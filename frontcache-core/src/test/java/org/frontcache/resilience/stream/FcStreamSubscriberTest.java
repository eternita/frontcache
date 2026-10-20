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
package org.frontcache.resilience.stream;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.frontcache.resilience.stream.FcSampleSseServlet.Subscriber;
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
 * The connection half of the async SSE stream.
 *
 * Moving off the parked-thread model put three things on this class that a blocking loop got for free,
 * and all three fail silently rather than loudly:
 *
 * - <b>Backpressure.</b> A write is only attempted when {@code isReady()}, so a stalled reader must
 *   queue rather than block. Get it wrong and the publisher thread stalls, which stops every OTHER
 *   dashboard - the exact regression PR 3 removed, and the reason this is non-blocking output rather
 *   than "the publisher writes".
 * - <b>The idle handoff.</b> {@code onWritePossible()} only fires on a not-ready -&gt; ready
 *   transition, so a drained-while-ready connection is never called again by the container. If
 *   {@code offer()} did not also drain, the stream would deliver a burst and then go permanently quiet.
 * - <b>Releasing the concurrency slot exactly once.</b> The request returns immediately now, so the
 *   decrement moved into {@code close()}. Miss it and the endpoint answers 503 forever after N
 *   connections; do it twice and the cap stops capping.
 *
 * All three are only observable through a connection that misbehaves, which is awkward to arrange over
 * a socket and trivial against a fake {@code ServletOutputStream}.
 */
public class FcStreamSubscriberTest {

	private static byte[] frame(String name) {
		return ("data: {\"name\":\"" + name + "\"}\n\n").getBytes(StandardCharsets.UTF_8);
	}

	// ---- writing and backpressure ---------------------------------------------------------------

	@Test
	public void framesAreWrittenInOrderWhileTheConnectionIsReady()
	{
		Connection connection = new Connection();

		connection.subscriber.offer(frame("a"));
		connection.subscriber.offer(frame("b"));

		assertEquals(names("a", "b"), connection.out.written());
		assertEquals("nothing should still be queued", 0, connection.subscriber.queueSize());
	}

	/**
	 * The property that makes this safe to call from the publisher thread: a reader behind TCP
	 * backpressure takes no bytes and blocks nobody.
	 */
	@Test
	public void aStalledConnectionQueuesInsteadOfWriting()
	{
		Connection connection = new Connection();
		connection.out.ready = false;

		connection.subscriber.offer(frame("a"));
		connection.subscriber.offer(frame("b"));

		assertTrue("a stalled connection must not be written to", connection.out.written().isEmpty());
		assertEquals(2, connection.subscriber.queueSize());
		assertFalse("stalling is not an error - the connection stays open", connection.subscriber.isClosed());
	}

	/** And when the container says it can take bytes again, the backlog goes out in order. */
	@Test
	public void theBacklogFlushesOnWritePossible()
	{
		Connection connection = new Connection();
		connection.out.ready = false;

		connection.subscriber.offer(frame("a"));
		connection.subscriber.offer(frame("b"));

		connection.out.ready = true;
		connection.subscriber.onWritePossible();

		assertEquals(names("a", "b"), connection.out.written());
		assertEquals(0, connection.subscriber.queueSize());
	}

	/**
	 * The idle handoff. After a drain that empties the queue while still ready, the container will never
	 * call onWritePossible() again - so if offer() did not write, the stream would go quiet forever after
	 * the first burst.
	 */
	@Test
	public void aDrainedConnectionIsWokenByTheNextFrameAndNotByTheContainer()
	{
		Connection connection = new Connection();

		connection.subscriber.offer(frame("a"));
		assertEquals(names("a"), connection.out.written());

		// no onWritePossible() in between - this is exactly the state the container has stopped tracking,
		// because the connection never went not-ready
		connection.subscriber.offer(frame("b"));

		assertEquals(names("a", "b"), connection.out.written());
	}

	@Test
	public void theOldestFrameIsDroppedWhenTheQueueIsFull()
	{
		Connection connection = new Connection();
		connection.out.ready = false;

		int capacity = FcSampleSseServlet.SUBSCRIBER_QUEUE_CAPACITY;
		for (int i = 0; i < capacity + 3; i++)
			connection.subscriber.offer(frame("f" + i));

		assertEquals("the queue is bounded - an unbounded one behind a stalled reader is a memory leak",
				capacity, connection.subscriber.queueSize());

		connection.out.ready = true;
		connection.subscriber.onWritePossible();

		List<String> written = connection.out.written();
		assertEquals(capacity, written.size());
		assertEquals("the three OLDEST frames were dropped, not the newest - a dashboard wants current"
				+ " numbers", "f3", written.get(0));
		assertEquals("f" + (capacity + 2), written.get(written.size() - 1));
	}

