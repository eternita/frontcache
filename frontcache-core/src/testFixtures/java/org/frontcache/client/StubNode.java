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
package org.frontcache.client;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * A single-threaded HTTP/1.1 stub that hands out one canned body per request, in order, and records
 * the {@code action} form parameter of each. Deliberately tiny: it exists to observe which actions
 * the client sends, which no assertion against a real node can show.
 */
final class StubNode {

	private final ServerSocket serverSocket;
	private final List<String> actions = Collections.synchronizedList(new ArrayList<String>());
	private final CountDownLatch stopped = new CountDownLatch(1);

	private volatile String[] bodies = new String[0];
	private volatile boolean running = true;
	private Thread thread;

	StubNode() throws IOException
	{
		serverSocket = new ServerSocket(0);
	}

	void answerWith(String... responseBodies)
	{
		bodies = responseBodies;
	}

	String url()
	{
		return "http://127.0.0.1:" + serverSocket.getLocalPort();
	}

	List<String> actionsSeen()
	{
		return new ArrayList<String>(actions);
	}

	void start()
	{
		thread = new Thread(new Runnable() {
			@Override
			public void run() {
				int served = 0;
				try {
					while (running)
					{
						Socket socket = serverSocket.accept();
						try {
							readRequestAndRecordAction(socket);
							String responseBody = (served < bodies.length) ? bodies[served] : "{}";
							served++;
							write(socket, responseBody);
						} finally {
							socket.close();
						}
					}
				} catch (IOException closed) {
					// the socket was closed by stop() - that is how this loop ends
				} finally {
					stopped.countDown();
				}
			}
		}, "stub-node");
		thread.setDaemon(true);
		thread.start();
	}

	private void readRequestAndRecordAction(Socket socket) throws IOException
	{
		BufferedReader reader = new BufferedReader(
				new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));

		int contentLength = 0;
		String line;
		while (null != (line = reader.readLine()) && 0 != line.length())
			if (line.toLowerCase().startsWith("content-length:"))
				contentLength = Integer.parseInt(line.substring("content-length:".length()).trim());

		char[] body = new char[contentLength];
		int read = 0;
		while (read < contentLength)
		{
			int n = reader.read(body, read, contentLength - read);
			if (-1 == n)
				break;
			read += n;
		}

		for (String pair : new String(body, 0, read).split("&"))
			if (pair.startsWith("action="))
				actions.add(pair.substring("action=".length()));
	}

	private void write(Socket socket, String body) throws IOException
	{
		byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
		OutputStream out = socket.getOutputStream();
		out.write(("HTTP/1.1 200 OK\r\n"
				+ "Content-Type: application/json\r\n"
				+ "Content-Length: " + bytes.length + "\r\n"
				+ "Connection: close\r\n\r\n").getBytes(StandardCharsets.UTF_8));
		out.write(bytes);
		out.flush();
	}

	void stop()
	{
		running = false;
		try {
			serverSocket.close();
		} catch (IOException ignored) { }

		try {
			stopped.await(5, TimeUnit.SECONDS);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}
}
