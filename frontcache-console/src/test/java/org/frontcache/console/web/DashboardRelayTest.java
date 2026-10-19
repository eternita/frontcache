/*
 * Copyright 2015 Eternita
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
package org.frontcache.console.web;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.frontcache.console.web.DashboardStreamProxyServlet.Relay;
import org.junit.Test;

import jakarta.servlet.AsyncContext;
import jakarta.servlet.AsyncEvent;
import jakarta.servlet.AsyncListener;
import jakarta.servlet.ServletContext;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.WriteListener;

/**
 * The relay that joins the node's SSE stream to the browser, now that neither end blocks.
 *
 * The thing worth pinning is the join: a chunk is requested from the node only once everything already
 * received has been written to the browser. Get that wrong in the obvious direction - request eagerly,
 * the way a plain subscriber would - and the console quietly buffers an <em>endless</em> stream in its
 * heap whenever a viewer stalls. Nothing fails, nothing logs; the process just grows.
 *
 * The other half is that this relay must never drop, where the node's stream deliberately does. The node
 * knows what a frame is and that a stale one is worthless. Here a dropped chunk cuts an SSE frame in
 * half and corrupts everything after it, so demand is what bounds the memory instead.
 */
public class DashboardRelayTest {

	private static List<ByteBuffer> chunk(String text) {
		return Arrays.asList(ByteBuffer.wrap(text.getBytes(StandardCharsets.UTF_8)));
	}

	// ---- the demand join ------------------------------------------------------------------------

	@Test
	public void subscribingAsksForExactlyOneChunk()
	{
		Fixture fixture = new Fixture();

		fixture.relay.onSubscribe(fixture.subscription);

		assertEquals(1, fixture.subscription.requested.get());
		assertTrue(fixture.relay.hasOutstandingDemand());
	}

	/**
	 * Demand is gated on the QUEUE being empty, not on the browser being ready, and the difference
	 * matters in one direction only.
	 *
	 * Gating on {@code isReady()} reads stricter and risks a hang: {@code onWritePossible()} fires only
	 * on a not-ready -&gt; ready transition, so a relay that declines to request while not ready, and is
	 * then never called back, waits forever with an idle connection and nothing in any log. Gating on
	 * the queue cannot deadlock - the queue empties only inside {@code drain()}, which requests on its
	 * way out - and it bounds the memory just as well: one chunk in flight plus at most one held.
	 */
	@Test
	public void demandIsBoundedToOneChunkInFlightEvenWhenTheBrowserIsNotReady()
	{
		Fixture fixture = new Fixture();
		fixture.out.ready = false;

		fixture.relay.onSubscribe(fixture.subscription);
		assertEquals("one in flight", 1, fixture.subscription.requested.get());

		// it arrives and cannot be written, so it is held - and nothing more is asked for
		fixture.relay.onNext(chunk("data: one\n\n"));

		assertEquals(1, fixture.relay.queueSize());
		assertEquals("a relay that kept requesting would buffer an endless stream in the console's heap",
				1, fixture.subscription.requested.get());
	}

	/** Written through, then one more chunk asked for. One in flight at a time, never two. */
	@Test
	public void eachChunkIsWrittenAndThenOneMoreIsRequested()
	{
		Fixture fixture = new Fixture();
		fixture.relay.onSubscribe(fixture.subscription);

		fixture.relay.onNext(chunk("data: one\n\n"));

		assertEquals("data: one\n\n", fixture.out.written());
		assertEquals(0, fixture.relay.queueSize());
		assertEquals("exactly one further chunk", 2, fixture.subscription.requested.get());
	}

	/**
	 * The property the whole design rests on. A browser that stops taking bytes must stop us reading
	 * from the node - that is what pushes the backpressure back up the chain to where it can be handled.
	 */
	@Test
	public void aStalledBrowserStopsUsAskingTheNodeForMore()
	{
		Fixture fixture = new Fixture();
		fixture.relay.onSubscribe(fixture.subscription);
		int afterSubscribe = fixture.subscription.requested.get();

		fixture.out.ready = false;
		fixture.relay.onNext(chunk("data: one\n\n"));

		assertEquals("nothing could be written", "", fixture.out.written());
		assertEquals("so it is held, not dropped - a half frame would corrupt the stream",
				1, fixture.relay.queueSize());
		assertEquals("and no further chunk is requested while it is held",
				afterSubscribe, fixture.subscription.requested.get());
	}

	/** And when the browser recovers, the held chunk goes out and the flow resumes. */
	@Test
	public void aRecoveredBrowserDrainsAndResumesTheFlow()
	{
		Fixture fixture = new Fixture();
		fixture.relay.onSubscribe(fixture.subscription);

		fixture.out.ready = false;
		fixture.relay.onNext(chunk("data: one\n\n"));
		int whileStalled = fixture.subscription.requested.get();

		fixture.out.ready = true;
		fixture.relay.onWritePossible();

		assertEquals("data: one\n\n", fixture.out.written());
		assertEquals(0, fixture.relay.queueSize());
		assertTrue("the node is asked for more only now", fixture.subscription.requested.get() > whileStalled);
	}

	/** Bytes arrive in whatever chunking the network gave them; the relay must not reorder or lose any. */
	@Test
	public void chunksAreWrittenInOrder()
	{
		Fixture fixture = new Fixture();
		fixture.relay.onSubscribe(fixture.subscription);

		fixture.out.ready = false;
		fixture.relay.onNext(Arrays.asList(
				ByteBuffer.wrap("data: on".getBytes(StandardCharsets.UTF_8)),
				ByteBuffer.wrap("e\n\n".getBytes(StandardCharsets.UTF_8))));
		fixture.relay.onNext(chunk("data: two\n\n"));

		fixture.out.ready = true;
		fixture.relay.onWritePossible();

		assertEquals("data: one\n\ndata: two\n\n", fixture.out.written());
	}

