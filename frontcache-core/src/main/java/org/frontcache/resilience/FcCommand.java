/**
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 */
package org.frontcache.resilience;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

import org.frontcache.resilience.FcCommandConfig.Isolation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
 */
public abstract class FcCommand<T> {

	private static final Logger logger = LoggerFactory.getLogger(FcCommand.class);

	private final String groupKey;
	private final String commandKey;
	private final String threadPoolKey;
	private final FcCommandConfig config;
	private final FcCommandMetrics metrics;

	private final List<FcEventType> executionEvents = new CopyOnWriteArrayList<FcEventType>();
	private volatile Throwable failedExecutionException = null;

	protected FcCommand(Setter setter) {
		if (null == setter || null == setter.groupKey || null == setter.commandKey)
			throw new IllegalArgumentException("FcCommand needs both a group key and a command key");

		this.groupKey = setter.groupKey;
		this.commandKey = setter.commandKey;
		this.config = FcResilienceConfig.getInstance().forCommand(setter.commandKey, setter.threadPoolKey);
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
		// Only the semaphore path is watched. The thread path has a real TimeLimiter, so watching it too
		// would report the same overrun twice. See proposal section 6.2.
		long budgetMs = (Isolation.SEMAPHORE == config.getIsolation()) ? config.getTimeoutMs() : 0;
		FcCommandMetrics.InFlight execution = metrics.markStart(budgetMs, getExecutionDescription());
		long startNanos = System.nanoTime();

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
		} finally {
			metrics.recordLatency(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos));
			metrics.markEnd(execution);
		}
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
