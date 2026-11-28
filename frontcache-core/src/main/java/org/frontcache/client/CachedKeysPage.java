/*
 * Copyright 2018 Eternita
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
 */
package org.frontcache.client;

import java.io.IOException;
import java.util.Collections;
import java.util.List;

/**
 * One page of a node's cached keys - {@link FrontCacheClient#getCachedKeysPage}. The next page is
 * asked for with {@link #nextAfter()} while {@link #isMore()}.
 */
public final class CachedKeysPage {

	private final List<String> keys;
	private final boolean more;

	public CachedKeysPage(List<String> keys, boolean more) {
		this.keys = Collections.unmodifiableList(keys);
		this.more = more;
	}

	/** The keys, in the node's key order. */
	public List<String> getKeys() {
		return keys;
	}

	/** Whether keys follow this page - exact, not a guess from a full page. */
	public boolean isMore() {
		return more;
	}

	/** The cursor for the next page: the last key of this one, or null for an empty page. */
	public String nextAfter() {
		return keys.isEmpty() ? null : keys.get(keys.size() - 1);
	}

	/**
	 * The node answered a paged request without paging: it predates it, ignored {@code limit} and
	 * began listing every key. The client dropped the connection rather than read the listing -
	 * {@link FrontCacheClient#getCachedKeys(java.io.OutputStream)} is how to read one from such a node.
	 */
	public static final class PagingNotSupportedException extends IOException {
		private static final long serialVersionUID = 1L;

		public PagingNotSupportedException(String message) {
			super(message);
		}
	}
}
