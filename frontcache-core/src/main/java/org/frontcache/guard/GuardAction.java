/*
 * Copyright 2015 Eternita
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
package org.frontcache.guard;

import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;

import jakarta.servlet.http.HttpServletRequest;

import org.frontcache.core.RequestContext;

/**
 * What a matching guard rule does with the request: reject it, redirect it, or explicitly let it
 * through (an exemption placed above a broader rule).
 */
public class GuardAction {

	public enum Type {
		REJECT, REDIRECT, ALLOW
	}

	/** disposition written to the failed-requests log (the is_cached column) */
	public static final String DISPOSITION_REJECTED = "rejected";
	public static final String DISPOSITION_REDIRECTED = "redirected";
	public static final String DISPOSITION_DRY_RUN = "dry-run";

	private static final GuardAction ALLOW = new GuardAction(Type.ALLOW, 0, null, null);

	private final Type type;
	private final int status;
	private final String body;    // REJECT only
	private final String target;  // REDIRECT only - may contain ${...} placeholders

	private GuardAction(Type type, int status, String body, String target) {
		this.type = type;
		this.status = status;
		this.body = body;
		this.target = target;
	}

	public static GuardAction reject(int status, String body) {
		return new GuardAction(Type.REJECT, status, body, null);
	}

	public static GuardAction redirect(int status, String target) {
		return new GuardAction(Type.REDIRECT, status, null, target);
	}

	public static GuardAction allow() {
		return ALLOW;
	}

	public Type getType() {
		return type;
	}

	public int getStatus() {
		return status;
	}

	public String getBody() {
		return body;
	}

	/**
	 * @return redirect target as configured, placeholders NOT expanded
	 */
	public String getTarget() {
		return target;
	}

	/**
	 * @return the disposition string for the log line, or null for ALLOW (not logged)
	 */
	public String getDisposition() {
		switch (type)
		{
		case REJECT:
			return DISPOSITION_REJECTED;
		case REDIRECT:
			return DISPOSITION_REDIRECTED;
		default:
			return null;
		}
	}

	/**
	 * Expands ${uri} / ${query} / ${url} / ${url:enc} / ${host} in the redirect target.
	 *
	 * @param context current request
	 * @return target ready for the Location header
	 */
	public String resolveTarget(RequestContext context)
	{
		if (null == target || -1 == target.indexOf("${"))
			return target;

		String url = requestURL(context);
		String resolved = target;
		resolved = replace(resolved, "${url:enc}", urlEncode(url));
		resolved = replace(resolved, "${url}", url);
		resolved = replace(resolved, "${uri}", nullToEmpty(context.getRequestURI()));
		resolved = replace(resolved, "${query}", nullToEmpty(context.getRequestQueryString()));
		resolved = replace(resolved, "${host}", publicHost(context));
		return resolved;
	}

	/**
	 * The hostname to PUT IN a URL we hand to a client - the site's public name
	 * (front-cache.default-domain), not the Host this particular request arrived under.
	 *
	 * The two are different questions and conflating them is the bug in
	 * docs/archive/guard-redirect-url-host-fix.md: a node is reachable under the CDN's name, an internal
	 * alias, its own hostname and a bare IP, and every one of those used to end up in a browser's
	 * address bar via ${url}. Predicates (host~, host:ip) still read getFrontCacheHost(), because
	 * they ask the opposite question - what actually arrived.
	 *
	 * Falls back to the arriving host when the node names no public host, which is what a default
	 * FRONTCACHE_HOME does. Never emits FCConfig.DEFAULT_DOMAIN - see
	 * FrontCacheEngine.getPublicDomain().
	 */
	private static String publicHost(RequestContext context)
	{
		String publicHost = context.getPublicHost();
		if (null != publicHost && 0 < publicHost.length())
			return publicHost;

		return nullToEmpty(context.getFrontCacheHost());
	}

	/**
	 * Full request URL, as the client would have to type it to get back here. Built here rather than
	 * taken from RequestContext.getCurrentRequestURL(), which FrontCacheEngine only populates later
	 * and only for cacheable requests - guards run before that.
	 *
	 * Note the two independent substitutions relative to what the socket saw: the host (see
	 * {@link #publicHost}) and the scheme. Behind a TLS-terminating proxy the connection reaching
	 * this process is plaintext, so the scheme comes from RequestContext.getPublicProtocol()
	 * (X-Forwarded-Proto from a trusted peer, or front-cache.public-scheme) and only then from the
	 * connection. On the example-site edges roughly one request in ten arrived over the plaintext leg,
	 * and each one produced an http:// return URL for an https-only site.
	 */
	public static String requestURL(RequestContext context)
	{
		String protocol = context.getPublicProtocol();
		if (null == protocol)
			protocol = context.getFrontCacheProtocol();
		if (null == protocol)
			protocol = "http";

		String configuredHost = context.getPublicHost();
		boolean isPublicHost = (null != configuredHost && 0 < configuredHost.length());
		String host = isPublicHost ? configuredHost : nullToEmpty(context.getFrontCacheHost());

		return protocol + "://" + host + port(context, protocol, isPublicHost)
				+ nullToEmpty(context.getRequestURI()) + nullToEmpty(context.getRequestQueryString());
	}

	/**
	 * The ":port" of an emitted URL, or "" for the default port of its scheme.
	 *
	 * With a public host configured this is the port the SITE is published on
	 * (front-cache.http-port / front-cache.https-port), not the port this request happened to land
	 * on - behind a proxy those differ, and the request's own port belongs to the internal leg. Only
	 * the fallback path, which is already echoing the arriving host, echoes the arriving port too.
	 */
	private static String port(RequestContext context, String protocol, boolean isPublicHost)
	{
		boolean secure = "https".equals(protocol);

		if (isPublicHost)
		{
			String configured = secure ? context.getFrontCacheHttpsPort() : context.getFrontCacheHttpPort();
			if (null == configured || 0 == configured.trim().length())
				return "";

			configured = configured.trim();
			if ((secure && "443".equals(configured)) || (!secure && "80".equals(configured)))
				return "";

			return ":" + configured;
		}

		HttpServletRequest request = context.getRequest();
		if (null == request)
			return "";

		try {
			int port = request.getServerPort();
			if ((80 == port && !secure) || (443 == port && secure))
				return "";

			return ":" + port;
		} catch (Exception e) {
			return ""; // recycled request - the URL is still useful without the port
		}
	}

	private static String replace(String value, String placeholder, String replacement)
	{
		int idx = value.indexOf(placeholder);
		if (-1 == idx)
			return value;

		StringBuilder sb = new StringBuilder();
		int from = 0;
		while (-1 < idx)
		{
			sb.append(value, from, idx).append(replacement);
			from = idx + placeholder.length();
			idx = value.indexOf(placeholder, from);
		}
		sb.append(value, from, value.length());
		return sb.toString();
	}

	private static String urlEncode(String value)
	{
		try {
			return URLEncoder.encode(value, "UTF-8");
		} catch (UnsupportedEncodingException e) {
			return value; // UTF-8 is always present
		}
	}

	private static String nullToEmpty(String value) {
		return (null != value) ? value : "";
	}

	@Override
	public String toString()
	{
		switch (type)
		{
		case REJECT:
			return "reject:" + status;
		case REDIRECT:
			return "redirect:" + status + " " + target;
		default:
			return "allow";
		}
	}

}
