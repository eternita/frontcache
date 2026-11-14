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
package org.frontcache.plugin.testkit;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.frontcache.plugin.PluginManifest;
import org.frontcache.plugin.serve.EvidenceSigner;
import org.frontcache.plugin.serve.PluginRequest;

/**
 * An {@link EvidenceSigner} that checks what the node checks - a cookie and a provider the manifest names, a ttl within
 * {@link EvidenceSigner#MAX_TTL}, an address to bind to - and records each call instead of signing: what a pass is
 * worth is the core's {@code cookie-hmac:} predicate's business, never a plugin's test's.
 */
public final class RecordingEvidenceSigner implements EvidenceSigner {

	/** One call: {@code pass} or {@code fail} / {@code error}. */
	public record Call(String outcome, String provider, String cookieName, Duration ttl, List<Binding> bindings,
			String requestId) {
	}

	private final PluginManifest manifest;
	private final List<Call> calls = new ArrayList<>();

	public RecordingEvidenceSigner(PluginManifest manifest) {
		this.manifest = manifest;
	}

	@Override
	public synchronized String sign(PluginRequest request, String provider, String cookieName, Duration ttl,
			Binding... bindings) {
		if (null == request)
			throw new IllegalArgumentException("no request");
		if (!manifest.cookies().contains(cookieName))
			throw new IllegalArgumentException("cookie '" + cookieName + "' is not named in the manifest");
		if (!manifest.challenges().contains(provider))
			throw new IllegalArgumentException("provider '" + provider + "' is not named in the manifest");
		if (null == ttl || ttl.isNegative() || ttl.isZero() || ttl.compareTo(MAX_TTL) > 0)
			throw new IllegalArgumentException("ttl out of range: " + ttl);
		List<Binding> bound = Arrays.asList(bindings);
		if (bound.contains(Binding.CLIENT_IP) && null == request.clientAddress())
			throw new IllegalArgumentException("CLIENT_IP binding without a resolved address");
		calls.add(new Call("pass", provider, cookieName, ttl, List.copyOf(bound), request.requestId()));
		return (System.currentTimeMillis() / 1000 + ttl.toSeconds()) + ".test-signature";
	}

	@Override
	public synchronized void failed(PluginRequest request, String provider, boolean error) {
		calls.add(new Call(error ? "error" : "fail", provider, null, null, List.of(), request.requestId()));
	}

	public synchronized List<Call> calls() {
		return List.copyOf(calls);
	}
}
