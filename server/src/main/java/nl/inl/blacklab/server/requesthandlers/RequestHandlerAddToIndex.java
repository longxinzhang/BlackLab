package nl.inl.blacklab.server.requesthandlers;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;

import javax.servlet.http.HttpServletRequest;

import org.apache.commons.fileupload.FileItem;

import nl.inl.blacklab.index.IndexListenerReportConsole;
import nl.inl.blacklab.index.Indexer;
import nl.inl.blacklab.server.BlackLabServer;
import nl.inl.blacklab.server.datastream.DataStream;
import nl.inl.blacklab.server.exceptions.BadRequest;
import nl.inl.blacklab.server.exceptions.BlsException;
import nl.inl.blacklab.server.exceptions.InternalServerError;
import nl.inl.blacklab.server.exceptions.NotAuthorized;
import nl.inl.blacklab.server.index.Index;
import nl.inl.blacklab.server.jobs.User;
import nl.inl.blacklab.server.util.FileUploadHandler;

/**
 * Display the contents of the cache.
 */
public class RequestHandlerAddToIndex extends RequestHandler {

	String indexError = null;

	public RequestHandlerAddToIndex(BlackLabServer servlet,
			HttpServletRequest request, User user, String indexName,
			String urlResource, String urlPathPart) {
		super(servlet, request, user, indexName, urlResource, urlPathPart);
	}

	@Override
	public int handle(DataStream ds) throws BlsException {
		debug(logger, "REQ add data: " + indexName);

		// TODO have we verified this index belongs to this user?
		if (!Index.isUserIndex(indexName))
			throw new NotAuthorized("Can only add to private indices.");

		FileItem file = FileUploadHandler.getFile(request, "data");
		Index index = indexMan.getIndex(indexName);
		Indexer indexer = index.getIndexer();
		indexer.setListener(new IndexListenerReportConsole() {
			@Override
			public synchronized boolean errorOccurred(String error, String unitType, File unit, File subunit) {
				indexError = error + " in " + unit + (subunit == null ? "" : " (" + subunit + ")");
				super.errorOccurred(error, unitType, unit, subunit);
				return false; // Don't continue indexing
			}
		});

		try (InputStream is = file.getInputStream()) {
			indexer.index(file.getName(), is);
		} catch(IOException e) {
			throw new InternalServerError("Error occured during indexing: " + e.getMessage(), 41);
		} finally {
			if (indexError != null)
				indexer.rollback();

			indexer.close();
		}

		if (indexError != null)
			throw new BadRequest("INDEX_ERROR", "An error occurred during indexing. (error text: " + indexError + ")");
		return Response.success(ds, "Data added succesfully.");
	}

}
