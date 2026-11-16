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
import java.net.Inet6Address;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;

import org.frontcache.io.ActionResponse;
import org.frontcache.plugin.FrontcachePlugin;
import org.frontcache.plugin.ManagementAction;
import org.frontcache.plugin.ManagementRequest;
import org.frontcache.plugin.PluginConfigException;
import org.frontcache.plugin.PluginContext;
import org.frontcache.plugin.event.EventListener;
import org.frontcache.plugin.event.FcEvent;
import org.frontcache.plugin.event.GuardMatched;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.typesafe.config.Config;

/**
 * Verified crawler feeder: keeps {@code conf/lists/googlebot.txt}, {@code bingbot.txt} and {@code applebot.txt}
 * current, so a bots.conf or guard rule can tell the real crawler from a scraper using its name:
 *
 * <pre>
 * # bots.conf
 * googlebot-verified | ua~(?i)googlebot ; client-ip:@lists/googlebot.txt | bot-whitelisted
 * # guard-rules.conf - a claimant from outside the list, reported to the feeder (and only logged)
 * googlebot-claim | ua~(?i)googlebot ; !client-ip:@lists/googlebot.txt | reject:403 | dry-run
 * </pre>
 *
 * A list holds two things: the vendor's published ranges, fetched on {@code PluginContext.scheduler()} (a fetch that
 * fails, or answers anything but a list of prefixes, keeps the list as it was; each feed's last success is kept in the
 * plugin's data directory, so a restart does not fetch again before the feed is due), and <b>claimants confirmed by
 * reverse DNS</b>: a {@link GuardMatched} of a rule the feed names ({@code googlebot-claim} by default) reports an
 * address that says it is the crawler and is not on its list; on a thread of its own, off the request path, the
 * plugin looks up the address's name, checks it is under the crawler's domains, and that the name resolves back to the
 * address. A confirmed address is added to the list for {@code keep} (checked again from half-way), an address not
 * confirmed is not looked up again for {@code retry}.
 *
 * <p>It decides nothing: it writes through {@code PluginContext.writeList}, and the core's {@code client-ip:} rule
 * decides. A claimant's first requests are decided as an unlisted address's are - the check takes a few lookups.
 *
 * <p>Management actions: {@code crawler-feeder-status}, {@code crawler-feeder-refresh[&feed=<name>]} (write-gated) to
 * fetch now, and {@code crawler-feeder-verify&address=<ip>[&feed=<name>]} (write-gated) to check an address now.
 */
public final class CrawlerFeederPlugin implements FrontcachePlugin {

	static final String STATUS = "crawler-feeder-status";
	static final String REFRESH = "crawler-feeder-refresh";
	static final String VERIFY = "crawler-feeder-verify";

	/** Claimants waiting for their lookups; more are dropped (and counted) until the queue drains. */
	static final int MAX_PENDING = 1_000;
	/** Addresses not confirmed, remembered for {@code retry} - the oldest forgotten first. */
	static final int MAX_REFUSED = 10_000;
	/** The confirmed addresses status shows per feed, newest first. */
	static final int STATUS_CONFIRMED = 20;
	static final Duration SWEEP = Duration.ofMinutes(1);

	private static final Logger LOG = LoggerFactory.getLogger(CrawlerFeederPlugin.class);

	/** An address confirmed by reverse DNS: when, under which name, and when it was last looked at. */
	record Verified(String address, String host, Instant confirmed, Instant checked) {
	}

	/** What a feed did - kept across reloads and config changes, by name. */
	static final class FeedState {
		final ReentrantLock running = new ReentrantLock();
		volatile String url;
		volatile Instant lastAttempt;
		volatile Instant lastSuccess;
		volatile int blocks = -1;
		volatile String lastError;
		volatile int consecutiveFailures;
		/** The fetched part of the list - two comments and the prefixes; null until fetched (or read back). */
		volatile List<String> base;
		/** The confirmed claimants, oldest confirmation first. Guarded by this state. */
		final LinkedHashMap<String, Verified> verified = new LinkedHashMap<>();
		/** Why the last write for a confirmation (or an expiry) kept the list as it was; null after a write. */
		volatile String writeError;
	}

