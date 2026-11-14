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

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.atomic.AtomicInteger;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.frontcache.plugin.PluginContext;
import org.frontcache.plugin.PluginKind;
import org.frontcache.plugin.PluginManifest;
import org.frontcache.plugin.serve.EvidenceSigner;

/**
 * A node, as a dropped-in plugin's {@link PluginContext} shows it - a plain {@code PluginContext}, never the core's
 * richer bundled one:
 *
 * <ul>
 * <li>a temporary FRONTCACHE_HOME with {@code conf/} and {@code plugins/<id>/data} ({@link #delete()} removes it);</li>
 * <li>a node id, a public domain and the core version, settable;</li>
 * <li>{@link #hashClientKey} keyed per context, as the node keys it per process: HMAC-SHA256, 128 bits, hex;</li>
 * <li>a real {@link #scheduler()} - one {@link PluginScheduler} per start, cancelled on stop;</li>
 * <li>{@link #writeList} with the node's rules ({@link ManagedLists}), for a plugin declaring {@code manage};</li>
 * <li>an {@link #evidenceSigner()} only when one was {@linkplain #evidenceSigner(EvidenceSigner) set} and the
 * manifest declares {@code serve} and names a cookie - as the node hands one out.</li>
 * </ul>
 */
public class TestPluginContext implements PluginContext, AutoCloseable {

	/** The version of the core this testkit was built with - what {@link #coreVersion()} answers by default. */
	public static final String CORE_VERSION = readVersion();

	private static final AtomicInteger THREADS = new AtomicInteger();

	private final PluginManifest manifest;
	private final File home;
	private final boolean ownsHome;
	private final byte[] clientKeySecret = new byte[32];
	private final ThreadLocal<Mac> hmac;

	private volatile String nodeId = "fc-test-1";
	private volatile String publicDomain = "www.example.com";
	private volatile String coreVersion = CORE_VERSION;
	private volatile EvidenceSigner evidenceSigner;
	private final Set<String> allowedRedirectHosts = new LinkedHashSet<>();

	private ScheduledThreadPoolExecutor executor;
	private volatile PluginScheduler scheduler;

	/** A context with a fresh temporary FRONTCACHE_HOME, removed by {@link #delete()} / {@link #close()}. */
	public TestPluginContext(PluginManifest manifest) {
		this(manifest, createHome(manifest), true);
	}

	/** A context over an existing FRONTCACHE_HOME, which {@link #delete()} leaves in place. */
	public TestPluginContext(PluginManifest manifest, File home) {
		this(manifest, home, false);
	}

	private TestPluginContext(PluginManifest manifest, File home, boolean ownsHome) {
		this.manifest = manifest;
		this.home = home;
		this.ownsHome = ownsHome;
		new File(home, "conf").mkdirs();
		new File(home, "plugins/" + manifest.id() + "/data").mkdirs();
		new SecureRandom().nextBytes(clientKeySecret);
		this.hmac = ThreadLocal.withInitial(() -> {
			try {
				Mac mac = Mac.getInstance("HmacSHA256");
				mac.init(new SecretKeySpec(clientKeySecret, "HmacSHA256"));
				return mac;
			} catch (GeneralSecurityException e) {
				throw new IllegalStateException("HmacSHA256 is not available", e);
			}
		});
	}

