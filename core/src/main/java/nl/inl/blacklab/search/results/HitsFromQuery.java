package nl.inl.blacklab.search.results;

import java.io.IOException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import org.apache.lucene.index.IndexReader;
import org.apache.lucene.index.LeafReaderContext;
import org.apache.lucene.index.Term;
import org.apache.lucene.index.TermContext;
import org.apache.lucene.search.DocIdSetIterator;
import org.apache.lucene.search.spans.SpanWeight;
import org.apache.lucene.search.spans.SpanWeight.Postings;
import org.apache.lucene.search.spans.Spans;

import nl.inl.blacklab.exceptions.BlackLabRuntimeException;
import nl.inl.blacklab.exceptions.WildcardTermTooBroad;
import nl.inl.blacklab.search.BlackLabIndex;
import nl.inl.blacklab.search.BlackLabIndexImpl;
import nl.inl.blacklab.search.Span;
import nl.inl.blacklab.search.lucene.BLSpanQuery;
import nl.inl.blacklab.search.lucene.BLSpans;
import nl.inl.blacklab.search.lucene.HitQueryContext;

/**
 * A Hits object that is filled from a BLSpanQuery.
 */
public class HitsFromQuery extends Hits {

    /** Max. hits to process/count. */
    MaxSettings maxSettings;
    
    /** Did we exceed the maximums? */
    MaxStats maxStats;
    
    /**
     * The SpanWeight for our SpanQuery, from which we can get the next Spans when
     * the current one's done.
     */
    private SpanWeight weight;

    /**
     * The LeafReaderContexts we should query in succession.
     */
    private List<LeafReaderContext> atomicReaderContexts;

    /**
     * What LeafReaderContext we're querying now.
     */
    private int atomicReaderContextIndex = -1;

    /**
     * Term contexts for the terms in the query.
     */
    private Map<Term, TermContext> termContexts;

    /**
     * docBase of the segment we're currently in
     */
    private int currentDocBase;

    /**
     * Our Spans object, which may not have been fully read yet.
     */
    private BLSpans currentSourceSpans;

    /**
     * Did we completely read our Spans object?
     */
    private boolean sourceSpansFullyRead = true;

    private Lock ensureHitsReadLock = new ReentrantLock();
    
    /** Context of our query; mostly used to keep track of captured groups. */
    private HitQueryContext hitQueryContext;
    
    /**
     * Document the previous hit was in, so we can count separate documents.
     */
    private int previousHitDoc = -1;

    /**
     * Construct a Hits object from a SpanQuery.
     *
     * @param index the index object
     * @param field field our hits came from
     * @param sourceQuery the query to execute to get the hits
     * @throws WildcardTermTooBroad if the query is overly broad (expands to too many terms)
     */
    HitsFromQuery(QueryInfo queryInfo, BLSpanQuery sourceQuery, MaxSettings maxSettings) throws WildcardTermTooBroad {
        super(queryInfo);
        this.maxSettings = maxSettings;
        this.maxStats = new MaxStats();
        hitsCounted = 0;
        hitQueryContext = new HitQueryContext();
        try {
            BlackLabIndex index = queryInfo.index();
            IndexReader reader = index.reader();
            if (BlackLabIndexImpl.isTraceQueryExecution())
                logger.debug("Hits(): optimize");
            BLSpanQuery optimize = sourceQuery.optimize(reader);

            if (BlackLabIndexImpl.isTraceQueryExecution())
                logger.debug("Hits(): rewrite");
            BLSpanQuery spanQuery = optimize.rewrite(reader);

            //System.err.println(spanQuery);
            termContexts = new HashMap<>();
            Set<Term> terms = new HashSet<>();
            spanQuery = BLSpanQuery.ensureSortedUnique(spanQuery);
            if (BlackLabIndexImpl.isTraceQueryExecution())
                logger.debug("Hits(): createWeight");
            weight = spanQuery.createWeight(index.searcher(), false);
            weight.extractTerms(terms);
            if (BlackLabIndexImpl.isTraceQueryExecution())
                logger.debug("Hits(): extract terms");
            for (Term term : terms) {
                try {
                    threadPauser.waitIfPaused();
                } catch (InterruptedException e) {
                    // Taking too long, break it off.
                    // Not a very graceful way to do it... but at least it won't
                    // be stuck forever.
                    Thread.currentThread().interrupt(); // client can check this
                    throw new BlackLabRuntimeException("Query matches too many terms; aborted.");
                }
                termContexts.put(term, TermContext.build(reader.getContext(), term));
            }

            currentSourceSpans = null;
            atomicReaderContexts = reader == null ? null : reader.leaves();
            atomicReaderContextIndex = -1;
        } catch (IOException e) {
            throw BlackLabRuntimeException.wrap(e);
        }

        sourceSpansFullyRead = false;
        if (BlackLabIndexImpl.isTraceQueryExecution())
            logger.debug("Hits(): done");
    }
    