	private final Dns dns;
	private final Clock clock;

	private volatile FeederConfig config = FeederConfig.defaults();
	/** guard rule name -> the feeds it reports claimants to */
	private volatile Map<String, List<String>> ruleFeeds = ruleFeeds(config);
	private final Map<String, FeedState> states = new ConcurrentHashMap<>();

	private final Set<String> pending = ConcurrentHashMap.newKeySet();
	/** "feed address" -> epoch millis before which it is not looked up again; guarded by itself */
	private final LinkedHashMap<String, Long> refused = new LinkedHashMap<>(16, 0.75f, false) {
		@Override
		protected boolean removeEldestEntry(Map.Entry<String, Long> eldest) {
			return size() > MAX_REFUSED;
		}
	};
	private final AtomicLong claims = new AtomicLong();
	private final AtomicLong lookups = new AtomicLong();
	private final AtomicLong confirmations = new AtomicLong();
	private final AtomicLong refutations = new AtomicLong();
	private final AtomicLong unknowns = new AtomicLong();
	private final AtomicLong dropped = new AtomicLong();
	private final AtomicLong ignored = new AtomicLong();

	private final EventListener listener = new EventListener() {
		@Override
		public Set<Class<? extends FcEvent>> subscribes() {
			return Set.of(GuardMatched.class);
		}

		@Override
		public void onEvents(List<FcEvent> batch) {
			claimed(batch);
		}
	};

	// guarded by this
	private volatile PluginContext context;   // read by the verifier's thread too
	private ScheduledExecutorService scheduler;
	private final Map<String, ScheduledFuture<?>> scheduled = new HashMap<>();
	private ScheduledFuture<?> sweep;
	private boolean started;
	private volatile FeedFetcher fetcher;
	private volatile ExecutorService verifier;

	private final Object files = new Object();
	/** Feeds whose confirmations, read back at start, were not all kept - their lists are rewritten. */
	private final Set<String> stale = ConcurrentHashMap.newKeySet();

	public CrawlerFeederPlugin() {
		// cheap and side-effect free: the node may construct a plugin before any engine exists
		this(Dns.SYSTEM, Clock.systemUTC());
	}

