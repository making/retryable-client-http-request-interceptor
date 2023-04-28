/*
 * Copyright (C) 2023 Toshiaki Maki <makingx@gmail.com>
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package am.ik.spring.http.client;

import java.io.OutputStream;
import java.io.PrintWriter;
import java.net.InetSocketAddress;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import com.sun.net.httpserver.HttpServer;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

public class MockServerRunner {

	static int port = 9999;

	private HttpServer httpServer;

	private final Log log = LogFactory.getLog(MockServerRunner.class);

	public void run() throws Exception {
		this.httpServer = HttpServer.create(new InetSocketAddress(port), 0);
		this.httpServer.setExecutor(Executors.newSingleThreadExecutor());
		final AtomicInteger counter = new AtomicInteger(0);
		this.httpServer.createContext("/hello", exchange -> {
			log.info(exchange.getRequestURI().toString());
			try (final OutputStream stream = exchange.getResponseBody()) {
				final PrintWriter printWriter = new PrintWriter(stream);
				final boolean success = counter.getAndIncrement() % 3 == 2;
				final String body = success ? "Hello World!" : "Oops!";
				exchange.sendResponseHeaders(success ? 200 : 503, body.length());
				printWriter.write(body);
				printWriter.flush();
			}
		});
		log.info("Start http server on " + port);
		this.httpServer.start();
	}

	public void destroy() throws Exception {
		log.info("Stop http server");
		if (this.httpServer != null) {
			this.httpServer.stop(0);
			this.httpServer = null;
		}
	}

}
