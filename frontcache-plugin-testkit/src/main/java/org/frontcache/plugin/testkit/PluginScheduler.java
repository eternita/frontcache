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

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * What {@link TestPluginContext#scheduler()} hands a plugin - one view per start of the plugin, over threads the
 * context owns, with the node's rules:
 *
 * <ul>
 * <li><b>every task is wrapped</b>: a task that throws is logged and counted ({@link #taskErrors()}), and a periodic
 * one keeps its schedule - a bare {@code ScheduledThreadPoolExecutor} would silently stop it;</li>
 * <li><b>the tasks are cancelled when the plugin stops</b> - a {@code reload} included - so a plugin schedules them
 * in {@code start}, and asks {@code context.scheduler()} there: the view of a stopped start refuses new tasks;</li>
 * <li>{@link #shutdown()} cancels this plugin's tasks only, never the threads under them.</li>
 * </ul>
 */
public final class PluginScheduler extends AbstractExecutorService implements ScheduledExecutorService {

	private static final System.Logger LOG = System.getLogger(PluginScheduler.class.getName());

	private final ScheduledExecutorService executor;
	private final String pluginId;
	private final Set<ScheduledFuture<?>> tasks = ConcurrentHashMap.newKeySet();
	private final AtomicInteger errors = new AtomicInteger();
	private final AtomicReference<Throwable> lastError = new AtomicReference<>();
	private volatile boolean shutdown;

	PluginScheduler(ScheduledExecutorService executor, String pluginId) {
		this.executor = executor;
		this.pluginId = pluginId;
	}

	@Override
	public ScheduledFuture<?> schedule(Runnable command, long delay, TimeUnit unit) {
		open();
		return track(executor.schedule(wrap(command), delay, unit));
	}

	@Override
	public <V> ScheduledFuture<V> schedule(Callable<V> callable, long delay, TimeUnit unit) {
		open();
		return track(executor.schedule(wrap(callable), delay, unit));
	}

	@Override
	public ScheduledFuture<?> scheduleAtFixedRate(Runnable command, long initialDelay, long period, TimeUnit unit) {
		open();
		return track(executor.scheduleAtFixedRate(wrap(command), initialDelay, period, unit));
	}

	@Override
	public ScheduledFuture<?> scheduleWithFixedDelay(Runnable command, long initialDelay, long delay, TimeUnit unit) {
		open();
		return track(executor.scheduleWithFixedDelay(wrap(command), initialDelay, delay, unit));
	}

	@Override
	public void execute(Runnable command) {
		schedule(command, 0, TimeUnit.NANOSECONDS);
	}

	/** Cancels this plugin's tasks and refuses new ones; the threads are the context's. */
	@Override
	public void shutdown() {
		shutdown = true;
		cancelAll();
	}

	@Override
	public List<Runnable> shutdownNow() {
		shutdown();
		return new ArrayList<>();
	}

	@Override
	public boolean isShutdown() {
		return shutdown;
	}

	@Override
	public boolean isTerminated() {
		return shutdown && activeTasks() == 0;
	}

	@Override
	public boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
		long deadline = System.nanoTime() + unit.toNanos(timeout);
		while (!isTerminated())
		{
			if (System.nanoTime() > deadline)
				return false;
			Thread.sleep(5);
		}
		return true;
	}

	/** How many tasks of this view threw (each run of a periodic task counts). */
	public int taskErrors() {
		return errors.get();
	}

	/** What the last failing task threw, or null. */
	public Throwable lastError() {
		return lastError.get();
	}

	/** Tasks scheduled through this view that are neither done nor cancelled - a periodic task stays active. */
	public int activeTasks() {
		int active = 0;
		for (ScheduledFuture<?> task : tasks)
			if (!task.isDone())
				active++;
		return active;
	}

	/** Tasks scheduled through this view, ever - done and cancelled ones included. */
	public int scheduledTasks() {
		return tasks.size();
	}

	/** What the harness does when the plugin stops. */
	void cancelAll() {
		shutdown = true;
		for (ScheduledFuture<?> task : tasks)
			task.cancel(false);
	}

	private void open() {
		if (shutdown)
			throw new RejectedExecutionException("the scheduler of plugin '" + pluginId + "' is shut down - it was"
					+ " cancelled when the plugin stopped; ask context.scheduler() again in start()");
	}

	private <T extends ScheduledFuture<?>> T track(T future) {
		tasks.add(future);
		return future;
	}

	private Runnable wrap(Runnable command) {
		if (null == command)
			throw new NullPointerException("command");
		return () -> {
			try {
				command.run();
			} catch (Throwable t) {
				failed(t);
			}
		};
	}

	private <V> Callable<V> wrap(Callable<V> callable) {
		if (null == callable)
			throw new NullPointerException("callable");
		return () -> {
			try {
				return callable.call();
			} catch (Exception | Error e) {
				failed(e);
				throw e;
			}
		};
	}

	private void failed(Throwable t) {
		errors.incrementAndGet();
		lastError.set(t);
		LOG.log(System.Logger.Level.WARNING, "A task of plugin '" + pluginId + "' threw - logged, and a periodic"
				+ " task keeps its schedule", t);
	}
}
