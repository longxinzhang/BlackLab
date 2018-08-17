/*******************************************************************************
 * Copyright (c) 2010, 2012 Institute for Dutch Lexicology
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *******************************************************************************/
package nl.inl.blacklab.search.results;

import java.util.List;

import nl.inl.blacklab.resultproperty.PropertyValue;

/**
 * A group of results, with its group identity and the results themselves, that
 * you can access randomly (i.e. you can obtain a list of Hit objects)
 */
public class HitGroup extends Group<Hit> {

    HitGroup(QueryInfo queryInfo, PropertyValue groupIdentity, int totalSize) {
        super(groupIdentity, Hits.emptyList(queryInfo), totalSize);
    }

    /**
     * Wraps a list of Hit objects with the HitGroup interface.
     *
     * NOTE: the list is not copied!
     *
     * @param queryInfo query info
     * @param storedResults the hits we actually stored
     * @param totalSize total group size
     */
    HitGroup(QueryInfo queryInfo, PropertyValue groupIdentity, List<Hit> storedResults, int totalSize) {
        super(groupIdentity, Hits.list(queryInfo, storedResults), totalSize);
    }

    /**
     * Wraps a list of Hit objects with the HitGroup interface.
     *
     * NOTE: the list is not copied!
     *
     * @param queryInfo query info
     * @param storedResults the hits
     */
    HitGroup(PropertyValue groupIdentity, Hits storedResults, int totalSize) {
        super(groupIdentity, storedResults, totalSize);
    }
    
    @Override
    public Hits getStoredResults() {
        return (Hits)super.getStoredResults();
    }
}
