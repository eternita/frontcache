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

import java.util.List;
import java.util.Set;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@code PluginContext.scheduler()}: one plugin's view of threads the node shares between its plugins
 * ({@value #THREADS} daemon threads, {@code fc-plugin-scheduler-<n>}). Every task is wrapped: one that throws is
 * counted as the plugin's error and logged (at most once a minute per plugin), and a periodic one keeps its schedule -
 * a bare {@link ScheduledThreadPoolExecutor} would cancel it silently. {@link #shutdown()} cancels this plugin's tasks
 * and nobody else's; the node calls it when the plugin stops, a {@code reload} included.
 */
final class PluginScheduler extends AbstractExecutorService implements ScheduledExecutorService {

	private static final Logger logger = LoggerFactory.getLogger(PluginScheduler.class);

	static final int THREADS = 2;

	private static ScheduledThreadPoolExecutor shared;

	private final String plugin;
	private final PluginCounters counters;
	private final Set<Future<?>> tasks = ConcurrentHashMap.newKeySet();
	private final AtomicLong lastLogged = new AtomicLong();
	private volatile boolean shutdown;

	PluginScheduler(String plugin, PluginCounters counters) {
		this.plugin = plugin;
		this.counters = counters;
	}

	private static synchronized ScheduledThreadPoolExecutor shared() {
		if (null == shared) {
			AtomicInteger n = new AtomicInteger();
			shared = new ScheduledThreadPoolExecutor(THREADS, r -> {
				Thread t = new Thread(r, "fc-plugin-scheduler-" + n.incrementAndGet());
				t.setDaemon(true);
				return t;
			});
			shared.setRemoveOnCancelPolicy(true);
			shared.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
		}
		return shared;
	}

	private Runnable wrap(Runnable task) {
		if (null == task)
			throw new NullPointerException("task");
		return () -> {
			try {
				task.run();
			} catch (Throwable t) {
				failed(t);
			}
		};
	}

	private <V> Callable<V> wrap(Callable<V> task) {
		if (null == task)
			throw new NullPointerException("task");
		return () -> {
			try {
				return task.call();
			} catch (Throwable t) {
				failed(t);
				throw t;
			}
		};
	}

	private void failed(Throwable t) {
		counters.errors.increment();
		long now = System.currentTimeMillis();
		long last = lastLogged.get();
		if (now - last >= 60_000 && lastLogged.compareAndSet(last, now))
			logger.error("Plugin '" + plugin + "': a scheduled task threw (logged at most once a minute)", t);
	}

	private <F extends Future<?>> F track(F future) {
		tasks.removeIf(Future::isDone);
		tasks.add(future);
		if (shutdown) // stopped while scheduling
			future.cancel(false);
		return future;
	}

	private void open() {
		if (shutdown)
			throw new RejectedExecutionException("plugin '" + plugin + "' is stopped - its scheduler takes no task");
	}

	@Override
	public ScheduledFuture<?> schedule(Runnable command, long delay, TimeUnit unit) {
		open();
		return track(shared().schedule(wrap(command), delay, unit));
	}

	@Override
	public <V> ScheduledFuture<V> schedule(Callable<V> callable, long delay, TimeUnit unit) {
		open();
		return track(shared().schedule(wrap(callable), delay, unit));
	}

	@Override
	public ScheduledFuture<?> scheduleAtFixedRate(Runnable command, long initialDelay, long period, TimeUnit unit) {
		open();
		return track(shared().scheduleAtFixedRate(wrap(command), initialDelay, period, unit));
	}

	@Override
	public ScheduledFuture<?> scheduleWithFixedDelay(Runnable command, long initialDelay, long delay, TimeUnit unit) {
		open();
		return track(shared().scheduleWithFixedDelay(wrap(command), initialDelay, delay, unit));
	}

	@Override
	public void execute(Runnable command) {
		schedule(command, 0, TimeUnit.NANOSECONDS);
	}

	@Override
	public void shutdown() {
		shutdown = true;
		for (Future<?> task : tasks)
			task.cancel(false);
		tasks.clear();
	}

	@Override
	public List<Runnable> shutdownNow() {
		shutdown = true;
		for (Future<?> task : tasks)
			task.cancel(true);
		tasks.clear();
		return List.of();
	}

	@Override
	public boolean isShutdown() {
		return shutdown;
	}

	@Override
	public boolean isTerminated() {
		return shutdown && tasks.isEmpty();
	}

	@Override
	public boolean awaitTermination(long timeout, TimeUnit unit) {
		return isTerminated();
	}

	/** Tasks scheduled and not yet done - for tests. */
	int pending() {
		tasks.removeIf(Future::isDone);
		return tasks.size();
	}
}