	/**
	 * Flushing is not optional. Without it the container aggregates and releases on a full buffer, which
	 * was measured on the node's own stream as ~3 s of silence followed by a burst.
	 */
	@Test
	public void theBrowserIsFlushedRatherThanLeftToTheContainersBuffer()
	{
		Fixture fixture = new Fixture();
		fixture.relay.onSubscribe(fixture.subscription);

		fixture.relay.onNext(chunk("data: one\n\n"));

		assertEquals("a stream delivered on buffer-full is a dashboard that jumps rather than ticks",
				1, fixture.out.flushes.get());
	}

	// ---- ending the relay -----------------------------------------------------------------------

	@Test
	public void aClosedBrowserCancelsTheUpstreamSubscription()
	{
		Fixture fixture = new Fixture();
		fixture.relay.onSubscribe(fixture.subscription);
		fixture.out.failWith = new IOException("broken pipe");

		fixture.relay.onNext(chunk("data: one\n\n"));

		assertTrue(fixture.relay.isClosed());
		assertTrue("the node must stop being read - otherwise the console holds a connection for a"
				+ " dashboard nobody is watching", fixture.subscription.cancelled.get());
		assertEquals(1, fixture.async.completed.get());
	}

	@Test
	public void anUpstreamFailureEndsTheBrowserConnection()
	{
		Fixture fixture = new Fixture();
		fixture.relay.onSubscribe(fixture.subscription);

		fixture.relay.onError(new IOException("edge went away"));

		assertTrue(fixture.relay.isClosed());
		assertEquals(1, fixture.async.completed.get());
	}

	/** The node's stream has no natural end, so onComplete means the edge went away. */
	@Test
	public void anUpstreamThatEndsClosesTheRelay()
	{
		Fixture fixture = new Fixture();
		fixture.relay.onSubscribe(fixture.subscription);

		fixture.relay.onComplete();

		assertTrue(fixture.relay.isClosed());
	}

	/** Both ends can fail at once, and the container can also end the request. Once is once. */
	@Test
	public void theRelayIsClosedExactlyOnceHoweverItEnds()
	{
		Fixture fixture = new Fixture();
		fixture.relay.onSubscribe(fixture.subscription);

		fixture.relay.close();
		fixture.relay.onComplete();
		fixture.relay.onError(new IOException("too late"));
		fixture.relay.onComplete(new AsyncEvent(fixture.async));
		fixture.relay.onTimeout(new AsyncEvent(fixture.async));
		fixture.relay.onError(new AsyncEvent(fixture.async));

		assertEquals(1, fixture.async.completed.get());
		assertEquals(1, fixture.subscription.cancelled.get() ? 1 : 0);
	}

	@Test
	public void aClosedRelayWritesNothingFurther()
	{
		Fixture fixture = new Fixture();
		fixture.relay.onSubscribe(fixture.subscription);
		fixture.relay.close();

		fixture.relay.onNext(chunk("data: late\n\n"));

		assertEquals("", fixture.out.written());
	}

	// ---- fixtures -------------------------------------------------------------------------------

	private static class Fixture {

		final FakeAsyncContext async = new FakeAsyncContext();
		final FakeOutputStream out = new FakeOutputStream();
		final FakeSubscription subscription = new FakeSubscription();
		final Relay relay;

		Fixture() {
			relay = new Relay("http://edge.example.com/hystrix.stream", async, out);
			relay.start();
		}
	}

	private static class FakeSubscription implements Flow.Subscription {

		final AtomicInteger requested = new AtomicInteger();
		final AtomicBoolean cancelled = new AtomicBoolean();

		@Override public void request(long n) { requested.incrementAndGet(); }
		@Override public void cancel() { cancelled.set(true); }
	}

	/** Lets a test decide when the browser is willing to take bytes - the one thing a socket will not. */
	private static class FakeOutputStream extends ServletOutputStream {

		volatile boolean ready = true;
		volatile IOException failWith = null;

		final AtomicInteger flushes = new AtomicInteger();

		private final StringBuilder sink = new StringBuilder();

		@Override public boolean isReady() { return ready; }

		@Override public void setWriteListener(WriteListener writeListener) { }

		@Override
		public void write(byte[] b) throws IOException {
			if (null != failWith)
				throw failWith;
			sink.append(new String(b, StandardCharsets.UTF_8));
		}

		@Override
		public void write(int b) {
			throw new UnsupportedOperationException("chunks are written whole");
		}

		@Override public void flush() { flushes.incrementAndGet(); }

		String written() { return sink.toString(); }
	}

	private static class FakeAsyncContext implements AsyncContext {

		final AtomicInteger completed = new AtomicInteger();

		@Override public void complete() { completed.incrementAndGet(); }

		@Override public ServletRequest getRequest() { return null; }
		@Override public ServletResponse getResponse() { return null; }
		@Override public boolean hasOriginalRequestAndResponse() { return true; }
		@Override public void dispatch() { }
		@Override public void dispatch(String path) { }
		@Override public void dispatch(ServletContext context, String path) { }
		@Override public void start(Runnable run) { run.run(); }
		@Override public void addListener(AsyncListener listener) { }
		@Override public void addListener(AsyncListener listener, ServletRequest request, ServletResponse response) { }
		@Override public <T extends AsyncListener> T createListener(Class<T> clazz) { return null; }
		@Override public void setTimeout(long timeout) { }
		@Override public long getTimeout() { return 0; }
	}
}
