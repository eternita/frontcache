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
package org.frontcache.bots;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import org.frontcache.core.FCHeaders;
import org.frontcache.guard.GuardConfigException;

/**
 * The visitor types a node knows: the two roots, {@code bot} and {@code guest}, and the types {@code bots.conf}
 * declares with {@code @type <name> like <bot|guest>} - at most {@value #MAX_DECLARED}, each based on a root.
 *
 * <b>A declared type IS its base everywhere it is not named.</b> A request has one visitor type (the leaf,
 * {@code customer}) and so one client type (its root, {@code guest}); {@code RequestContext.getClientType()} keeps
 * returning the root, so every consumer that does not know about declared types sees exactly what it saw before
 * they existed. What selects by type ({@code client=}, {@code client-type:}) selects by is-a - a type is itself and
 * its base; what looks a type up ({@code expireTimeMap}) looks up the leaf, then the base - {@link #ttl}. Both need
 * only the leaf and the root the request carries, never this object, so a {@code reload-bots} that removes a type
 * cannot leave a request in flight without an answer.
 *
 * A declaration may end with {@code no-store} ({@code @type customer like guest no-store}): a request of that type
 * <b>never writes</b> an entry, whatever the page's {@code maxage} says - it still reads one per its resolved TTL
 * ({@link #isNoStore}). One cached copy serves every type, so a type the origin renders differently for (a session
 * or tracking cookie) would otherwise have its render stored and served to everyone; the flag closes that by
 * configuration instead of by every page author remembering {@code customer:0}. It is decided when an entry would
 * be written, from the running vocabulary - not carried on the request - so a reload that adds it protects the
 * requests already in flight too.
 *
 * Immutable. One snapshot per {@link BotClassifier}; {@link #active()} is the running classifier's.
 */
public final class VisitorTypes {

	/** The most types a bots.conf may declare. It bounds every stored entry's expireTimeMap and the metric labels. */
	public static final int MAX_DECLARED = 16;

	/** The directive's keyword. */
	public static final String DIRECTIVE = "@type";

	/** The one flag a declaration may carry - see {@link #isNoStore}. */
	public static final String NO_STORE = "no-store";

	/** a type name: it becomes a JSON key, a log value and an HTML attribute value */
	private static final Pattern NAME = Pattern.compile("[a-z][a-z0-9-]{0,31}");

	/**
	 * Names no declaration may take: the roots, {@code browser} (the pre-2.9.0 name of {@code guest} - declaring it
	 * would give un-updated pages' {@code browser:} prefix a new meaning) and words reserved for selectors.
	 */
	private static final Set<String> RESERVED = Set.of(FCHeaders.REQUEST_CLIENT_TYPE_BOT,
			FCHeaders.REQUEST_CLIENT_TYPE_GUEST, "browser", "all", "none");

	/** No declarations - the two roots only. What a node without {@code @type} lines runs, and every node before. */
	public static final VisitorTypes ROOTS = new VisitorTypes(Collections.emptyMap(), Collections.emptySet());

	// declared name -> its base (a root), in declaration order
	private final Map<String, String> declared;

	// the declared names flagged no-store, in declaration order
	private final Set<String> noStore;

	private VisitorTypes(Map<String, String> declared, Set<String> noStore) {
		this.declared = declared;
		this.noStore = noStore;
	}

	/**
	 * @return the vocabulary of the running {@link BotClassifier}, or {@link #ROOTS} before one is loaded. Never
	 *         loads one: FCConfig and its static initializer are not this method's to start
	 */
	public static VisitorTypes active() {
		return BotClassifier.loadedVisitorTypes();
	}

	public static boolean isRoot(String name) {
		return FCHeaders.REQUEST_CLIENT_TYPE_BOT.equals(name) || FCHeaders.REQUEST_CLIENT_TYPE_GUEST.equals(name);
	}

	/** @return true for a root or a declared type */
	public boolean isKnown(String name) {
		return isRoot(name) || declared.containsKey(name);
	}

	/** @return the root a known type is based on - a root is its own - or null for a name this node does not know */
	public String rootOf(String name) {
		if (isRoot(name))
			return name;
		return declared.get(name);
	}

	/** @return the declared types (roots excluded), name -> base, in declaration order */
	public Map<String, String> declared() {
		return declared;
	}

	/**
	 * @return true when {@code name} is a type declared {@code no-store}: a request of it never writes an entry - not
	 *         on a miss, not by a soft refresh - and reads one per its resolved TTL. Never true of a root, nor of a
	 *         name this node does not know
	 */
	public boolean isNoStore(String name) {
		return null != name && noStore.contains(name);
	}

	/** @return the declared types flagged {@code no-store}, in declaration order */
	public Set<String> noStore() {
		return noStore;
	}

	/** @return the known names, roots first, for messages */
	public String describeKnown() {
		StringBuilder sb = new StringBuilder("'" + FCHeaders.REQUEST_CLIENT_TYPE_BOT + "', '"
				+ FCHeaders.REQUEST_CLIENT_TYPE_GUEST + "'");
		for (String name : declared.keySet())
			sb.append(", '").append(name).append("'");
		return sb.toString();
	}

	/**
	 * is-a: a request whose visitor type is {@code visitorType} (root {@code clientType}) is {@code name} when
	 * {@code name} is the leaf or the root. Two compares, no lookup.
	 */
	public static boolean isA(String visitorType, String clientType, String name) {
		return null != name && (name.equals(visitorType) || name.equals(clientType));
	}

