/**
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 */
package org.frontcache.hystrix;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

import org.frontcache.FCConfig;
import org.frontcache.hystrix.FcCommandConfig.Isolation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.netflix.hystrix.HystrixCommandGroupKey;
import com.netflix.hystrix.HystrixCommandKey;
import com.netflix.hystrix.HystrixEventType;
import com.netflix.hystrix.HystrixThreadPoolKey;
import com.netflix.hystrix.exception.HystrixBadRequestException;
import com.netflix.hystrix.exception.HystrixRuntimeException;

import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.bulkhead.BulkheadFullException;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.timelimiter.TimeLimiter;

/**
 * The seam. Reproduces the slice of the {@code HystrixCommand} contract Frontcache's five commands
 * actually use, on top of Resilience4j.
 *
 * The five commands consume exactly five things from Hystrix: the {@code Setter} chain in the
 * constructor, {@code run()}, {@code getFallback()}, {@code getExecutionEvents()},
 * {@code getFailedExecutionException()} and {@code execute()}. That uniformity is what makes this
 * migration a base-class swap rather than a rewrite - see proposal sections 3.1 and 4.
 *
 * Two behavioral details are preserved deliberately, because getting either wrong would be a silent
 * production change:
 *
 * - {@code getFailedExecutionException()} is populated only for a genuine FAILURE, and stays null for
 *   TIMEOUT / SHORT_CIRCUITED / rejections, exactly as Hystrix does. The existing fallbacks depend on
 *   it: FC_ThroughCache_HttpClient's log line passes it to SLF4J as the throwable, and expects a null
 *   there for a timeout so it logs a message without a stack trace.
 * - {@code getFallback()} is invoked from the catch block below, on the calling thread, with no
 *   reactive subscription in between - so it always runs. That is the fix for the "in multi threaded
 *   environment hystrix does not resolve fallback ??" comment in ConcurrentIncludeProcessor
 *   (section 6.4).
 *
 * An {@link FcBadRequestException} is rethrown to the caller without resolving a fallback and without
 * moving the circuit breaker's failure rate, matching HystrixBadRequestException.
 *
 * <h2>The kill switch</h2>
 *
 * For one release, {@code front-cache.resilience.impl=hystrix|resilience4j} (default
 * {@code resilience4j}) selects which library actually executes the call, so a rollback on a live site
 * is a config edit and a restart rather than a redeploy. The branch lives here, in one method, and the
 * five FC_* commands are single-implementation regardless - they never learn which backend ran them.
 *
 * Two things make that work:
 *
 * - {@link FcCommandMetrics} is recorded in {@link #execute()}, ABOVE the branch, so both backends
 *   populate the same counters. Without that, flipping to {@code hystrix} after the metrics stream is
 *   rewritten would blank the dashboard, because the new emitter reads these counters and not Hystrix's.
 *   It also means the golden-key test proves both backends emit identical JSON.
 * - The legacy path is an inner {@link com.netflix.hystrix.HystrixCommand} built from the same keys,
 *   whose run() and getFallback() call straight back into this instance. It reproduces today's behavior
 *   faithfully, including the parts the migration is meant to fix: the semaphore-path fallback still
 *   runs on the Hystrix timer thread concurrently with run() (see
 *   HystrixSemaphoreTimeoutMeasurementTest), and thread-pool metrics still come from Hystrix's own
 *   pools rather than {@link FcThreadPools}. That is the point of a rollback path - it is today, not a
 *   better version of today.
 *
 * PR 4 deletes this branch along with the rest of Hystrix.
 */
public abstract class FcCommand<T> {

	private static final Logger logger = LoggerFactory.getLogger(FcCommand.class);

	/** The kill switch (proposal section 14.1). Removed with the rest of Hystrix in PR 4. */
	static final String RESILIENCE_IMPL_KEY = "front-cache.resilience.impl";
	static final String IMPL_RESILIENCE4J = "resilience4j";
	static final String IMPL_HYSTRIX = "hystrix";

	private static final java.util.concurrent.atomic.AtomicBoolean backendLogged =
			new java.util.concurrent.atomic.AtomicBoolean(false);

	private final String groupKey;
	private final String commandKey;
	private final String threadPoolKey;
	/** Null when the command declared no pool - the legacy path needs to know the difference. */
	private final String declaredThreadPoolKey;
	private final FcCommandConfig config;
	private final FcCommandMetrics metrics;

	private final List<FcEventType> executionEvents = new CopyOnWriteArrayList<FcEventType>();
	private volatile Throwable failedExecutionException = null;
	/** Set by the legacy path's fallback, which may run on the Hystrix timer thread. */
	private volatile boolean fallbackInvoked = false;

