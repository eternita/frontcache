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
package org.frontcache.plugin.host;

import java.time.Duration;
import java.util.EnumSet;
import java.util.Set;

import org.frontcache.core.EvidenceCookies;
import org.frontcache.plugin.PluginManifest;
import org.frontcache.plugin.event.ChallengeResult;
import org.frontcache.plugin.serve.EvidenceSigner;
import org.frontcache.plugin.serve.PluginRequest;

/**
 * One serving plugin's {@link EvidenceSigner}: it signs only the cookies the plugin's manifest names, only for a
 * provider it names, only on a request the node handed that plugin - and binds a pass only to what the node resolved
 * from that request. The key never leaves {@link EvidenceCookies}.
 */
final class CoreEvidenceSigner implements EvidenceSigner {

	private final PluginManifest manifest;

	CoreEvidenceSigner(PluginManifest manifest) {
		this.manifest = manifest;
	}

	@Override
	public String sign(PluginRequest request, String provider, String cookieName, Duration ttl, Binding... bindings) {

		CorePluginRequest own = own(request);
		checkProvider(provider);
		if (null == cookieName || !manifest.cookies().contains(cookieName))
			throw new IllegalArgumentException("plugin '" + manifest.id() + "' does not name the cookie '" + cookieName
					+ "' in its manifest - it names " + manifest.cookies());
		if (null == ttl || ttl.isNegative() || ttl.isZero() || 0 < ttl.compareTo(MAX_TTL))
			throw new IllegalArgumentException("a pass lives more than zero and at most " + MAX_TTL + ", not " + ttl);

		Set<Binding> bound = EnumSet.noneOf(Binding.class);
		if (null != bindings)
			for (Binding binding : bindings)
				if (null != binding)
					bound.add(binding);

		String binding = null;
		if (bound.contains(Binding.CLIENT_IP) && bound.contains(Binding.USER_AGENT))
			binding = EvidenceCookies.BIND_IP_UA;
		else if (bound.contains(Binding.CLIENT_IP))
			binding = EvidenceCookies.BIND_IP;
		else if (bound.contains(Binding.USER_AGENT))
			binding = EvidenceCookies.BIND_UA;

		long expiry = System.currentTimeMillis() / 1000L + ttl.getSeconds();
		String value = EvidenceCookies.sign(cookieName, expiry, binding, own.context().getResolvedClientIP(),
				own.context().getUserAgent());

		CoreEvents.challengeResult(own.context(), provider, manifest.id(), ChallengeResult.PASS);
		return value;
	}

	@Override
	public void failed(PluginRequest request, String provider, boolean error) {
		CorePluginRequest own = own(request);
		checkProvider(provider);
		CoreEvents.challengeResult(own.context(), provider, manifest.id(), error ? ChallengeResult.ERROR : ChallengeResult.FAIL);
	}

	private CorePluginRequest own(PluginRequest request) {
		if (!(request instanceof CorePluginRequest own) || !manifest.id().equals(own.pluginId()))
			throw new IllegalArgumentException("not a request the node handed plugin '" + manifest.id() + "'");
		return own;
	}

	private void checkProvider(String provider) {
		if (null == provider || !manifest.challenges().contains(provider))
			throw new IllegalArgumentException("plugin '" + manifest.id() + "' does not name the challenge provider '"
					+ provider + "' in its manifest - it names " + manifest.challenges());
	}
}
