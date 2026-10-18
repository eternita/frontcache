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
package org.frontcache.core;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Random;

import org.junit.Test;

/**
 * {@link FCUtils#writeResponse(InputStream, OutputStream)} - the loop that streams a bypassed origin
 * body to the client.
 *
 * The version this replaced had three defects, and two of them are only visible as behaviour rather
 * than as a number, so they are pinned here:
 *
 *  - it called {@code flush()} after every chunk, turning a large download into thousands of write
 *    syscalls and chunked-transfer boundaries;
 *  - its buffer started at 1 KB and DOUBLED on every full read, without a ceiling;
 *  - it caught IOException INSIDE the loop and printed it, so a client disconnect was swallowed and
 *    the whole origin body was pulled into a socket nobody was reading.
 */
public class WriteResponseStreamTest {

	/** Records what the stream is actually asked to do, not just what came out of it. */
	private static final class RecordingOutputStream extends OutputStream {
		final ByteArrayOutputStream written = new ByteArrayOutputStream();
		int writeCalls = 0;
		int flushCalls = 0;
		int closeCalls = 0;

		@Override
		public void write(int b) {
			writeCalls++;
			written.write(b);
		}

		@Override
		public void write(byte[] b, int off, int len) {
			writeCalls++;
			written.write(b, off, len);
		}

		@Override
		public void flush() {
			flushCalls++;
		}

		@Override
		public void close() {
			closeCalls++;
		}
	}

	private static byte[] payload(int size) {
		byte[] data = new byte[size];
		new Random(42).nextBytes(data);
		return data;
	}

	@Test
	public void copiesTheBodyExactly() throws Exception {
		for (int size : new int[] { 0, 1, 1023, 16 * 1024, 16 * 1024 + 1, 1_000_000 }) {
			byte[] data = payload(size);
			RecordingOutputStream out = new RecordingOutputStream();

			FCUtils.writeResponse(new ByteArrayInputStream(data), out);

			assertArrayEquals("body of " + size + " bytes must arrive byte-for-byte", data, out.written.toByteArray());
		}
	}

	/**
	 * The flush-per-chunk fix. A 1 MB body used to mean ~1000 flushes (and a growing chain of
	 * discarded buffers); now the writes are bounded by the buffer size and the flush is the caller's,
	 * done once in its finally block.
	 */
	@Test
	public void doesNotFlushOrCloseAndWritesInBoundedChunks() throws Exception {
		int size = 1_000_000;
		RecordingOutputStream out = new RecordingOutputStream();

		FCUtils.writeResponse(new ByteArrayInputStream(payload(size)), out);

		assertEquals("flushing is the caller's job - it flushes once in its finally", 0, out.flushCalls);
		assertEquals("closing is the caller's job too", 0, out.closeCalls);
		assertTrue("a 1 MB body should take on the order of 60 writes, not 1000: " + out.writeCalls,
				out.writeCalls <= 128);
	}

	/**
	 * A broken pipe must end the transfer. The old loop swallowed it and kept reading the whole origin
	 * body into a dead socket, printing a stack trace per kilobyte; the callers' finally blocks are
	 * what release the pooled origin connection, and they only run if this propagates.
	 */
	@Test
	public void aFailedWritePropagatesInsteadOfBeingSwallowed() throws Exception {
		final int[] readsBeforeFailure = { 0 };

		InputStream endless = new InputStream() {
			@Override
			public int read() {
				return 0;
			}
			@Override
			public int read(byte[] b, int off, int len) {
				readsBeforeFailure[0]++;
				return len; // never ends - the test is that we stop anyway
			}
		};

		OutputStream brokenPipe = new OutputStream() {
			@Override
			public void write(int b) throws IOException {
				throw new IOException("Broken pipe");
			}
			@Override
			public void write(byte[] b, int off, int len) throws IOException {
				throw new IOException("Broken pipe");
			}
		};

		try {
			FCUtils.writeResponse(endless, brokenPipe);
			fail("a broken pipe must propagate, not be swallowed inside the copy loop");
		} catch (IOException expected) {
			assertEquals("Broken pipe", expected.getMessage());
		}

		assertEquals("the copy must stop at the first failed write, not drain the origin",
				1, readsBeforeFailure[0]);
	}
}
