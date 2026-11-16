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
package org.frontcache.examples.debug;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import org.frontcache.plugin.serve.PluginRequest;

/**
 * The page: the request as the plugin's view shows it, and the guard's recent decisions about the visitor. Every
 * value is escaped; there is no script, and the stylesheet is the plugin's own ({@code /fc-x/debug/debug.css}), so
 * the page's Content-Security-Policy can forbid everything else.
 */
final class DebugPage {

	static final String CSP = "default-src 'none'; style-src 'self'; base-uri 'none'; form-action 'none';"
			+ " frame-ancestors 'none'";

	static final String CSS = """
			body { font: 14px/1.45 system-ui, sans-serif; margin: 2em auto; max-width: 70em; padding: 0 1em; color: #222; }
			h1 { font-size: 1.4em; } h2 { font-size: 1.1em; margin-top: 2em; }
			table { border-collapse: collapse; width: 100%; }
			th, td { text-align: left; vertical-align: top; padding: .3em .6em; border-bottom: 1px solid #ddd; }
			th { width: 14em; font-weight: 600; } td { font-family: ui-monospace, monospace; word-break: break-all; }
			thead th { width: auto; background: #f4f4f4; }
			.note { color: #666; } footer { margin-top: 3em; color: #888; font-size: .9em; }
			@media (prefers-color-scheme: dark) {
			  body { background: #161616; color: #ddd; } th, td { border-color: #333; } thead th { background: #222; }
			}
			""";

	private DebugPage() {
	}

	static String render(PluginRequest request, List<RecentDecisions.Decision> decisions, int recent, String nodeId,
			String coreVersion, String pluginVersion) {

		StringBuilder html = new StringBuilder(4096);
		html.append("<!doctype html>\n<html lang=\"en\"><head><meta charset=\"utf-8\">")
				.append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">")
				.append("<meta name=\"robots\" content=\"noindex\">")
				.append("<title>Frontcache - your request</title>")
				.append("<link rel=\"stylesheet\" href=\"/fc-x/debug/debug.css\"></head><body>\n")
				.append("<h1>Your request, as this node sees it</h1>\n");

		html.append("<table>");
		row(html, "Client address", request.clientAddress(),
				"resolved through front-cache.client-ip.trusted-proxies only - never a header a client could set");
		row(html, "Client type", request.clientType(), "the bots.conf verdict's root - bot or guest");
		row(html, "Visitor type", request.visitorType(), "the bots.conf verdict as written - a declared type, else the root");
		row(html, "Scheme", request.scheme(), null);
		row(html, "Request id", request.requestId(), "as the request log and every event carry it");
		row(html, "Method", request.method(), null);
		row(html, "Path", "/fc-x/debug" + request.path(), null);
		row(html, "Query", request.queryString(), null);
		html.append("</table>\n");

		html.append("<h2>Headers</h2>\n<p class=\"note\">As a plugin's view shows them: Authorization, Cookie and every"
				+ " x-frontcache-* header are never shown to a plugin.</p>\n<table>");
		Map<String, String> headers = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
		for (String name : request.headerNames())
			headers.put(name, request.header(name));
		if (headers.isEmpty())
			html.append("<tr><td>none</td></tr>");
		for (Map.Entry<String, String> h : headers.entrySet())
			row(html, h.getKey(), h.getValue(), null);
		html.append("</table>\n");

		html.append("<h2>The guard's recent decisions about your address</h2>\n");
		if (decisions.isEmpty())
			html.append("<p class=\"note\">None since the plugin started. It keeps the last ").append(recent)
					.append(" per visitor: rules that rejected, redirected or challenged a request of yours, dry runs,")
					.append(" and rate limits you went over.</p>\n");
		else
		{
			html.append("<table><thead><tr><th>time (UTC)</th><th>what</th><th>rule / bucket</th><th>outcome</th>")
					.append("<th>status</th><th>request</th><th>request id</th></tr></thead><tbody>");
			for (RecentDecisions.Decision d : decisions)
			{
				html.append("<tr><td>").append(escape(Instant.ofEpochMilli(d.time()).toString())).append("</td><td>")
						.append(escape(d.event())).append("</td><td>").append(escape(d.name())).append("</td><td>")
						.append(escape(d.outcome())).append("</td><td>").append((0 == d.status()) ? "" : d.status())
						.append("</td><td>").append(escape(d.uri())).append("</td><td>").append(escape(d.requestId()))
						.append("</td></tr>");
			}
			html.append("</tbody></table>\n");
		}

		html.append("<footer>node ").append(escape(nodeId)).append(" - Frontcache ").append(escape(coreVersion))
				.append(" - debug plugin ").append(escape(pluginVersion)).append("</footer>\n</body></html>\n");
		return html.toString();
	}

	private static void row(StringBuilder html, String name, String value, String note) {
		html.append("<tr><th>").append(escape(name)).append("</th><td>")
				.append((null == value) ? "<span class=\"note\">none</span>" : escape(value));
		if (null != note)
			html.append(" <span class=\"note\">- ").append(escape(note)).append("</span>");
		html.append("</td></tr>");
	}

	/** HTML-escapes text for an element or a double-quoted attribute. Null is empty. */
	static String escape(String text) {
		if (null == text)
			return "";
		StringBuilder out = new StringBuilder(text.length() + 16);
		for (int i = 0; i < text.length(); i++)
		{
			char c = text.charAt(i);
			switch (c) {
				case '&' -> out.append("&amp;");
				case '<' -> out.append("&lt;");
				case '>' -> out.append("&gt;");
				case '"' -> out.append("&quot;");
				case '\'' -> out.append("&#39;");
				default -> {
					if (c < 0x20 && c != '\t')
						out.append(' ');
					else
						out.append(c);
				}
			}
		}
		return out.toString();
	}
}
