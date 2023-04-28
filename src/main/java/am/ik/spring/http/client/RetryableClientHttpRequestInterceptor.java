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

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.SocketException;
import java.time.Duration;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;

public class RetryableClientHttpRequestInterceptor implements ClientHttpRequestInterceptor {

	private final int retryMaxCount;

	private final Duration retryWaitTime;

	private final Set<Integer> retryableResponseStatuses;

	private final boolean retryClientTimeout;

	private final Log log = LogFactory.getLog(RetryableClientHttpRequestInterceptor.class);

	public RetryableClientHttpRequestInterceptor() {
		this(2);
	}

	public RetryableClientHttpRequestInterceptor(int retryMaxCount) {
		this(retryMaxCount, Duration.ofSeconds(2));
	}

	public RetryableClientHttpRequestInterceptor(int retryMaxCount, Duration retryWaitTime) {
		this(retryMaxCount, retryWaitTime,
				Collections
					.unmodifiableSet(new HashSet<>(Arrays.asList(408 /* Request Timeout */,
							425 /* Too Early */, 429 /* Too Many Requests */,
							500 /* Internal Server Error */, 502 /* Bad Gateway */,
							503 /* Service Unavailable */, 504 /* Gateway Timeout */
					))));
	}

	public RetryableClientHttpRequestInterceptor(int retryMaxCount, Duration retryWaitTime,
			Set<Integer> retryableResponseStatuses) {
		this(retryMaxCount, retryWaitTime, retryableResponseStatuses, true);
	}

	public RetryableClientHttpRequestInterceptor(int retryMaxCount, Duration retryWaitTime,
			Set<Integer> retryableResponseStatuses, boolean retryClientTimeout) {
		this.retryMaxCount = retryMaxCount;
		this.retryWaitTime = retryWaitTime;
		this.retryableResponseStatuses = retryableResponseStatuses;
		this.retryClientTimeout = retryClientTimeout;
	}

	@Override
	public ClientHttpResponse intercept(HttpRequest request, byte[] body, ClientHttpRequestExecution execution)
			throws IOException {
		for (int retryCount = 0; retryCount <= retryMaxCount; retryCount++) {
			try {
				if (log.isDebugEnabled()) {
					log.debug(String.format("Request %d: %s %s", retryCount, request.getMethod(), request.getURI()));
					log.debug(String.format("Request %d: %s", retryCount, request.getHeaders()));
				}
				final ClientHttpResponse response = execution.execute(request, body);
				if (log.isDebugEnabled()) {
					log.debug(String.format("Response %d: %s", retryCount, response.getStatusCode()));
					log.debug(String.format("Response %d: %s", retryCount, response.getHeaders()));
				}
				if (!isRetryableHttpStatus(() -> response.getStatusCode().isError(),
						() -> response.getStatusCode().value())) {
					return response;
				}
				if (retryCount == retryMaxCount) {
					log.warn("No longer retryable");
					return response;
				}
			}
			catch (IOException e) {
				if (!isRetryableClientTimeout(e)) {
					throw e;
				}
				else if (retryCount == retryMaxCount) {
					log.warn("No longer retryable", e);
					throw e;
				}
				else {
					log.info(e.getMessage());
				}
			}
			if (log.isInfoEnabled()) {
				log.info(String.format("Wait %dms for %d/%d retries...", retryWaitTime.toMillis(), retryCount + 1,
						retryMaxCount));
			}
			try {
				Thread.sleep(retryWaitTime.toMillis());
			}
			catch (InterruptedException ex) {
				Thread.currentThread().interrupt();
			}
		}
		throw new IllegalStateException("should not come here!");
	}

	/**
	 * @see {@link HttpURLConnection#setConnectTimeout(int)}
	 * @see {@link HttpURLConnection#setReadTimeout(int)}
	 */
	private boolean isRetryableClientTimeout(IOException e) {
		return (e instanceof SocketException) && retryClientTimeout;
	}

	private boolean isRetryableHttpStatus(ErrorSupplier errorSupplier, StatusSupplier statusSupplier)
			throws IOException {
		return errorSupplier.isError() && retryableResponseStatuses.contains(statusSupplier.getStatus());
	}

	// to work with both Spring 5 (HttpStatus) and 6 (HttpStatusCode)
	private interface ErrorSupplier {

		boolean isError() throws IOException;

	}

	private interface StatusSupplier {

		int getStatus() throws IOException;

	}

}
