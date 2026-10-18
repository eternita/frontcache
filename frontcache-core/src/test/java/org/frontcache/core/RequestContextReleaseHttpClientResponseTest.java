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

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.io.IOException;

import org.apache.hc.core5.http.ClassicHttpResponse;
import org.junit.Test;

/**
 * The origin response in bypass-cache mode is opened by a Hystrix worker thread but its body is
 * streamed by the request thread, so the two race to close it - and on a Hystrix TIMEOUT the request
 * thread finishes first. These cover both orderings: the connection must be returned to the pool
 * exactly once, and never while the other thread still needs it.
 */
public class RequestContextReleaseHttpClientResponseTest {

	/**
	 * Normal flow: the worker opens the response and finishes first, the request thread is still
	 * streaming the body - so the request thread does the close.
	 */
	@Test
	public void closedByRequestThreadWhenWorkerFinishesFirst() throws IOException {

		RequestContext context = new RequestContext();
		ClassicHttpResponse response = mock(ClassicHttpResponse.class);

		context.setHttpClientResponse(response); // worker: origin response received
		context.releaseHttpClientResponse();     // worker: run() done

		// the request thread has not finished streaming yet - closing here would truncate the body
		verify(response, never()).close();

		context.releaseHttpClientResponse();     // request thread: writeResponse() done

		verify(response, times(1)).close();
	}

	/**
	 * Hystrix TIMEOUT flow - the leak this guards against: the request thread already served the
	 * fallback and left, then the worker's origin call finally returns. Nobody used to close it.
	 */
	@Test
	public void closedByWorkerWhenRequestThreadTimedOutFirst() throws IOException {

		RequestContext context = new RequestContext();
		ClassicHttpResponse response = mock(ClassicHttpResponse.class);

		context.releaseHttpClientResponse();     // request thread: gave up, served the fallback

		context.setHttpClientResponse(response); // worker: origin response arrives late
		context.releaseHttpClientResponse();     // worker: run() done

		verify(response, times(1)).close();
	}

	/**
	 * The origin call failed or never ran (filter mode, short circuit, aborted in the pool wait) -
	 * both parties still arrive and must not blow up on the missing response.
	 */
	@Test
	public void noResponseOpenedIsSafe() {

		RequestContext context = new RequestContext();

		context.releaseHttpClientResponse();
		context.releaseHttpClientResponse();
	}

	/**
	 * A failure to close must not be swallowed into a broken state or rethrown at the caller - the
	 * request thread calls this from a finally block.
	 */
	@Test
	public void closeFailureIsContained() throws IOException {

		RequestContext context = new RequestContext();
		ClassicHttpResponse response = mock(ClassicHttpResponse.class);
		org.mockito.Mockito.doThrow(new IOException("connection already gone")).when(response).close();

		context.setHttpClientResponse(response);
		context.releaseHttpClientResponse();
		context.releaseHttpClientResponse();

		verify(response, times(1)).close();
	}

	/**
	 * Each request is an independent exchange - a context copy (used by soft invalidation) must not
	 * inherit a half-finished handshake, or it would close a response it never opened.
	 */
	@Test
	public void copyDoesNotInheritHandshakeState() throws IOException {

		RequestContext context = new RequestContext();
		ClassicHttpResponse response = mock(ClassicHttpResponse.class);

		context.setHttpClientResponse(response);
		context.releaseHttpClientResponse(); // one party arrived on the original

		RequestContext copy = context.copy();
		copy.releaseHttpClientResponse();    // first party on the copy - must not close

		verify(response, never()).close();
	}
}
