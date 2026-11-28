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
package org.frontcache.cache;

import java.util.Comparator;

/**
 * The order and the range a cached-key listing is cut by - shared by every
 * {@link CacheProcessor#forEachCachedKey(String, String, int, java.util.function.Consumer)} so a
 * cursor one processor handed out means the same thing to the next page.
 * <p>
 * <b>The order is Unicode code-point order, not {@link String#compareTo}.</b> The L2 index lists its
 * keys in UTF-8 byte order, which is code-point order; {@code String.compareTo} compares UTF-16 code
 * units and disagrees with it for a key holding a character above U+FFFF against one holding a
 * character in U+E000-U+FFFF. A cursor compared one way against keys sorted the other skips or
 * repeats keys on exactly those pages.
 */
public final class CachedKeys {

	/** Code-point order - the order the L2 terms dictionary lists keys in. */
	public static final Comparator<String> ORDER = CachedKeys::compare;

	private CachedKeys() {
	}

	static int compare(String a, String b) {
		int i = 0;
		int j = 0;
		while (i < a.length() && j < b.length()) {
			int ca = a.codePointAt(i);
			int cb = b.codePointAt(j);
			if (ca != cb)
				return Integer.compare(ca, cb);
			i += Character.charCount(ca);
			j += Character.charCount(cb);
		}
		return Integer.compare(a.length() - i, b.length() - j);
	}

	/**
	 * Whether {@code key} belongs in a listing that starts with {@code prefix} and resumes after
	 * {@code after} - either may be null (or empty), meaning no restriction. {@code after} itself is
	 * never in range: it is the last key of the page before.
	 */
	public static boolean inRange(String key, String prefix, String after) {
		if (null != prefix && !key.startsWith(prefix))
			return false;
		return null == after || after.isEmpty() || compare(key, after) > 0;
	}
}