	/** For the tests: a resolver and a clock of their own. */
	CrawlerFeederPlugin(Dns dns, Clock clock) {
		this.dns = dns;
		this.clock = clock;
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
			this.ruleFeeds = ruleFeeds(parsed);
			synchronized (refused) {
				refused.clear();   // the domains may have changed
			}
			if (started)
				schedule();   // reload-plugin-config while running: the feeds may have changed
		}
	}

	private static Map<String, List<String>> ruleFeeds(FeederConfig config) {
		Map<String, List<String>> map = new HashMap<>();
		for (FeederConfig.Feed feed : config.feeds())
			for (String rule : feed.rules())
				map.computeIfAbsent(rule, r -> new ArrayList<>()).add(feed.name());
		map.replaceAll((rule, feeds) -> List.copyOf(feeds));
		return Map.copyOf(map);
	}

	// ---- life cycle --------------------------------------------------------------------------------------------

	@Override
	public synchronized void start(PluginContext context) {
		this.context = context;
		this.scheduler = context.scheduler();
		this.started = true;
		this.verifier = Executors.newSingleThreadExecutor(r -> {
			Thread t = new Thread(r, "crawler-feeder-verify");
			t.setDaemon(true);
			return t;
		});
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
		ExecutorService v = verifier;
		verifier = null;
		if (null != v)
			v.shutdownNow();
		pending.clear();
	}

	/** Only while some feed checks claimants: no rule names a feed, no event is built for this plugin. */
	@Override
	public List<EventListener> eventListeners() {
		return config.verifies() ? List.of(listener) : List.of();
	}

	/**
	 * (Re)schedules every configured feed - first when it is due, then every {@code every} - and the sweep of
	 * confirmed claimants; brings each feed's confirmed claimants in line with its domains.
	 */
	private void schedule() {
		cancel();
		FeedFetcher old = fetcher;
		fetcher = new FeedFetcher(config.timeout(), "frontcache-crawler-feeder/" + context.manifest().version());
		if (null != old)
			old.close();
		states.keySet().retainAll(config.feeds().stream().map(FeederConfig.Feed::name).toList());
		for (FeederConfig.Feed feed : config.feeds())
		{
			FeedState state = states.computeIfAbsent(feed.name(), n -> new FeedState());
			if (reconcile(feed, state) | stale.remove(feed.name()) || (!feed.fetches() && !listFileExists(feed)))
				writeList(feed, state);
		}
		saveVerified();
		if (null == scheduler)
		{
			LOG.warn("crawler-feeder: this node offers plugins no scheduler (before 2.13.0) - the lists are not kept");
			return;
		}
		for (FeederConfig.Feed feed : config.feeds())
		{
			if (!feed.fetches())
				continue;
			FeedState state = states.get(feed.name());
			long delay = dueIn(feed, state).toMillis();
			scheduled.put(feed.name(), scheduler.scheduleWithFixedDelay(() -> run(feed), delay,
					feed.every().toMillis(), TimeUnit.MILLISECONDS));
		}
		if (config.verifies())
			sweep = scheduler.scheduleWithFixedDelay(this::sweep, 0, SWEEP.toMillis(), TimeUnit.MILLISECONDS);
	}

	/** A feed without a url writes its list at start, so a rule reading it finds a file from the first request. */
	private boolean listFileExists(FeederConfig.Feed feed) {
		File home = context.frontcacheHome();
		return null == home || new File(home, "conf/lists/" + feed.name() + ".txt").isFile();
	}

	private void cancel() {
		for (ScheduledFuture<?> task : scheduled.values())
			task.cancel(false);
		scheduled.clear();
		if (null != sweep)
			sweep.cancel(false);
		sweep = null;
	}

	/** Zero for a feed never fetched (or fetched from another url, or whose prefixes are lost); else what is left of {@code every}. */
	private static Duration dueIn(FeederConfig.Feed feed, FeedState state) {
		Instant success = state.lastSuccess;
		if (null == success || !feed.url().toString().equals(state.url) || null == state.base)
			return Duration.ZERO;
		Duration left = Duration.between(Instant.now(), success.plus(feed.every()));
		return left.isNegative() ? Duration.ZERO : left;
	}

	/**
	 * Drops the confirmed claimants the feed no longer vouches for: all of them when it checks none, those whose name
	 * is not under its domains now, and the oldest beyond {@code max-verified}.
	 *
	 * @return whether any was dropped
	 */
	private boolean reconcile(FeederConfig.Feed feed, FeedState state) {
		synchronized (state) {
			int before = state.verified.size();
			if (!feed.verifies())
				state.verified.clear();
			else
				state.verified.values().removeIf(v -> !ClaimCheck.underOneOf(v.host(), feed.domains()));
			evict(state);
			return state.verified.size() != before;
		}
	}

	private void evict(FeedState state) {
		Iterator<String> oldest = state.verified.keySet().iterator();
		for (int over = state.verified.size() - config.maxVerified(); over > 0 && oldest.hasNext(); over--)
		{
			oldest.next();
			oldest.remove();
		}
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
			int blocks;
			synchronized (state) {
				blocks = c.writeList(feed.name(), compose(feed, lines, state));
				state.base = lines;   // only once written: a refused base would refuse every later write
				state.blocks = blocks;
				state.writeError = null;
			}
			state.url = feed.url().toString();
			state.lastSuccess = Instant.now();
			state.lastError = null;
			state.consecutiveFailures = 0;
			saveBase(feed, lines);
			saveState();
			LOG.info("crawler-feeder: lists/{}.txt now holds {} address blocks from {}", feed.name(), blocks, feed.url());
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			failed(feed, state, "interrupted");
		} catch (IOException | RuntimeException e) {
			// RuntimeException: writeList's IllegalArgumentException (an entry that is not an address - the list is
			// refused whole) or UnsupportedOperationException (a node without writeList)
			failed(feed, state, why(e));
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

	private static String why(Exception e) {
		return (null == e.getMessage()) ? e.getClass().getSimpleName() : e.getMessage();
	}

	// ---- the list: the fetched prefixes, then the confirmed claimants ------------------------------------------------

	/** Call holding the state's monitor. */
	private List<String> compose(FeederConfig.Feed feed, List<String> base, FeedState state) {
		List<String> lines = new ArrayList<>();
		if (feed.fetches())
			lines.addAll(base);
		else
			lines.add("# " + feed.name() + ": no published ranges - addresses confirmed by reverse DNS only");
		if (feed.verifies())
		{
			lines.add("# " + state.verified.size() + " claimants confirmed by reverse DNS under "
					+ String.join(", ", feed.domains()));
			for (Verified v : state.verified.values())
			{
				lines.add("# " + v.host() + " - confirmed " + v.confirmed());
				lines.add(v.address());
			}
		}
		return lines;
	}

	/**
	 * Writes the list after its confirmed claimants changed. Not before a fetching feed's prefixes are known - its
	 * first fetch writes the list, confirmations included; a list of claimants alone would drop the vendor's ranges.
	 */
	private void writeList(FeederConfig.Feed feed, FeedState state) {
		PluginContext c = context;
		if (null == c)
			return;
		synchronized (state) {
			List<String> base = state.base;
			if (feed.fetches() && null == base)
				return;
			try {
				state.blocks = c.writeList(feed.name(), compose(feed, base, state));
				state.writeError = null;
			} catch (IOException | RuntimeException e) {
				String why = why(e);
				if (!why.equals(state.writeError))
					LOG.warn("crawler-feeder: lists/{}.txt keeps what it had: {}", feed.name(), why);
				state.writeError = why;
			}
		}
	}

	// ---- claimants -----------------------------------------------------------------------------------------------

	/** On the listener's own thread: queues a check of each address a claim rule reported. Never blocks. */
	private void claimed(List<FcEvent> batch) {
		Map<String, List<String>> rules = ruleFeeds;
		FeederConfig cfg = config;
		for (FcEvent event : batch)
		{
			if (!(event instanceof GuardMatched matched) || null == matched.rule())
				continue;
			List<String> feeds = rules.get(matched.rule());
			if (null == feeds)
				continue;
			claims.incrementAndGet();
			InetAddress address = literal(matched.clientKey());
			if (null == address || !isPublic(address))
			{
				ignored.incrementAndGet();
				continue;
			}
			for (String name : feeds)
			{
				FeederConfig.Feed feed = cfg.feed(name);
				if (null != feed && feed.verifies())
					queue(feed, address, false);
			}
		}
	}

	/** Queues a lookup - unless the address is listed already, was not confirmed lately, or is queued. */
	private void queue(FeederConfig.Feed feed, InetAddress address, boolean recheck) {
		String literal = address.getHostAddress();
		String key = feed.name() + " " + literal;
		FeedState state = states.get(feed.name());
		if (null == state)
			return;
		if (!recheck)
		{
			synchronized (state) {
				if (state.verified.containsKey(literal))
					return;   // a claim rule that does not read the list keeps reporting it
			}
			synchronized (refused) {
				Long until = refused.get(key);
				if (null != until && until > clock.millis())
					return;
			}
		}
		ExecutorService v = verifier;
		if (null == v || pending.contains(key))
			return;
		if (pending.size() >= MAX_PENDING)
		{
			dropped.incrementAndGet();
			return;
		}
		if (!pending.add(key))
			return;
		try {
			v.execute(() -> {
				try {
					FeederConfig.Feed now = config.feed(feed.name());
					if (null != now && now.verifies())
						check(now, address, recheck);
				} finally {
					pending.remove(key);
				}
			});
		} catch (RejectedExecutionException e) {
			pending.remove(key);
		}
	}

	/** Looks the address up and records the verdict - on the verifier's thread, or a management thread. */
	private ClaimCheck.Verdict check(FeederConfig.Feed feed, InetAddress address, boolean recheck) {
		lookups.incrementAndGet();
		ClaimCheck.Verdict verdict = ClaimCheck.check(dns, address, feed.domains());
		record(feed, address, verdict, recheck);
		return verdict;
	}

	private void record(FeederConfig.Feed feed, InetAddress address, ClaimCheck.Verdict verdict, boolean recheck) {
		FeedState state = states.get(feed.name());
		if (null == state)
			return;
		String literal = address.getHostAddress();
		String key = feed.name() + " " + literal;
		Instant now = clock.instant();
		boolean changed = false;   // the list is rewritten
		boolean save = false;      // verified.txt is
		switch (verdict.outcome()) {
			case CONFIRMED -> {
				confirmations.incrementAndGet();
				synchronized (refused) {
					refused.remove(key);
				}
				boolean added;
				synchronized (state) {
					added = null == state.verified.remove(literal);
					state.verified.put(literal, new Verified(literal, verdict.host(), now, now));   // the newest, last
					evict(state);
				}
				changed = true;   // a new confirmation time, in the list's comment too
				if (added)
					LOG.info("crawler-feeder: {} is {} - on lists/{}.txt until {}", literal, verdict.host(), feed.name(),
							now.plus(config.keep()));
			}
			case REFUTED -> {
				refutations.incrementAndGet();
				refuse(key, now);
				synchronized (state) {
					changed = null != state.verified.remove(literal);
				}
				if (changed)
					LOG.info("crawler-feeder: {} is no longer confirmed ({}) - off lists/{}.txt", literal,
							verdict.reason(), feed.name());
				else if (LOG.isDebugEnabled())
					LOG.debug("crawler-feeder: a claimant of {} ({}) is not confirmed: {}", feed.name(),
							context.hashClientKey(literal), verdict.reason());
			}
			case UNKNOWN -> {
				unknowns.incrementAndGet();
				if (recheck)
				{
					// no answer is no reason to drop it: it stays until its confirmation runs out
					synchronized (state) {
						Verified v = state.verified.get(literal);
						if (null != v)
							state.verified.put(literal, new Verified(v.address(), v.host(), v.confirmed(), now));
						save = null != v;
					}
				}
				else
					refuse(key, now);
			}
		}
		if (changed)
			writeList(feed, state);
		if (changed || save)
			saveVerified();
	}

	private void refuse(String key, Instant now) {
		synchronized (refused) {
			refused.remove(key);   // re-inserted: the oldest is forgotten first
			refused.put(key, now.plus(config.retry()).toEpochMilli());
		}
	}

	/** Every minute: confirmations that ran out leave the lists, those past half-way are checked again. */
	void sweep() {
		FeederConfig cfg = config;
		Instant now = clock.instant();
		Duration keep = cfg.keep();
		Duration recheckAfter = keep.dividedBy(2);
		boolean saved = false;
		for (FeederConfig.Feed feed : cfg.feeds())
		{
			FeedState state = states.get(feed.name());
			if (null == state || !feed.verifies())
				continue;
			List<Verified> again = new ArrayList<>();
			boolean expired;
			synchronized (state) {
				int before = state.verified.size();
				state.verified.values().removeIf(v -> !v.confirmed().plus(keep).isAfter(now));
				expired = state.verified.size() != before;
				for (Verified v : state.verified.values())
				{
					boolean halfway = !v.confirmed().plus(recheckAfter).isAfter(now);
					// not looked at since it was confirmed, or not for retry: an unknown answer is asked again later
					boolean due = v.checked().equals(v.confirmed()) || !v.checked().plus(cfg.retry()).isAfter(now);
					if (halfway && due)
						again.add(v);
				}
			}
			if (expired)
			{
				LOG.info("crawler-feeder: confirmations of {} ran out - lists/{}.txt rewritten", feed.name(), feed.name());
				writeList(feed, state);
				saved = true;
			}
			for (Verified v : again)
			{
				InetAddress address = literal(v.address());
				if (null != address)
					queue(feed, address, true);
			}
		}
		if (saved)
			saveVerified();
		synchronized (refused) {
			refused.values().removeIf(until -> until <= now.toEpochMilli());
		}
	}

	/** Whether no lookup is queued or running - for the tests. */
	boolean idle() {
		return pending.isEmpty();
	}

	/** An IPv4 address is written in four parts here - {@code ofLiteral} also takes {@code 66.249.90}, which is 66.249.0.90. */
	private static final java.util.regex.Pattern IPV4 = java.util.regex.Pattern.compile("\\d{1,3}(\\.\\d{1,3}){3}");

	/** An IP literal - never a name, so parsing one never makes a lookup; null when it is not one. */
	static InetAddress literal(String text) {
		if (null == text || text.isBlank())
			return null;
		String t = text.strip();
		if (-1 == t.indexOf(':') && !IPV4.matcher(t).matches())
			return null;
		try {
			InetAddress address = InetAddress.ofLiteral(t);
			return (address instanceof Inet6Address six && 0 != six.getScopeId()) ? null : address;
		} catch (IllegalArgumentException e) {
			return null;
		}
	}

	/**
	 * Not loopback, private, link-local, unique-local or multicast: what a node behind a proxy it does not trust sees
	 * every visitor as - and no crawler's address.
	 */
	static boolean isPublic(InetAddress address) {
		if (address.isLoopbackAddress() || address.isAnyLocalAddress() || address.isLinkLocalAddress()
				|| address.isSiteLocalAddress() || address.isMulticastAddress())
			return false;
		byte[] bytes = address.getAddress();
		if (16 == bytes.length && 0xfc == (bytes[0] & 0xfe))
			return false;   // fc00::/7
		return !(4 == bytes.length && 100 == (bytes[0] & 0xff) && 64 == (bytes[1] & 0xc0));   // 100.64.0.0/10
	}

	// ---- the files in plugins/crawler-feeder/data/: state.properties, <feed>.feed, verified.txt ------------------

	private File dataFile(String name) {
		File dir = (null == context) ? null : context.dataDirectory();
		return (null == dir) ? null : new File(dir, name);
	}

	private void loadState() {
		File file = dataFile("state.properties");
		if (null != file && file.isFile())
		{
			Properties p = new Properties();
			try (InputStream in = Files.newInputStream(file.toPath())) {
				p.load(in);
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
			} catch (IOException e) {
				LOG.warn("crawler-feeder: cannot read {} - every feed is fetched now", file);
			}
		}
		for (FeederConfig.Feed feed : config.feeds())
		{
			FeedState state = states.get(feed.name());
			if (null == state || !feed.fetches() || !feed.url().toString().equals(state.url))
				continue;
			File base = dataFile(feed.name() + ".feed");
			try {
				if (null != base && base.isFile())
					state.base = List.copyOf(Files.readAllLines(base.toPath(), StandardCharsets.UTF_8));
			} catch (IOException e) {
				LOG.warn("crawler-feeder: cannot read {} - {} is fetched now", base, feed.name());
			}
		}
		loadVerified();
	}

	/** {@code <feed> <address> <host> <confirmed> <checked>} per line; what ran out while stopped is dropped. */
	private void loadVerified() {
		File file = dataFile("verified.txt");
		for (FeedState state : states.values())
			synchronized (state) {
				state.verified.clear();
			}
		if (null == file || !file.isFile())
			return;
		List<String> lines;
		try {
			lines = Files.readAllLines(file.toPath(), StandardCharsets.UTF_8);
		} catch (IOException e) {
			LOG.warn("crawler-feeder: cannot read {} - claimants are confirmed afresh", file);
			return;
		}
		Instant now = clock.instant();
		for (String line : lines)
		{
			String[] f = line.strip().split(" ");
			if (5 != f.length)
				continue;
			FeederConfig.Feed feed = config.feed(f[0]);
			InetAddress address = literal(f[1]);
			if (null == feed || !feed.verifies() || null == address || !ClaimCheck.HOST.matcher(f[2]).matches())
			{
				stale.add(f[0]);
				continue;
			}
			try {
				Verified v = new Verified(address.getHostAddress(), f[2], Instant.parse(f[3]), Instant.parse(f[4]));
				if (!v.confirmed().plus(config.keep()).isAfter(now))
				{
					stale.add(feed.name());   // ran out while the plugin was stopped: still on the list file
					continue;
				}
				FeedState state = states.computeIfAbsent(feed.name(), n -> new FeedState());
				synchronized (state) {
					state.verified.put(v.address(), v);
				}
			} catch (RuntimeException e) {
				// a line this version did not write
			}
		}
	}

	private void saveVerified() {
		List<String> lines = new ArrayList<>();
		lines.add("# crawler-feeder: claimants confirmed by reverse DNS - feed address host confirmed checked");
		states.forEach((name, state) -> {
			synchronized (state) {
				for (Verified v : state.verified.values())
					lines.add(name + " " + v.address() + " " + v.host() + " " + v.confirmed() + " " + v.checked());
			}
		});
		write(dataFile("verified.txt"), String.join("\n", lines) + "\n", "a restart confirms claimants afresh");
	}

	private void saveBase(FeederConfig.Feed feed, List<String> lines) {
		write(dataFile(feed.name() + ".feed"), String.join("\n", lines) + "\n", "a restart fetches " + feed.name() + " again");
	}

	private void saveState() {
		Properties p = new Properties();
		states.forEach((name, state) -> {
			if (null != state.lastSuccess)
			{
				p.setProperty(name + ".success", state.lastSuccess.toString());
				p.setProperty(name + ".url", String.valueOf(state.url));
				p.setProperty(name + ".blocks", String.valueOf(state.blocks));
			}
		});
		java.io.StringWriter text = new java.io.StringWriter();
		try {
			p.store(text, "crawler-feeder: each feed's last success");
		} catch (IOException e) {
			throw new IllegalStateException(e);   // a StringWriter does not throw
		}
		write(dataFile("state.properties"), text.toString(), "a restart fetches every feed again");
	}

	private void write(File file, String content, String otherwise) {
		if (null == file)
			return;
		synchronized (files) {
			try {
				Path temp = Files.createTempFile(file.getParentFile().toPath(), ".crawler-feeder", ".tmp");
				Files.writeString(temp, content, StandardCharsets.UTF_8);
				try {
					Files.move(temp, file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
				} catch (AtomicMoveNotSupportedException e) {
					Files.move(temp, file.toPath(), StandardCopyOption.REPLACE_EXISTING);
				}
			} catch (IOException e) {
				LOG.warn("crawler-feeder: cannot write {} - {}", file, otherwise);
			}
		}
	}

	// ---- management ----------------------------------------------------------------------------------------------

	@Override
	public Map<String, ManagementAction> managementActions() {
		return Map.of(STATUS, new ManagementAction() {
			@Override
			public String description() {
				return "each crawler feed: its list, last fetch, address blocks, confirmed claimants, errors, next run";
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
		}, VERIFY, new ManagementAction() {
			@Override
			public String description() {
				return "check address=<ip> by reverse DNS now against every feed with domains, or feed=<name>;"
						+ " a confirmed one is listed (write-gated)";
			}

			@Override
			public ActionResponse handle(ManagementRequest request) {
				return verify(request);
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
			if (feed.fetches() && (null == only || only.equals(feed.name())))
			{
				s.execute(() -> run(feed));
				any = true;
			}
		if (!any)
			return error(REFRESH, (null == only) ? "no feed has a url" : "no feed with a url is named '" + only + "'");
		return status(REFRESH);
	}

	/** On the management thread: the caller waits for the lookups. */
	private ActionResponse verify(ManagementRequest request) {
		if (!request.isAuthorizedToWrite())
			return error(VERIFY, "crawler-feeder-verify may rewrite conf/lists - it needs the write gate");
		synchronized (this) {
			if (!started)
				return error(VERIFY, "crawler-feeder is not running");
		}
		InetAddress address = literal(request.parameter("address"));
		if (null == address)
			return error(VERIFY, "address=<ip> is required - an IPv4 or IPv6 address, not a name");
		String only = request.parameter("feed");
		FeederVerifyResponse response = new FeederVerifyResponse();
		response.setAction(VERIFY);
		response.setAddress(address.getHostAddress());
		for (FeederConfig.Feed feed : config.feeds())
		{
			if (!feed.verifies() || (null != only && !only.equals(feed.name())))
				continue;
			ClaimCheck.Verdict verdict = check(feed, address, false);
			FeederVerifyResponse.Check c = new FeederVerifyResponse.Check();
			c.setFeed(feed.name());
			c.setOutcome(verdict.outcome().name().toLowerCase(java.util.Locale.ROOT));
			c.setHost(verdict.host());
			c.setReason(verdict.reason());
			FeedState state = states.get(feed.name());
			if (null != state)
				synchronized (state) {
					c.setListed(state.verified.containsKey(address.getHostAddress()));
				}
			response.getChecks().add(c);
		}
		if (response.getChecks().isEmpty())
			return error(VERIFY, (null == only) ? "no feed has domains to check against"
					: "no feed with domains is named '" + only + "'");
		response.setResponseStatus(ActionResponse.RESPONSE_STATUS_OK);
		return response;
	}

	FeederStatusResponse status(String action) {
		FeederConfig cfg = config;
		FeederStatusResponse response = new FeederStatusResponse();
		response.setAction(action);
		response.setResponseStatus(ActionResponse.RESPONSE_STATUS_OK);
		for (FeederConfig.Feed feed : cfg.feeds())
		{
			FeedState state = states.get(feed.name());
			FeederStatusResponse.FeedStatus f = new FeederStatusResponse.FeedStatus();
			f.setName(feed.name());
			f.setList("lists/" + feed.name() + ".txt");
			if (feed.fetches())
			{
				f.setUrl(feed.url().toString());
				f.setEvery(feed.every().toString());
			}
			f.setDomains(feed.domains());
			f.setRules(feed.rules());
			if (null != state)
			{
				f.setLastAttempt(iso(state.lastAttempt));
				f.setLastSuccess(iso(state.lastSuccess));
				f.setBlocks(state.blocks);
				f.setLastError(state.lastError);
				f.setConsecutiveFailures(state.consecutiveFailures);
				f.setWriteError(state.writeError);
				synchronized (state) {
					f.setVerified(state.verified.size());
					List<Verified> newest = new ArrayList<>(state.verified.values());
					Collections.reverse(newest);
					for (Verified v : newest.subList(0, Math.min(STATUS_CONFIRMED, newest.size())))
						f.getConfirmed().add(new FeederStatusResponse.Confirmed(v.address(), v.host(), iso(v.confirmed()),
								iso(v.confirmed().plus(cfg.keep()))));
				}
			}
			ScheduledFuture<?> next;
			synchronized (this) {
				next = scheduled.get(feed.name());
			}
			if (null != next && !next.isDone())
				f.setNextRunSeconds(Math.max(0, next.getDelay(TimeUnit.SECONDS)));
			response.getFeeds().add(f);
		}
		FeederStatusResponse.Claimants c = response.getClaimants();
		c.setKeep(cfg.keep().toString());
		c.setRetry(cfg.retry().toString());
		c.setMaxVerified(cfg.maxVerified());
		c.setClaims(claims.get());
		c.setLookups(lookups.get());
		c.setConfirmed(confirmations.get());
		c.setRefuted(refutations.get());
		c.setUnknown(unknowns.get());
		c.setDropped(dropped.get());
		c.setIgnored(ignored.get());
		c.setPending(pending.size());
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