	protected FcCommand(Setter setter) {
		if (null == setter || null == setter.groupKey || null == setter.commandKey)
			throw new IllegalArgumentException("FcCommand needs both a group key and a command key");

		this.groupKey = setter.groupKey;
		this.commandKey = setter.commandKey;
		this.config = FcResilienceConfig.getInstance().forCommand(setter.commandKey, setter.threadPoolKey);
		this.declaredThreadPoolKey = setter.threadPoolKey;
		this.threadPoolKey = (null != setter.threadPoolKey) ? setter.threadPoolKey : setter.commandKey;
		this.metrics = FcCommandMetrics.getInstance(setter.commandKey, setter.groupKey, config.getRollingStatsMs());
	}

	/**
	 * Mirrors {@code HystrixCommand.Setter} so the constructor bodies of the five commands change by
	 * one type name and nothing else.
	 */
	public static class Setter {

		private String groupKey;
		private String commandKey;
		private String threadPoolKey;

		private Setter() { }

		public static Setter withGroupKey(String groupKey)
		{
			Setter setter = new Setter();
			setter.groupKey = groupKey;
			return setter;
		}

		public Setter andCommandKey(String commandKey)
		{
			this.commandKey = commandKey;
			return this;
		}

		public Setter andThreadPoolKey(String threadPoolKey)
		{
			this.threadPoolKey = threadPoolKey;
			return this;
		}
	}

	protected abstract T run() throws Exception;

	/**
	 * Overridden by every Frontcache command. The base implementation signals "no fallback available",
	 * which {@link #execute()} turns into an {@link FcCommandException} - the HystrixRuntimeException
	 * equivalent.
	 */
	protected T getFallback()
	{
		throw new NoFallbackAvailable();
	}

	/** What the watchdog names in its log line. Overridable so a command can add the URL it is on. */
	protected String getExecutionDescription()
	{
		return commandKey;
	}

