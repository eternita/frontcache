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
package org.frontcache.examples.crawlerfeeder;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

import org.frontcache.io.ActionResponse;
import org.frontcache.plugin.FrontcachePlugin;
import org.frontcache.plugin.ManagementAction;
import org.frontcache.plugin.ManagementRequest;
import org.frontcache.plugin.PluginConfigException;
import org.frontcache.plugin.PluginContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.typesafe.config.Config;

/**
 * Verified crawler feeder: keeps {@code conf/lists/googlebot.txt} and {@code conf/lists/bingbot.txt} current from the
 * vendors' published address ranges, so a bots.conf or guard rule can tell the real crawler from a scraper using its
 * name:
 *
 * <pre>
 * # bots.conf
 * fake-googlebot | ua~(?i)googlebot ; !client-ip:@lists/googlebot.txt | bot
 * </pre>
 *
 * It decides nothing: it fetches on {@code PluginContext.scheduler()} and writes through
 * {@code PluginContext.writeList} - the core's {@code client-ip:} rule decides. A fetch that fails, or answers anything
 * but a list of prefixes, keeps the list as it was (an empty list is never written). Each feed's last success is kept
 * in the plugin's data directory, so a restart does not fetch again before the feed is due.
 *
 * <p>Management actions: {@code crawler-feeder-status}, and {@code crawler-feeder-refresh[&feed=<name>]} (write-gated)
 * to fetch now.
 */
public final class CrawlerFeederPlugin implements FrontcachePlugin {

	static final String STATUS = "crawler-feeder-status";
	static final String REFRESH = "crawler-feeder-refresh";

	private static final Logger LOG = LoggerFactory.getLogger(CrawlerFeederPlugin.class);

	/** What a feed did - kept across reloads and config changes, by name. */
	static final class FeedState {
		final ReentrantLock running = new ReentrantLock();
		volatile String url;
		volatile Instant lastAttempt;
		volatile Instant lastSuccess;
		volatile int blocks = -1;
		volatile String lastError;
		volatile int consecutiveFailures;
	}

	private volatile FeederConfig config = FeederConfig.defaults();
	private final Map<String, FeedState> states = new ConcurrentHashMap<>();

	// guarded by this
	private PluginContext context;
	private ScheduledExecutorService scheduler;
	private final Map<String, ScheduledFuture<?>> scheduled = new HashMap<>();
	private boolean started;
	private volatile FeedFetcher fetcher;

	public CrawlerFeederPlugin() {
		// cheap and side-effect free: the node may construct a plugin before any engine exists
	}

	// ---- config ------------------------------------------------------------------------------------------------

	@Override
	public void validateConfig(Config config) throws PluginConfigException {
		FeederConfig.parse(config);
	}

	@Override
	public void configure(Config config) throws PluginConfigException {
		FeederConfig parsed = FeederConfig.parse(config);   // throws before anything is applied
		synchronized (this) {
			this.config = parsed;
			if (started)
				schedule();   // reload-plugin-config while running: the feeds may have changed
		}
	}

	// ---- life cycle --------------------------------------------------------------------------------------------

	@Override
	public synchronized void start(PluginContext context) {
		this.context = context;
		this.scheduler = context.scheduler();
		this.started = true;
		loadState();
		schedule();
	}

	@Override
	public synchronized void stop(boolean shutdown) {
		started = false;
		cancel();
		FeedFetcher f = fetcher;
		fetcher = null;
		if (null != f)
			f.close();
	}

	/** (Re)schedules every configured feed - first when it is due, then every {@code every}. */
	private void schedule() {
		cancel();
		FeedFetcher old = fetcher;
		fetcher = new FeedFetcher(config.timeout(), "frontcache-crawler-feeder/" + context.manifest().version());
		if (null != old)
			old.close();
		if (null == scheduler)
		{
			LOG.warn("crawler-feeder: this node offers plugins no scheduler (before 2.13.0) - the lists are not kept");
			return;
		}
		states.keySet().retainAll(config.feeds().stream().map(FeederConfig.Feed::name).toList());
		for (FeederConfig.Feed feed : config.feeds())
		{
			FeedState state = states.computeIfAbsent(feed.name(), n -> new FeedState());
			long delay = dueIn(feed, state).toMillis();
			scheduled.put(feed.name(), scheduler.scheduleWithFixedDelay(() -> run(feed), delay,
					feed.every().toMillis(), TimeUnit.MILLISECONDS));
		}
	}

