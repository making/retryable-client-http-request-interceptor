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
import java.net.SocketTimeoutException;
import java.net.URLConnection;
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
import org.springframework.util.backoff.BackOff;
import org.springframework.util.backoff.BackOffExecution;

public class RetryableClientHttpRequestInterceptor implements ClientHttpRequestInterceptor {

	private final BackOff backOff;

	private final Set<Integer> retryableResponseStatuses;

	private final boolean retryClientTimeout;

	public static Set<Integer> DEFAULT_RETRYABLE_RESPONSE_STATUSES = Collections
			.unmodifiableSet(new HashSet<>(Arrays.asList(408 /* Request Timeout */,
					425 /* Too Early */, 429 /* Too Many Requests */,
					500 /* Internal Server Error */, 502 /* Bad Gateway */,
					503 /* Service Unavailable */, 504 /* Gateway Timeout */
			)));

	private static final int MAX_ATTEMPTS = 100;

	private final Log log = LogFactory.getLog(RetryableClientHttpRequestInterceptor.class);

	public RetryableClientHttpRequestInterceptor(BackOff backOff) {
		this(backOff, DEFAULT_RETRYABLE_RESPONSE_STATUSES, true);
	}

	public RetryableClientHttpRequestInterceptor(BackOff backOff, Set<Integer> retryableResponseStatuses) {
		this(backOff, retryableResponseStatuses, true);
	}

	public RetryableClientHttpRequestInterceptor(BackOff backOff, Set<Integer> retryableResponseStatuses,
			boolean retryClientTimeout) {
		this.backOff = backOff;
		this.retryableResponseStatuses = retryableResponseStatuses;
		this.retryClientTimeout = retryClientTimeout;
	}

	@Override
	public ClientHttpResponse intercept(HttpRequest request, byte[] body, ClientHttpRequestExecution execution)
			throws IOException {
		final BackOffExecution backOffExecution = this.backOff.start();
		for (int i = 1; i <= MAX_ATTEMPTS; i++) {
			final long backOff = backOffExecution.nextBackOff();
			try {
				if (log.isDebugEnabled()) {
					log.debug(String.format("Request %d: %s %s", i, request.getMethod(), request.getURI()));
					log.debug(String.format("Request %d: %s", i, request.getHeaders()));
				}
				final ClientHttpResponse response = execution.execute(request, body);
				if (log.isDebugEnabled()) {
					log.debug(String.format("Response %d: %s", i, response.getStatusCode()));
					log.debug(String.format("Response %d: %s", i, response.getHeaders()));
				}
				if (!isRetryableHttpStatus(() -> response.getStatusCode().isError(),
						() -> response.getStatusCode().value())) {
					return response;
				}
				if (backOff == BackOffExecution.STOP) {
					log.warn("No longer retryable");
					return response;
				}
			}
			catch (IOException e) {
				if (!isRetryableClientTimeout(e)) {
					throw e;
				}
				else if (backOff == BackOffExecution.STOP) {
					log.warn("No longer retryable", e);
					throw e;
				}
				else {
					log.info(e.getMessage());
				}
			}
			if (log.isInfoEnabled()) {
				log.info(String.format("Wait interval (%s)", backOffExecution));
			}
			try {
				Thread.sleep(backOff);
			}
			catch (InterruptedException ex) {
				Thread.currentThread().interrupt();
			}
		}
		throw new IllegalStateException("Maximum number of attempts reached!");
	}

	/**
	 * @see {@link URLConnection#setConnectTimeout(int)}
	 * @see {@link URLConnection#setReadTimeout(int)}
	 */
	private boolean isRetryableClientTimeout(IOException e) {
		return (e instanceof SocketTimeoutException) && retryClientTimeout;
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
