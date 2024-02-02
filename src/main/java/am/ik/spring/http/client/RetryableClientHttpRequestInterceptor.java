/*
 * Copyright (C) 2023-2024 Toshiaki Maki <makingx@gmail.com>
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
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.URLConnection;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import am.ik.spring.http.client.RetryLifecycle.ResponseOrException;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import org.springframework.http.HttpHeaders;
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

	private final boolean retryConnectException;

	private final boolean retryUnknownHostException;

	private final LoadBalanceStrategy loadBalanceStrategy;

	private final RetryLifecycle retryLifecycle;

	private final Set<String> sensitiveHeaders;

	public static Set<Integer> DEFAULT_RETRYABLE_RESPONSE_STATUSES = Collections
		.unmodifiableSet(new HashSet<>(Arrays.asList( //
				408 /* Request Timeout */, //
				425 /* Too Early */, //
				429 /* Too Many Requests */, //
				500 /* Internal Server Error */, //
				502 /* Bad Gateway */, //
				503 /* Service Unavailable */, //
				504 /* Gateway Timeout */
		)));

	private static final int MAX_ATTEMPTS = 100;

	private final Log log = LogFactory.getLog(RetryableClientHttpRequestInterceptor.class);

	public static class Options {

		private boolean retryClientTimeout = true;

		private boolean retryConnectException = true;

		private boolean retryUnknownHostException = true;

		private LoadBalanceStrategy loadBalanceStrategy = LoadBalanceStrategy.NOOP;

		private RetryLifecycle retryLifecycle = RetryLifecycle.NOOP;

		private Set<String> sensitiveHeaders = Collections.unmodifiableSet(new HashSet<>(Arrays.asList( //
				HttpHeaders.AUTHORIZATION.toLowerCase(), //
				HttpHeaders.PROXY_AUTHENTICATE.toLowerCase(), //
				HttpHeaders.COOKIE.toLowerCase(), //
				"x-amz-security-token")));

		public Options retryClientTimeout(boolean retryClientTimeout) {
			this.retryClientTimeout = retryClientTimeout;
			return this;
		}

		public Options retryConnectException(boolean retryConnectException) {
			this.retryConnectException = retryConnectException;
			return this;
		}

		public Options retryUnknownHostException(boolean retryUnknownHostException) {
			this.retryUnknownHostException = retryUnknownHostException;
			return this;
		}

		public Options loadBalanceStrategy(LoadBalanceStrategy loadBalanceStrategy) {
			this.loadBalanceStrategy = loadBalanceStrategy;
			if (loadBalanceStrategy instanceof RetryLifecycle) {
				return this.retryLifecycle((RetryLifecycle) loadBalanceStrategy);
			}
			return this;
		}

		public Options retryLifecycle(RetryLifecycle retryLifecycle) {
			this.retryLifecycle = retryLifecycle;
			return this;
		}

		public Options sensitiveHeaders(Set<String> sensitiveHeaders) {
			this.sensitiveHeaders = sensitiveHeaders;
			return this;
		}

	}

	public RetryableClientHttpRequestInterceptor(BackOff backOff) {
		this(backOff, DEFAULT_RETRYABLE_RESPONSE_STATUSES, __ -> {
		});
	}

	public RetryableClientHttpRequestInterceptor(BackOff backOff, Set<Integer> retryableResponseStatuses) {
		this(backOff, retryableResponseStatuses, __ -> {
		});
	}

	public RetryableClientHttpRequestInterceptor(BackOff backOff, Consumer<Options> configurer) {
		this(backOff, DEFAULT_RETRYABLE_RESPONSE_STATUSES, configurer);
	}

	public RetryableClientHttpRequestInterceptor(BackOff backOff, Set<Integer> retryableResponseStatuses,
			Consumer<Options> configurer) {
		Options options = new Options();
		configurer.accept(options);
		this.backOff = backOff;
		this.retryableResponseStatuses = retryableResponseStatuses;
		this.retryClientTimeout = options.retryClientTimeout;
		this.retryConnectException = options.retryConnectException;
		this.retryUnknownHostException = options.retryUnknownHostException;
		this.loadBalanceStrategy = options.loadBalanceStrategy;
		this.retryLifecycle = options.retryLifecycle;
		this.sensitiveHeaders = options.sensitiveHeaders;
	}

	@Override
	public ClientHttpResponse intercept(HttpRequest request, byte[] body, ClientHttpRequestExecution execution)
			throws IOException {
		final BackOffExecution backOffExecution = this.backOff.start();
		for (int i = 1; i <= MAX_ATTEMPTS; i++) {
			final long backOff = backOffExecution.nextBackOff();
			final HttpRequest httpRequest = this.loadBalanceStrategy.choose(request);
			try {
				if (log.isDebugEnabled()) {
					log.debug(String.format("Request %d: %s %s", i, httpRequest.getMethod(), httpRequest.getURI()));
					log.debug(String.format("Request %d: %s", i, maskHeaders(httpRequest.getHeaders())));
				}
				final ClientHttpResponse response = execution.execute(httpRequest, body);
				if (log.isDebugEnabled()) {
					log.debug(String.format("Response %d: %s", i, response.getStatusCode()));
					log.debug(String.format("Response %d: %s", i, maskHeaders(response.getHeaders())));
				}
				ErrorSupplier errorSupplier = () -> response.getStatusCode().isError();
				if (!isRetryableHttpStatus(errorSupplier, () -> response.getStatusCode().value())) {
					if (errorSupplier.isError()) {
						this.retryLifecycle.onFailure(httpRequest, ResponseOrException.ofResponse(response));
					}
					else {
						this.retryLifecycle.onSuccess(httpRequest, response);
					}
					return response;
				}
				if (backOff == BackOffExecution.STOP) {
					log.warn("No longer retryable");
					this.retryLifecycle.onNoLongerRetryable(httpRequest, ResponseOrException.ofResponse(response));
					return response;
				}
				this.retryLifecycle.onRetry(httpRequest, ResponseOrException.ofResponse(response));
			}
			catch (IOException e) {
				if (!isRetryableIOException(e)) {
					this.retryLifecycle.onFailure(httpRequest, ResponseOrException.ofException(e));
					throw e;
				}
				else if (backOff == BackOffExecution.STOP) {
					log.warn("No longer retryable", e);
					this.retryLifecycle.onNoLongerRetryable(httpRequest, ResponseOrException.ofException(e));
					throw e;
				}
				else {
					this.retryLifecycle.onRetry(httpRequest, ResponseOrException.ofException(e));
					log.info(e.getClass().getName() + "\t" + e.getMessage());
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

	private boolean isRetryableIOException(IOException e) {
		return isRetryableClientTimeout(e) || isRetryableConnectException(e) || isRetryableUnknownHostException(e);
	}

	/**
	 * @see {@link URLConnection#setConnectTimeout(int)}
	 * @see {@link URLConnection#setReadTimeout(int)}
	 */
	private boolean isRetryableClientTimeout(IOException e) {
		return (e instanceof SocketTimeoutException) && this.retryClientTimeout;
	}

	private boolean isRetryableConnectException(IOException e) {
		return (e instanceof ConnectException) && this.retryConnectException;
	}

	private boolean isRetryableUnknownHostException(IOException e) {
		return (e instanceof UnknownHostException) && this.retryUnknownHostException;
	}

	private boolean isRetryableHttpStatus(ErrorSupplier errorSupplier, StatusSupplier statusSupplier)
			throws IOException {
		return errorSupplier.isError() && this.retryableResponseStatuses.contains(statusSupplier.getStatus());
	}

	// to work with both Spring 5 (HttpStatus) and 6 (HttpStatusCode)
	private interface ErrorSupplier {

		boolean isError() throws IOException;

	}

	private interface StatusSupplier {

		int getStatus() throws IOException;

	}

	private Map<String, List<String>> maskHeaders(HttpHeaders headers) {
		return headers.entrySet().stream().map(entry -> {
			if (sensitiveHeaders.contains(entry.getKey().toLowerCase())) {
				return new Map.Entry<String, List<String>>() {
					@Override
					public String getKey() {
						return entry.getKey();
					}

					@Override
					public List<String> getValue() {
						return entry.getValue().stream().map(s -> "(masked)").collect(Collectors.toList());
					}

					@Override
					public List<String> setValue(List<String> value) {
						return value;
					}
				};
			}
			else {
				return entry;
			}
		}).collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
	}

}
