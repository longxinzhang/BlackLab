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
package nl.inl.blacklab.search;


/**
 * NOT operator for TextPattern queries at token and sequence level
 */
public class TextPatternNot extends TextPatternCombiner {
	public TextPatternNot(TextPattern clause) {
		super(clause);
	}

	@Override
	public <T> T translate(TextPatternTranslator<T> translator, String fieldName) {
		throw new UnsupportedOperationException("NOT not yet implemented");
		//return translator.not(fieldName, clauses.get(0).translate(translator, fieldName));
	}
}