	/** The whole reason for non-blocking output: one bad reader must not cost the others their stream. */
	@Test
	public void aStalledSubscriberDoesNotAffectAnother()
	{
		Connection stalled = new Connection();
		Connection healthy = new Connection();
		stalled.out.ready = false;

		// what publishTick() does, minus the metrics
		for (Connection connection : new Connection[] { stalled, healthy })
			connection.subscriber.offer(frame("tick"));

		assertTrue(stalled.out.written().isEmpty());
		assertEquals(names("tick"), healthy.out.written());
		assertFalse(healthy.subscriber.isClosed());
	}

	// ---- ending the connection ------------------------------------------------------------------

	@Test
	public void aWriteFailureClosesTheConnectionAndReleasesItsSlot()
	{
		Connection connection = new Connection();
		connection.out.failWith = new IOException("broken pipe");

		connection.subscriber.offer(frame("a"));

		assertTrue("a client that went away must not stay a subscriber", connection.subscriber.isClosed());
		assertEquals("its slot in maxConcurrentConnections must come back", 1, connection.servlet.released.get());
		assertEquals(1, connection.async.completed.get());
	}

	/**
	 * The connection can end in four ways - client disconnect, write error, container timeout, shutdown -
	 * and more than one can happen. Releasing the slot twice would make the cap stop capping.
	 */
	@Test
	public void theSlotIsReleasedExactlyOnceHoweverTheConnectionEnds()
	{
		Connection connection = new Connection();

		connection.subscriber.close();
		connection.subscriber.onComplete(new AsyncEvent(connection.async));
		connection.subscriber.onTimeout(new AsyncEvent(connection.async));
		connection.subscriber.onError(new AsyncEvent(connection.async));
		connection.subscriber.onError(new RuntimeException("write failed"));

		assertEquals(1, connection.servlet.released.get());
		assertEquals(1, connection.async.completed.get());
	}

	@Test
	public void aClosedSubscriberStopsAcceptingFrames()
	{
		Connection connection = new Connection();
		connection.subscriber.close();

		connection.subscriber.offer(frame("a"));

		assertTrue(connection.out.written().isEmpty());
		assertEquals(0, connection.subscriber.queueSize());
	}

	// ---- fixtures -------------------------------------------------------------------------------

	private static List<String> names(String... values)
	{
		List<String> list = new ArrayList<String>();
		for (String value : values)
			list.add(value);
		return list;
	}

	/** A subscriber wired to a controllable output stream and a servlet that counts released slots. */
	private static class Connection {

		final CountingServlet servlet = new CountingServlet();
		final FakeAsyncContext async = new FakeAsyncContext();
		final FakeOutputStream out = new FakeOutputStream();
		final Subscriber subscriber;

		Connection() {
			subscriber = new Subscriber(servlet, async, out);
			subscriber.start();
		}
	}

	private static class CountingServlet extends FcSampleSseServlet {

		private static final long serialVersionUID = 1L;

		final AtomicInteger released = new AtomicInteger();

		CountingServlet() { super(500); }

		@Override protected int getMaxNumberConcurrentConnectionsAllowed() { return 5; }
		@Override protected int getNumberCurrentConnections() { return 0; }
		@Override protected int incrementAndGetCurrentConcurrentConnections() { return 1; }
		@Override protected void decrementCurrentConcurrentConnections() { released.incrementAndGet(); }
	}

	/**
	 * Records whole frames rather than bytes, and lets a test decide when the connection is willing to
	 * take them - which is the one thing a real socket will not do on demand.
	 */
	private static class FakeOutputStream extends ServletOutputStream {

		volatile boolean ready = true;
		volatile IOException failWith = null;

		private final List<byte[]> frames = new ArrayList<byte[]>();

		@Override public boolean isReady() { return ready; }

		@Override public void setWriteListener(WriteListener writeListener) { }

		@Override
		public void write(byte[] b) throws IOException {
			if (null != failWith)
				throw failWith;
			frames.add(b);
		}

		@Override
		public void write(int b) {
			throw new UnsupportedOperationException("frames are written whole");
		}

		/** The `name` of each frame written, in order. */
		List<String> written() {
			List<String> written = new ArrayList<String>();
			for (byte[] f : frames) {
				String s = new String(f, StandardCharsets.UTF_8);
				written.add(s.substring(s.indexOf("\"name\":\"") + 8, s.lastIndexOf("\"}")));
			}
			return written;
		}
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