    @Override
    public String toString() {
        return "Hits#" + hitsObjId + " (fullyRead=" + sourceSpansFullyRead + ", hits.size()=" + results.size() + ")";
    }

    /**
     * Ensure that we have read at least as many hits as specified in the parameter.
     *
     * @param number the minimum number of hits that will have been read when this
     *            method returns (unless there are fewer hits than this); if
     *            negative, reads all hits
     * @throws InterruptedException if the thread was interrupted during this
     *             operation
     */
    @Override
    protected void ensureResultsRead(int number) throws InterruptedException {
        // Prevent locking when not required
        if (sourceSpansFullyRead || (number >= 0 && results.size() > number))
            return;

        // At least one hit needs to be fetched.
        // Make sure we fetch at least FETCH_HITS_MIN while we're at it, to avoid too much locking.
        if (number >= 0 && number - results.size() < FETCH_HITS_MIN)
            number = results.size() + FETCH_HITS_MIN;

        while (!ensureHitsReadLock.tryLock()) {
            /*
             * Another thread is already counting, we don't want to straight up block until it's done
             * as it might be counting/retrieving all results, while we might only want trying to retrieve a small fraction
             * So instead poll our own state, then if we're still missing results after that just count them ourselves
             */
            Thread.sleep(50);
            if (sourceSpansFullyRead || (number >= 0 && results.size() >= number))
                return;
        }
        try {
            boolean readAllHits = number < 0;
            int maxHitsToCount = maxSettings.maxHitsToCount();
            int maxHitsToProcess = maxSettings.maxHitsToProcess();
            while (readAllHits || results.size() < number) {

                // Pause if asked
                threadPauser.waitIfPaused();

                // Stop if we're at the maximum number of hits we want to count
                if (maxHitsToCount >= 0 && hitsCounted >= maxHitsToCount) {
                    maxStats.setHitsCountedExceededMaximum();
                    break;
                }

                // Get the next hit from the spans, moving to the next
                // segment when necessary.
                while (true) {
                    while (currentSourceSpans == null) {
                        // Exhausted (or not started yet); get next segment spans.

                        atomicReaderContextIndex++;
                        if (atomicReaderContexts != null && atomicReaderContextIndex >= atomicReaderContexts.size()) {
                            setFinished();
                            return;
                        }
                        if (atomicReaderContexts != null) {
                            // Get the atomic reader context and get the next Spans from it.
                            LeafReaderContext context = atomicReaderContexts.get(atomicReaderContextIndex);
                            currentDocBase = context.docBase;
                            BLSpans spans = (BLSpans) weight.getSpans(context, Postings.OFFSETS);
                            currentSourceSpans = spans; //BLSpansWrapper.optWrapSortUniq(spans);
                        } else {
                            // TESTING
                            currentDocBase = 0;
                            if (atomicReaderContextIndex > 0) {
                                setFinished();
                                return;
                            }
                            BLSpans spans = (BLSpans) weight.getSpans(null, Postings.OFFSETS);
                            currentSourceSpans = spans; //BLSpansWrapper.optWrapSortUniq(spans);
                        }

                        if (currentSourceSpans != null) {
                            // Update the hit query context with our new spans,
                            // and notify the spans of the hit query context
                            // (TODO: figure out if we need to call setHitQueryContext()
                            //    for each segment or not; if it's just about capture groups
                            //    registering themselves, we only need that for the first Spans.
                            //    But it's probably required for backreferences, etc. anyway,
                            //    and there won't be that many segments, so it's probably ok)
                            hitQueryContext.setSpans(currentSourceSpans);
                            currentSourceSpans.setHitQueryContext(hitQueryContext); // let captured groups register themselves
                            if (capturedGroups == null && hitQueryContext.numberOfCapturedGroups() > 0) {
                                capturedGroups = new CapturedGroupsImpl(hitQueryContext.getCapturedGroupNames());
                            }

                            int doc = currentSourceSpans.nextDoc();
                            if (doc == DocIdSetIterator.NO_MORE_DOCS)
                                currentSourceSpans = null; // no matching docs in this segment, try next
                        }
                    }

                    // Advance to next hit
                    int start = currentSourceSpans.nextStartPosition();
                    if (start == Spans.NO_MORE_POSITIONS) {
                        int doc = currentSourceSpans.nextDoc();
                        if (doc != DocIdSetIterator.NO_MORE_DOCS) {
                            // Go to first hit in doc
                            start = currentSourceSpans.nextStartPosition();
                        } else {
                            // This one is exhausted; go to the next one.
                            currentSourceSpans = null;
                        }
                    }
                    if (currentSourceSpans != null) {
                        // We're at the next hit.
                        break;
                    }
                }

                // Count the hit and add it (unless we've reached the maximum number of hits we
                // want)
                hitsCounted++;
                int hitDoc = currentSourceSpans.docID() + currentDocBase;
                boolean maxHitsProcessed = maxStats.hitsProcessedExceededMaximum();
                if (hitDoc != previousHitDoc) {
                    docsCounted++;
                    if (!maxHitsProcessed)
                        docsRetrieved++;
                    previousHitDoc = hitDoc;
                }
                if (!maxHitsProcessed) {
                    Hit hit = currentSourceSpans.getHit();
                    Hit offsetHit = HitImpl.create(hit.doc() + currentDocBase, hit.start(), hit.end());
                    if (capturedGroups != null) {
                        Span[] groups = new Span[hitQueryContext.numberOfCapturedGroups()];
                        hitQueryContext.getCapturedGroups(groups);
                        capturedGroups.put(offsetHit, groups);
                    }
                    results.add(offsetHit);
                    if (maxHitsToProcess >= 0 && results.size() >= maxHitsToProcess) {
                        maxStats.setHitsProcessedExceededMaximum();
                    }
                }
            }
        } catch (InterruptedException e) {
            // We've stopped retrieving/counting
            maxStats.setHitsProcessedExceededMaximum();
            maxStats.setHitsCountedExceededMaximum();
            throw e;
        } catch (IOException e) {
            throw BlackLabRuntimeException.wrap(e);
        } finally {
            ensureHitsReadLock.unlock();
        }
    }

    private void setFinished() {
        sourceSpansFullyRead = true;
        
        // We no longer need these; allow them to be GC'ed
        weight = null;
        atomicReaderContexts = null;
        termContexts = null;
        currentSourceSpans = null;
        hitQueryContext = null;
    }

    @Override
    public boolean doneProcessingAndCounting() {
        return sourceSpansFullyRead || maxStats.hitsCountedExceededMaximum();
    }
    
    @Override
    public MaxStats maxStats() {
        return maxStats;
    }

}