	public T execute()
	{
		final boolean legacy = isHystrixBackend();

		// Only the Resilience4j semaphore path is watched. The thread path has a real TimeLimiter, and
		// the legacy path has Hystrix's own timeout - watching either would double-report the same
		// overrun. See proposal section 6.2.
		long budgetMs = (!legacy && Isolation.SEMAPHORE == config.getIsolation()) ? config.getTimeoutMs() : 0;
		long executionId = metrics.markStart(budgetMs, getExecutionDescription());
		long startNanos = System.nanoTime();

		try {
			return legacy ? executeViaHystrix() : executeViaResilience4j();
		} finally {
			metrics.recordLatency(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos));
			metrics.markEnd(executionId);
		}
	}

	/**
	 * Which library executes the call. Read per execution rather than cached, so a rollback drill on a
	 * running node is a property change away; the read is a map lookup.
	 *
	 * A system property wins over frontcache.properties, which is what lets the test suites run
	 * end-to-end against both backends without editing every FRONTCACHE_HOME in the repo.
	 */
	static boolean isHystrixBackend()
	{
		String impl = System.getProperty(RESILIENCE_IMPL_KEY);
		if (null == impl)
			impl = FCConfig.getProperty(RESILIENCE_IMPL_KEY, IMPL_RESILIENCE4J);

		boolean hystrix = IMPL_HYSTRIX.equalsIgnoreCase(impl.trim());

		// Reported once, from the one place that resolves the value. Which library is executing the
		// request path is not something anyone should have to infer from a stack trace - and a rollback
		// drill needs a line in the log confirming the flip actually took effect.
		if (backendLogged.compareAndSet(false, true))
			logger.info("Resilience implementation: " + (hystrix ? IMPL_HYSTRIX : IMPL_RESILIENCE4J)
					+ " (" + RESILIENCE_IMPL_KEY + "=" + impl.trim() + ")");

		return hystrix;
	}

	private T executeViaResilience4j()
	{
		try {
			T result = (Isolation.SEMAPHORE == config.getIsolation()) ? executeSemaphore() : executeThread();
			recordEvent(FcEventType.SUCCESS);
			return result;
		} catch (FcBadRequestException e) {
			// The caller asked for something invalid: no fallback, and the dependency's health is not
			// in question. Rethrown as-is, and excluded from the failure rate by ignoreExceptions.
			recordEvent(FcEventType.BAD_REQUEST);
			throw e;
		} catch (Throwable t) {
			Throwable cause = unwrap(t);
			FcEventType failure = classify(cause);
			recordEvent(failure);

			if (FcEventType.FAILURE == failure)
				this.failedExecutionException = cause;

			return resolveFallback(failure, cause);
		}
	}

	/**
	 * The rollback path: Hystrix executes, this class still records the metrics and still resolves the
	 * fallback, so everything above the library boundary is unchanged.
	 */
	private T executeViaHystrix()
	{
		LegacyHystrixCommand legacy = new LegacyHystrixCommand();

		try {
			T result = legacy.execute();

			// The fallback path records its own events (on whichever thread Hystrix ran it on), so a
			// SUCCESS here means the value came from run().
			if (!fallbackInvoked)
				recordEvent(FcEventType.SUCCESS);

			return result;
		} catch (HystrixBadRequestException e) {
			recordEvent(FcEventType.BAD_REQUEST);
			throw asBadRequest(e);
		} catch (HystrixRuntimeException e) {
			// run() failed and so did the fallback - the events are already recorded by whichever of
			// them ran. Surface the same exception type the Resilience4j path would.
			if (e.getCause() instanceof FcBadRequestException)
			{
				recordEvent(FcEventType.BAD_REQUEST);
				throw (FcBadRequestException) e.getCause();
			}

			FcEventType failure = translateFailure(legacy.getExecutionEvents());
			throw new FcCommandException(commandKey, failure,
					commandKey + " failed and has no usable fallback: " + failure, unwrap(e));
		}
	}

	private FcBadRequestException asBadRequest(HystrixBadRequestException e)
	{
		return (e.getCause() instanceof FcBadRequestException)
				? (FcBadRequestException) e.getCause()
				: new FcBadRequestException(e.getMessage(), e);
	}

	/**
	 * Collapses a Hystrix event list to the single failure outcome that caused the fallback, using the
	 * same precedence {@link FallbackLogger#failureType} applies - so the two agree by construction.
	 */
	private static FcEventType translateFailure(java.util.List<HystrixEventType> events)
	{
		if (null == events)
			return FcEventType.FAILURE;

		if (events.contains(HystrixEventType.SHORT_CIRCUITED))
			return FcEventType.SHORT_CIRCUITED;
		if (events.contains(HystrixEventType.TIMEOUT))
			return FcEventType.TIMEOUT;
		if (events.contains(HystrixEventType.THREAD_POOL_REJECTED))
			return FcEventType.THREAD_POOL_REJECTED;
		if (events.contains(HystrixEventType.SEMAPHORE_REJECTED))
			return FcEventType.SEMAPHORE_REJECTED;
		if (events.contains(HystrixEventType.BAD_REQUEST))
			return FcEventType.BAD_REQUEST;

		return FcEventType.FAILURE;
	}

	/** The inner Hystrix command of the legacy path - constructed per execution, as Hystrix requires. */
	private class LegacyHystrixCommand extends com.netflix.hystrix.HystrixCommand<T> {

		LegacyHystrixCommand() {
			super(buildLegacySetter());
		}

		@Override
		protected T run() throws Exception
		{
			try {
				return FcCommand.this.run();
			} catch (FcBadRequestException e) {
				// Hystrix only skips the fallback and the failure counters for its own bad-request type.
				throw new HystrixBadRequestException(e.getMessage(), e);
			}
		}

		@Override
		protected T getFallback()
		{
			fallbackInvoked = true;

			FcEventType failure = translateFailure(getExecutionEvents());
			recordEvent(failure);

			// Hystrix already returns null here for timeouts and short-circuits, which is the contract
			// the existing fallbacks rely on - so this is assigned verbatim rather than filtered.
			FcCommand.this.failedExecutionException = getFailedExecutionException();

			try {
				T fallback = FcCommand.this.getFallback();
				recordEvent(FcEventType.FALLBACK_SUCCESS);
				return fallback;
			} catch (Throwable t) {
				recordEvent(FcEventType.FALLBACK_FAILURE);
				recordEvent(FcEventType.EXCEPTION_THROWN);
				throw t;
			}
		}
	}

	private com.netflix.hystrix.HystrixCommand.Setter buildLegacySetter()
	{
		com.netflix.hystrix.HystrixCommand.Setter setter = com.netflix.hystrix.HystrixCommand.Setter
				.withGroupKey(HystrixCommandGroupKey.Factory.asKey(groupKey))
				.andCommandKey(HystrixCommandKey.Factory.asKey(commandKey));

		// Only set the pool key when the command actually declared one: Hystrix defaults a
		// thread-pool key to the group key, and inventing one here would register a pool per domain
		// that today's deployment does not have.
		if (null != declaredThreadPoolKey)
			setter = setter.andThreadPoolKey(HystrixThreadPoolKey.Factory.asKey(declaredThreadPoolKey));

		return setter;
	}

	private T executeSemaphore()
	{
		CircuitBreaker breaker = FcResilienceRegistry.circuitBreaker(config);
		Bulkhead bulkhead = FcResilienceRegistry.bulkhead(config);

		// Breaker outermost, matching Hystrix's order: the circuit is checked before a permit is taken,
		// so a short-circuited call never consumes semaphore capacity.
		Supplier<T> guarded = CircuitBreaker.decorateSupplier(breaker,
				Bulkhead.decorateSupplier(bulkhead, this::runUnchecked));

		return guarded.get();
	}

	private T executeThread() throws Exception
	{
		CircuitBreaker breaker = FcResilienceRegistry.circuitBreaker(config);
		TimeLimiter timeLimiter = FcResilienceRegistry.timeLimiter(config);
		final ThreadPoolExecutor pool = FcThreadPools.pool(threadPoolKey);

		Callable<T> guarded = CircuitBreaker.decorateCallable(breaker,
				() -> timeLimiter.executeFutureSupplier(() -> pool.submit(this::runUnchecked)));

		return guarded.call();
	}

	/**
	 * run() may throw checked exceptions; Resilience4j's Supplier cannot. Checked exceptions are
	 * wrapped here and unwrapped in {@link #unwrap} so classification and
	 * getFailedExecutionException() see the exception the command actually threw, not a wrapper.
	 */
	private T runUnchecked()
	{
		try {
			return run();
		} catch (RuntimeException e) {
			throw e;
		} catch (Exception e) {
			throw new CheckedRunException(e);
		}
	}

	private T resolveFallback(FcEventType failure, Throwable cause)
	{
		try {
			T fallback = getFallback();
			recordEvent(FcEventType.FALLBACK_SUCCESS);
			return fallback;
		} catch (NoFallbackAvailable e) {
			recordEvent(FcEventType.FALLBACK_FAILURE);
			recordEvent(FcEventType.EXCEPTION_THROWN);
			throw new FcCommandException(commandKey, failure,
					commandKey + " failed and has no fallback: " + failure, cause);
		} catch (Throwable fallbackFailure) {
			recordEvent(FcEventType.FALLBACK_FAILURE);
			recordEvent(FcEventType.EXCEPTION_THROWN);
			logger.error("Fallback for " + commandKey + " failed", fallbackFailure);
			throw new FcCommandException(commandKey, failure,
					commandKey + " failed and its fallback failed too: " + failure, cause);
		}
	}

	private void recordEvent(FcEventType event)
	{
		executionEvents.add(event);
		metrics.markEvent(event);
	}

	private static FcEventType classify(Throwable t)
	{
		if (t instanceof CallNotPermittedException)
			return FcEventType.SHORT_CIRCUITED;
		if (t instanceof BulkheadFullException)
			return FcEventType.SEMAPHORE_REJECTED;
		if (t instanceof RejectedExecutionException)
			return FcEventType.THREAD_POOL_REJECTED;
		if (t instanceof TimeoutException)
			return FcEventType.TIMEOUT;
		return FcEventType.FAILURE;
	}

	/**
	 * Peels the wrappers Resilience4j and the executor add, so classification sees the original.
	 * A rejection thrown while obtaining the future arrives wrapped in a CompletionException, and a
	 * checked exception from run() arrives in our own wrapper.
	 */
	private static Throwable unwrap(Throwable t)
	{
		Throwable current = t;
		while (null != current)
		{
			if (current instanceof CheckedRunException
					|| current instanceof java.util.concurrent.CompletionException
					|| current instanceof java.util.concurrent.ExecutionException)
			{
				if (null == current.getCause())
					return current;
				current = current.getCause();
				continue;
			}
			return current;
		}
		return t;
	}

	// ---- the HystrixCommand-shaped accessors --------------------------------

	/**
	 * The events of this execution, in the order they happened. Read inside getFallback() by four of
	 * the five commands (via {@link FallbackLogger#failureType}), so the failure event is already
	 * recorded by the time the fallback runs.
	 */
	public List<FcEventType> getExecutionEvents()
	{
		return Collections.unmodifiableList(executionEvents);
	}

	/** Null unless run() itself threw - see the class javadoc. */
	public Throwable getFailedExecutionException()
	{
		return failedExecutionException;
	}

	public String getCommandKey()
	{
		return commandKey;
	}

	public String getGroupKey()
	{
		return groupKey;
	}

	FcCommandConfig getConfig()
	{
		return config;
	}

	/** Marker for "getFallback() was not overridden" - never escapes execute(). */
	private static class NoFallbackAvailable extends RuntimeException {
		private static final long serialVersionUID = 1L;
	}

	/** Carries a checked exception from run() through Resilience4j's unchecked functional types. */
	private static class CheckedRunException extends RuntimeException {
		private static final long serialVersionUID = 1L;

		CheckedRunException(Throwable cause) {
			super(cause);
		}
	}
}
