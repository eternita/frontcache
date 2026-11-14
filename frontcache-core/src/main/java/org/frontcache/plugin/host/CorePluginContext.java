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

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.HexFormat;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.frontcache.FCConfig;
import org.frontcache.cache.CacheManager;
import org.frontcache.cache.CacheProcessor;
import org.frontcache.core.EvidenceCookies;
import org.frontcache.license.BuildInfo;
import org.frontcache.plugin.PluginKind;
import org.frontcache.plugin.PluginManifest;
import org.frontcache.plugin.serve.EvidenceSigner;
import org.frontcache.warmer.WarmRequest;

/** The engine's {@link BundledPluginContext}: each answer is read when asked, so a plugin never holds a stale one. */
final class CorePluginContext implements BundledPluginContext {

	/** The client-key hash's key: per process, in memory only (PluginContext.hashClientKey). */
	private static final byte[] CLIENT_KEY_SECRET = new byte[32];
	static {
		new SecureRandom().nextBytes(CLIENT_KEY_SECRET);
	}

	private static final ThreadLocal<Mac> HMAC = ThreadLocal.withInitial(() -> {
		try {
			Mac mac = Mac.getInstance("HmacSHA256");
			mac.init(new SecretKeySpec(CLIENT_KEY_SECRET, "HmacSHA256"));
			return mac;
		} catch (GeneralSecurityException e) {
			throw new IllegalStateException("HmacSHA256 is not available", e);
		}
	});

	private final PluginManifest manifest;
	private final String publicDomain;

	/** @param publicDomain {@code FrontCacheEngine.getPublicDomain()} - null when unset */
	CorePluginContext(PluginManifest manifest, String publicDomain) {
		this.manifest = manifest;
		this.publicDomain = publicDomain;
	}

	@Override
	public PluginManifest manifest() {
		return manifest;
	}

	@Override
	public String publicDomain() {
		return publicDomain;
	}

	@Override
	public String nodeId() {
		return FCConfig.getProperty(FCConfig.FRONTCACHE_ID_KEY);
	}

	@Override
	public String coreVersion() {
		return BuildInfo.current().getVersion();
	}

	@Override
	public File frontcacheHome() {
		String home = System.getProperty(FCConfig.FRONT_CACHE_HOME_SYSTEM_KEY);
		return (null == home || home.isBlank()) ? null : new File(home);
	}

	@Override
	public File dataDirectory() {
		File home = frontcacheHome();
		if (null == home)
			return null;
		File dir = new File(home, "plugins/" + manifest.id() + "/data");
		return (dir.isDirectory() || dir.mkdirs()) ? dir : null;
	}

	@Override
	public String hashClientKey(String clientKey) {
		return hash(clientKey);
	}

	/** 128 bits of HMAC-SHA256 over the key, hex. */
	static String hash(String clientKey) {
		if (null == clientKey)
			return null;
		byte[] mac = HMAC.get().doFinal(clientKey.getBytes(StandardCharsets.UTF_8));
		return HexFormat.of().formatHex(mac, 0, 16);
	}

	@Override
	public EvidenceSigner evidenceSigner() {
		if (!manifest.declares(PluginKind.SERVE) || manifest.cookies().isEmpty() || !EvidenceCookies.isConfigured())
			return null;
		return new CoreEvidenceSigner(manifest);
	}

	@Override
	public String property(String key) {
		return FCConfig.getProperty(key);
	}

	@Override
	public CacheProcessor existingCacheProcessor() {
		return CacheManager.existingInstance();
	}

	@Override
	public String warmRequestToken() {
		return WarmRequest.token();
	}
}