	/**
	 * The expireTimeMap value that applies to a request: the leaf's if the entry names it, else the root's. The roots
	 * are always in a map {@code WebResponse} built, so for a well-formed entry the answer is never null; an absent
	 * declared type means <i>inherit</i>, an absent root is a bug the callers log.
	 *
	 * @return the stamp, or null when neither key is present
	 */
	public static Long ttl(Map<String, Long> expireTimeMap, String visitorType, String clientType) {
		if (null == expireTimeMap)
			return null;
		Long value = (null == visitorType) ? null : expireTimeMap.get(visitorType);
		if (null == value && null != clientType)
			value = expireTimeMap.get(clientType);
		return value;
	}

	/**
	 * Builds a vocabulary from {@code @type} lines, refusing each bad one alone - the file's rule: one bad line costs
	 * that line. Not thread safe; one per load.
	 */
	public static final class Builder {

		private final Map<String, String> declared = new LinkedHashMap<String, String>();

		private final Set<String> noStore = new LinkedHashSet<String>();

		/**
		 * @param line one trimmed bots.conf line that {@link #isDeclaration starts with the directive}
		 * @return a warning to log when the line was accepted with a remark (a duplicate - first wins), else null
		 * @throws GuardConfigException when the line cannot be used
		 */
		public String add(String line) {
			String body = stripComment(line).substring(DIRECTIVE.length()).trim();
			String[] tokens = body.isEmpty() ? new String[0] : body.split("\\s+");

			if (3 > tokens.length || !"like".equals(tokens[1]))
				throw new GuardConfigException("expected '" + DIRECTIVE + " <name> like <" + FCHeaders.REQUEST_CLIENT_TYPE_BOT
						+ "|" + FCHeaders.REQUEST_CLIENT_TYPE_GUEST + "> [" + NO_STORE + "]' but got '" + line + "'");

			// an unknown flag refuses the whole line: a misspelt no-store must not declare a type that stores
			boolean flaggedNoStore = false;
			for (int i = 3; i < tokens.length; i++)
			{
				if (!NO_STORE.equals(tokens[i]))
					throw new GuardConfigException("unknown flag '" + tokens[i] + "' in '" + line + "' - the one flag is '"
							+ NO_STORE + "'");
				flaggedNoStore = true;
			}

			String name = tokens[0];
			String base = tokens[2];

			if (!NAME.matcher(name).matches())
				throw new GuardConfigException("'" + name + "' is not a type name in '" + line
						+ "' - lower case letters, digits and '-', starting with a letter, at most 32 characters");

			if (RESERVED.contains(name))
				throw new GuardConfigException("'" + name + "' cannot be declared in '" + line + "' - "
						+ (isRoot(name) ? "it is a built-in type"
							: "browser".equals(name) ? "'browser' was renamed to '" + FCHeaders.REQUEST_CLIENT_TYPE_GUEST
								+ "' in 2.9.0, and a page still saying 'browser:' must not gain a new meaning"
							: "the name is reserved"));

			if (!isRoot(base))
				throw new GuardConfigException("'" + base + "' is not a base in '" + line + "' - a type is 'like "
						+ FCHeaders.REQUEST_CLIENT_TYPE_BOT + "' or 'like " + FCHeaders.REQUEST_CLIENT_TYPE_GUEST + "'"
						+ (declared.containsKey(base) ? "; a declared type cannot be a base - types are one level deep" : ""));

			if (declared.containsKey(name))
				return "duplicate declaration of '" + name + "' - the first wins, '" + line + "' ignored";

			if (MAX_DECLARED <= declared.size())
				throw new GuardConfigException("more than " + MAX_DECLARED + " types declared - '" + line + "' refused");

			declared.put(name, base);
			if (flaggedNoStore)
				noStore.add(name);
			return null;
		}

		public VisitorTypes build() {
			return declared.isEmpty() ? ROOTS : new VisitorTypes(Collections.unmodifiableMap(new LinkedHashMap<String, String>(declared)),
					Collections.unmodifiableSet(new LinkedHashSet<String>(noStore)));
		}
	}

	public static Builder builder() {
		return new Builder();
	}

	/** for tests - a vocabulary from declaration lines, every one of which must be accepted */
	public static VisitorTypes of(String... declarations) {
		Builder builder = builder();
		for (String line : declarations)
			builder.add(line);
		return builder.build();
	}

	/**
	 * @return true when the trimmed line is a declaration. Recognised by prefix, before a rule is told from a bare
	 *         keyword - no real User-Agent begins {@code @type }
	 */
	public static boolean isDeclaration(String trimmedLine) {
		return trimmedLine.startsWith(DIRECTIVE) && (trimmedLine.length() == DIRECTIVE.length()
				|| Character.isWhitespace(trimmedLine.charAt(DIRECTIVE.length())));
	}

	/** A declaration may end with a {@code # comment}: no name or base contains '#'. */
	private static String stripComment(String line) {
		int hash = line.indexOf('#');
		return (-1 < hash) ? line.substring(0, hash).trim() : line;
	}

	@Override
	public String toString() {
		return "VisitorTypes" + declared + (noStore.isEmpty() ? "" : " " + NO_STORE + noStore);
	}
}