	private void cancel() {
		for (ScheduledFuture<?> task : scheduled.values())
			task.cancel(false);
		scheduled.clear();
	}

	/** Zero for a feed never fetched (or fetched from another url); else what is left of {@code every}. */
	private static Duration dueIn(FeederConfig.Feed feed, FeedState state) {
		Instant success = state.lastSuccess;
		if (null == success || !feed.url().toString().equals(state.url))
			return Duration.ZERO;
		Duration left = Duration.between(Instant.now(), success.plus(feed.every()));
		return left.isNegative() ? Duration.ZERO : left;
	}

	// ---- one fetch -----------------------------------------------------------------------------------------------

	/** Fetches one feed and replaces its list - or keeps the list and says why. One run per feed at a time. */
	void run(FeederConfig.Feed feed) {
		FeedState state = states.get(feed.name());
		FeedFetcher f = fetcher;
		PluginContext c = context;
		if (null == state || null == f || null == c || !state.running.tryLock())
			return;
		try {
			state.lastAttempt = Instant.now();
			List<String> lines = f.fetch(feed);
			int blocks = c.writeList(feed.name(), lines);
			state.url = feed.url().toString();
			state.lastSuccess = Instant.now();
			state.blocks = blocks;
			state.lastError = null;
			state.consecutiveFailures = 0;
			saveState();
			LOG.info("crawler-feeder: lists/{}.txt now holds {} address blocks from {}", feed.name(), blocks, feed.url());
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			failed(feed, state, "interrupted");
		} catch (IOException | RuntimeException e) {
			// RuntimeException: writeList's IllegalArgumentException (an entry that is not an address - the list is
			// refused whole) or UnsupportedOperationException (a node without writeList)
			String why = (null == e.getMessage()) ? e.getClass().getSimpleName() : e.getMessage();
			failed(feed, state, why);
		} finally {
			state.running.unlock();
		}
	}

	/** Whether a fetch of the feed is under way - for the tests. */
	boolean isRunning(String feed) {
		FeedState state = states.get(feed);
		return null != state && state.running.isLocked();
	}

	private static void failed(FeederConfig.Feed feed, FeedState state, String why) {
		state.lastError = why;
		state.consecutiveFailures++;
		LOG.warn("crawler-feeder: {} not refreshed from {} - lists/{}.txt keeps what it had: {}", feed.name(),
				feed.url(), feed.name(), why);
	}

	// ---- the state file: plugins/crawler-feeder/data/state.properties ------------------------------------------

	private File stateFile() {
		File dir = (null == context) ? null : context.dataDirectory();
		return (null == dir) ? null : new File(dir, "state.properties");
	}

	private void loadState() {
		File file = stateFile();
		if (null == file || !file.isFile())
			return;
		Properties p = new Properties();
		try (InputStream in = Files.newInputStream(file.toPath())) {
			p.load(in);
		} catch (IOException e) {
			LOG.warn("crawler-feeder: cannot read {} - every feed is fetched now", file);
			return;
		}
		for (FeederConfig.Feed feed : config.feeds())
		{
			String success = p.getProperty(feed.name() + ".success");
			if (null == success)
				continue;
			FeedState state = states.computeIfAbsent(feed.name(), n -> new FeedState());
			try {
				state.lastSuccess = Instant.parse(success);
				state.url = p.getProperty(feed.name() + ".url");
				state.blocks = Integer.parseInt(p.getProperty(feed.name() + ".blocks", "-1"));
			} catch (RuntimeException e) {
				state.lastSuccess = null;
			}
		}
	}