	private static File createHome(PluginManifest manifest) {
		try {
			return Files.createTempDirectory("frontcache-home-" + manifest.id() + "-").toFile();
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	// ---- PluginContext ----------------------------------------------------------------------------------------

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
		return nodeId;
	}

	@Override
	public String coreVersion() {
		return coreVersion;
	}

	@Override
	public File frontcacheHome() {
		return home;
	}

	@Override
	public File dataDirectory() {
		File dir = new File(home, "plugins/" + manifest.id() + "/data");
		return (dir.isDirectory() || dir.mkdirs()) ? dir : null;
	}

	@Override
	public String hashClientKey(String clientKey) {
		if (null == clientKey)
			return null;
		byte[] mac = hmac.get().doFinal(clientKey.getBytes(StandardCharsets.UTF_8));
		return HexFormat.of().formatHex(mac, 0, 16);
	}

	@Override
	public EvidenceSigner evidenceSigner() {
		if (!manifest.declares(PluginKind.SERVE) || manifest.cookies().isEmpty())
			return null;
		return evidenceSigner;
	}

	/**
	 * The current start's scheduler ({@link PluginHarness} makes a new one before every start, {@link #newScheduler()});
	 * a stopped start's view refuses new tasks.
	 */
	@Override
	public PluginScheduler scheduler() {
		PluginScheduler current = scheduler;
		return (null == current) ? newScheduler() : current;
	}

	/**
	 * {@code conf/lists/<name>.txt}, replaced atomically, with the node's rules - see {@link ManagedLists}.
	 *
	 * @throws UnsupportedOperationException for a plugin that does not declare {@code manage}
	 */
	@Override
	public int writeList(String name, List<String> lines) throws IOException {
		if (!manifest.declares(PluginKind.MANAGE))
			throw new UnsupportedOperationException("writeList is offered to a plugin declaring kind 'manage' - '"
					+ manifest.id() + "' declares " + manifest.kindList());
		return ManagedLists.write(home, manifest.id(), name, lines);
	}

	// ---- the node's side --------------------------------------------------------------------------------------

	/** A new scheduler view for the next start; the previous one's tasks are cancelled. */
	public synchronized PluginScheduler newScheduler() {
		if (null != scheduler)
			scheduler.cancelAll();
		if (null == executor)
		{
			executor = new ScheduledThreadPoolExecutor(2, r -> {
				Thread t = new Thread(r, "fc-plugin-" + manifest.id() + "-" + THREADS.incrementAndGet());
				t.setDaemon(true);
				return t;
			});
			executor.setRemoveOnCancelPolicy(true);
		}
		scheduler = new PluginScheduler(executor, manifest.id());
		return scheduler;
	}

	/** What the node does when the plugin stops: the current view's tasks are cancelled. */
	public synchronized void cancelScheduledTasks() {
		if (null != scheduler)
			scheduler.cancelAll();
	}

	public TestPluginContext nodeId(String nodeId) {
		this.nodeId = nodeId;
		return this;
	}

	public TestPluginContext publicDomain(String publicDomain) {
		this.publicDomain = publicDomain;
		return this;
	}

	public TestPluginContext coreVersion(String coreVersion) {
		this.coreVersion = coreVersion;
		return this;
	}

	/** The signer {@link #evidenceSigner()} hands a serving plugin that names a cookie, e.g. {@link RecordingEvidenceSigner}. */
	public TestPluginContext evidenceSigner(EvidenceSigner signer) {
		this.evidenceSigner = signer;
		return this;
	}

	/** {@code front-cache.guard-rules.allowed-redirect-hosts}: hosts besides the public domain a redirect may name. */
	public TestPluginContext allowRedirectHost(String host) {
		allowedRedirectHosts.add(host.toLowerCase(java.util.Locale.ROOT));
		return this;
	}

	public Set<String> allowedRedirectHosts() {
		return Set.copyOf(allowedRedirectHosts);
	}

	/** {@code conf/lists/<name>.txt} in this context's home - whether or not it exists. */
	public File listFile(String name) {
		return ManagedLists.file(home, name);
	}

	/** The lines of {@code conf/lists/<name>.txt}, the marker first; empty when the file does not exist. */
	public List<String> readList(String name) throws IOException {
		File file = listFile(name);
		return file.isFile() ? Files.readAllLines(file.toPath(), StandardCharsets.UTF_8) : List.of();
	}

	/** Writes {@code conf/<file>} in this context's home - e.g. an operator's list, to check one is never overwritten. */
	public File writeConfFile(String relativePath, String content) throws IOException {
		File file = new File(new File(home, "conf"), relativePath);
		file.getParentFile().mkdirs();
		Files.writeString(file.toPath(), content, StandardCharsets.UTF_8);
		return file;
	}

	/** Cancels every task, stops the threads and - for a home this context created - deletes it. */
	public synchronized void delete() {
		cancelScheduledTasks();
		if (null != executor)
		{
			executor.shutdownNow();
			executor = null;
		}
		if (ownsHome)
			deleteTree(home.toPath());
	}

	@Override
	public void close() {
		delete();
	}

	private static void deleteTree(Path root) {
		if (!Files.exists(root))
			return;
		try {
			Files.walkFileTree(root, new SimpleFileVisitor<>() {
				@Override
				public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
					Files.deleteIfExists(file);
					return FileVisitResult.CONTINUE;
				}

				@Override
				public FileVisitResult postVisitDirectory(Path dir, IOException e) throws IOException {
					Files.deleteIfExists(dir);
					return FileVisitResult.CONTINUE;
				}
			});
		} catch (IOException e) {
			// a temporary directory left behind is not a test failure
		}
	}

	private static String readVersion() {
		try (InputStream in = TestPluginContext.class.getResourceAsStream("testkit.properties")) {
			if (null == in)
				return "unknown";
			Properties p = new Properties();
			p.load(in);
			return p.getProperty("version", "unknown").trim();
		} catch (IOException e) {
			return "unknown";
		}
	}
}