	private synchronized void saveState() {
		File file = stateFile();
		if (null == file)
			return;
		Properties p = new Properties();
		states.forEach((name, state) -> {
			if (null != state.lastSuccess)
			{
				p.setProperty(name + ".success", state.lastSuccess.toString());
				p.setProperty(name + ".url", String.valueOf(state.url));
				p.setProperty(name + ".blocks", String.valueOf(state.blocks));
			}
		});
		try {
			Path temp = Files.createTempFile(file.getParentFile().toPath(), ".state", ".tmp");
			try (OutputStream out = Files.newOutputStream(temp)) {
				p.store(out, "crawler-feeder: each feed's last success");
			}
			try {
				Files.move(temp, file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
			} catch (AtomicMoveNotSupportedException e) {
				Files.move(temp, file.toPath(), StandardCopyOption.REPLACE_EXISTING);
			}
		} catch (IOException e) {
			LOG.warn("crawler-feeder: cannot write {} - a restart fetches every feed again", file);
		}
	}

	// ---- management ----------------------------------------------------------------------------------------------

	@Override
	public Map<String, ManagementAction> managementActions() {
		return Map.of(STATUS, new ManagementAction() {
			@Override
			public String description() {
				return "each crawler feed: its list, last attempt and success, address blocks, last error, next run";
			}

			@Override
			public ActionResponse handle(ManagementRequest request) {
				return status(STATUS);
			}
		}, REFRESH, new ManagementAction() {
			@Override
			public String description() {
				return "fetch every crawler feed now, or the one named by feed=<name> (write-gated)";
			}

			@Override
			public ActionResponse handle(ManagementRequest request) {
				return refresh(request);
			}
		});
	}

	private ActionResponse refresh(ManagementRequest request) {
		if (!request.isAuthorizedToWrite())
			return error(REFRESH, "crawler-feeder-refresh rewrites conf/lists - it needs the write gate");
		String only = request.parameter("feed");
		ScheduledExecutorService s;
		synchronized (this) {
			s = scheduler;
		}
		if (null == s || !started)
			return error(REFRESH, "crawler-feeder is not running");
		boolean any = false;
		for (FeederConfig.Feed feed : config.feeds())
			if (null == only || only.equals(feed.name()))
			{
				s.execute(() -> run(feed));
				any = true;
			}
		if (!any)
			return error(REFRESH, "no feed is named '" + only + "'");
		return status(REFRESH);
	}

	FeederStatusResponse status(String action) {
		FeederStatusResponse response = new FeederStatusResponse();
		response.setAction(action);
		response.setResponseStatus(ActionResponse.RESPONSE_STATUS_OK);
		for (FeederConfig.Feed feed : config.feeds())
		{
			FeedState state = states.get(feed.name());
			FeederStatusResponse.FeedStatus f = new FeederStatusResponse.FeedStatus();
			f.setName(feed.name());
			f.setUrl(feed.url().toString());
			f.setEvery(feed.every().toString());
			f.setList("lists/" + feed.name() + ".txt");
			if (null != state)
			{
				f.setLastAttempt(iso(state.lastAttempt));
				f.setLastSuccess(iso(state.lastSuccess));
				f.setBlocks(state.blocks);
				f.setLastError(state.lastError);
				f.setConsecutiveFailures(state.consecutiveFailures);
			}
			ScheduledFuture<?> next;
			synchronized (this) {
				next = scheduled.get(feed.name());
			}
			if (null != next && !next.isDone())
				f.setNextRunSeconds(Math.max(0, next.getDelay(TimeUnit.SECONDS)));
			response.getFeeds().add(f);
		}
		return response;
	}

	private static ActionResponse error(String action, String why) {
		ActionResponse response = new FeederStatusResponse();
		response.setAction(action);
		response.setResponseStatus(ActionResponse.RESPONSE_STATUS_ERROR);
		response.setErrorDescription(why);
		return response;
	}

	private static String iso(Instant instant) {
		return (null == instant) ? null : instant.toString();
	}
}
