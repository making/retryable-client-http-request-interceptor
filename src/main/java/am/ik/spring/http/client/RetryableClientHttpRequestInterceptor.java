/*
 * Copyright (C) 2023-2025 Toshiaki Maki <makingx@gmail.com>
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

import am.ik.spring.http.client.RetryLifecycle.ResponseOrException;
import java.io.IOException;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.util.backoff.BackOff;
import org.springframework.util.backoff.BackOffExecution;

/**
 * Interceptor for retrying HTTP requests with customizable backoff strategies, retryable
 * conditions, and load-balancing support.
 */
public class RetryableClientHttpRequestInterceptor implements ClientHttpRequestInterceptor {

	/**
	 * Backoff policy to determine retry intervals.
	 */
	private final BackOff backOff;

	/**
	 * Set of HTTP status codes that are considered retryable.
	 */
	private final Set<Integer> retryableResponseStatuses;

	/**
	 * Predicate to determine if an IOException is retryable.
	 */
	private final Predicate<IOException> retryableIOExceptionPredicate;

	/**
	 * Custom predicate to determine if an HTTP response is retryable. Exclusive of
	 * {@link #retryableResponseStatuses}.
	 */
	private final RetryableHttpResponsePredicate retryableHttpResponsePredicate;

	/**
	 * Strategy to apply load balancing across requests.
	 */
	private final LoadBalanceStrategy loadBalanceStrategy;

	/**
	 * Lifecycle hooks for retry operations.
	 */
	private final RetryLifecycle retryLifecycle;

	/**
	 * Predicate to identify sensitive HTTP headers for masking in logs.
	 */
	private final Predicate<String> sensitiveHeaderPredicate;

	/**
	 * Default retryable HTTP status codes.
	 */
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

	/**
	 * Configuration options for customizing retry behavior, load balancing, and header
	 * sensitivity.
	 */
	public static class Options {

		/**
		 * Predicates to determine if an IOException is retryable.
		 */
		private final Set<Predicate<IOException>> retryableIOExceptionPredicates = new LinkedHashSet<Predicate<IOException>>(
				RetryableIOExceptionPredicate.defaults());

		/**
		 * Predicate for retryable HTTP responses.
		 */
		private RetryableHttpResponsePredicate retryableHttpResponsePredicate = null;

		/**
		 * Strategy for load balancing.
		 */
		private LoadBalanceStrategy loadBalanceStrategy = LoadBalanceStrategy.NOOP;

		/**
		 * Lifecycle hooks for retry events.
		 */
		private RetryLifecycle retryLifecycle = RetryLifecycle.NOOP;

		/**
		 * Default sensitive headers.
		 */
		public static final Set<String> DEFAULT_SENSITIVE_HEADERS = Collections.unmodifiableSet(new HashSet<>(
				Arrays.asList("authorization", "proxy-authenticate", "cookie", "set-cookie", "x-amz-security-token")));

		/**
		 * Set of headers considered sensitive and masked in logs.
		 */
		private Set<String> sensitiveHeaders = DEFAULT_SENSITIVE_HEADERS;

		/**
		 * Predicate for sensitive headers.
		 */
		private Predicate<String> sensitiveHeaderPredicate = null;

		/**
		 * Consider using
		 * {@code addRetryableIOException(RetryableIOExceptionPredicate.CLIENT_TIMEOUT)}
		 * or
		 * {@code removeRetryableIOException(RetryableIOExceptionPredicate.CLIENT_TIMEOUT)}
		 */
		@Deprecated
		public Options retryClientTimeout(boolean retryClientTimeout) {
			if (retryClientTimeout) {
				return this.addRetryableIOException(RetryableIOExceptionPredicate.CLIENT_TIMEOUT);
			}
			else {
				return this.removeRetryableIOException(RetryableIOExceptionPredicate.CLIENT_TIMEOUT);
			}
		}

		/**
		 * Consider using
		 * {@code addRetryableIOException(RetryableIOExceptionPredicate.CONNECT_TIMEOUT)}
		 * or
		 * {@code removeRetryableIOException(RetryableIOExceptionPredicate.CONNECT_TIMEOUT)}
		 */
		@Deprecated
		public Options retryConnectException(boolean retryConnectException) {
			if (retryConnectException) {
				return this.addRetryableIOException(RetryableIOExceptionPredicate.CONNECT_TIMEOUT);
			}
			else {
				return this.removeRetryableIOException(RetryableIOExceptionPredicate.CONNECT_TIMEOUT);
			}
		}

		/**
		 * Consider using
		 * {@code addRetryableIOException(RetryableIOExceptionPredicate.UNKNOWN_HOST)} or
		 * {@code removeRetryableIOException(RetryableIOExceptionPredicate.UNKNOWN_HOST)}
		 */
		@Deprecated
		public Options retryUnknownHostException(boolean retryUnknownHostException) {
			if (retryUnknownHostException) {
				return this.addRetryableIOException(RetryableIOExceptionPredicate.UNKNOWN_HOST);
			}
			else {
				return this.removeRetryableIOException(RetryableIOExceptionPredicate.UNKNOWN_HOST);
			}
		}

		/**
		 * Adds a predicate for determining retryable IOExceptions.
		 * @param retryableIOExceptionPredicate the predicate to add
		 * @return the updated options
		 */
		public Options addRetryableIOException(Predicate<IOException> retryableIOExceptionPredicate) {
			this.retryableIOExceptionPredicates.add(retryableIOExceptionPredicate);
			return this;
		}

		public Options addRetryableIOExceptions(
				Collection<? extends Predicate<IOException>> retryableIOExceptionPredicates) {
			this.retryableIOExceptionPredicates.addAll(retryableIOExceptionPredicates);
			return this;
		}

		public Options addRetryableIOExceptions(Predicate<IOException>... retryableIOExceptionPredicates) {
			this.addRetryableIOExceptions(Arrays.asList(retryableIOExceptionPredicates));
			return this;
		}

		/**
		 * Removes a predicate for determining retryable IOExceptions.
		 * @param retryableIOExceptionPredicate the predicate to remove
		 * @return the updated options
		 */
		public Options removeRetryableIOException(Predicate<IOException> retryableIOExceptionPredicate) {
			this.retryableIOExceptionPredicates.remove(retryableIOExceptionPredicate);
			return this;
		}

		public Options removeRetryableIOExceptions(
				Collection<? extends Predicate<IOException>> retryableIOExceptionPredicate) {
			this.retryableIOExceptionPredicates.removeAll(retryableIOExceptionPredicate);
			return this;
		}

		public Options removeRetryableIOExceptions(Predicate<IOException>... retryableIOExceptionPredicate) {
			this.removeRetryableIOExceptions(Arrays.asList(retryableIOExceptionPredicate));
			return this;
		}

		/**
		 * Sets a custom <code>RetryableHttpResponsePredicate</code>. If this is set,
		 * {@link RetryableClientHttpRequestInterceptor#retryableResponseStatuses} is no
		 * longer respected.
		 * @param retryableHttpResponsePredicate the predicate to set
		 * @return the updated options
		 */
		public Options retryableHttpResponsePredicate(RetryableHttpResponsePredicate retryableHttpResponsePredicate) {
			this.retryableHttpResponsePredicate = retryableHttpResponsePredicate;
			return this;
		}

		/**
		 * Configures the load balancing strategy.
		 * @param loadBalanceStrategy the load balancing strategy to use
		 * @return the updated options
		 */
		public Options loadBalanceStrategy(LoadBalanceStrategy loadBalanceStrategy) {
			this.loadBalanceStrategy = loadBalanceStrategy;
			if (loadBalanceStrategy instanceof RetryLifecycle) {
				return this.retryLifecycle((RetryLifecycle) loadBalanceStrategy);
			}
			return this;
		}

		/**
		 * Sets lifecycle hooks for retry operations.
		 * @param retryLifecycle the retry lifecycle to use
		 * @return the updated options
		 */
		public Options retryLifecycle(RetryLifecycle retryLifecycle) {
			this.retryLifecycle = retryLifecycle;
			return this;
		}

		/**
		 * Sets the sensitive headers to be masked in logs.
		 * @param sensitiveHeaders the set of sensitive headers
		 * @return the updated options
		 */
		public Options sensitiveHeaders(Set<String> sensitiveHeaders) {
			this.sensitiveHeaders = sensitiveHeaders;
			return this;
		}

		/**
		 * Sets a custom predicate for identifying sensitive headers.
		 * @param sensitiveHeaderPredicate the predicate to set
		 * @return the updated options
		 */
		public Options sensitiveHeaderPredicate(Predicate<String> sensitiveHeaderPredicate) {
			this.sensitiveHeaderPredicate = sensitiveHeaderPredicate;
			return this;
		}

	}

	/**
	 * Constructs an interceptor with a backoff policy and default retryable response
	 * statuses.
	 * @param backOff the backoff policy
	 */
	public RetryableClientHttpRequestInterceptor(BackOff backOff) {
		this(backOff, DEFAULT_RETRYABLE_RESPONSE_STATUSES, __ -> {
		});
	}

	/**
	 * Constructs an interceptor with a backoff policy and specified retryable response
	 * statuses.
	 * @param backOff the backoff policy
	 * @param retryableResponseStatuses the set of retryable HTTP status codes
	 */
	public RetryableClientHttpRequestInterceptor(BackOff backOff, Set<Integer> retryableResponseStatuses) {
		this(backOff, retryableResponseStatuses, __ -> {
		});
	}

	/**
	 * Constructs an interceptor with a backoff policy and a custom configuration.
	 * @param backOff the backoff policy
	 * @param configurer a consumer to configure additional options
	 */
	public RetryableClientHttpRequestInterceptor(BackOff backOff, Consumer<Options> configurer) {
		this(backOff, DEFAULT_RETRYABLE_RESPONSE_STATUSES, configurer);
	}

	/**
	 * Constructs an interceptor with a backoff policy, retryable response statuses, and
	 * custom configuration.
	 * @param backOff the backoff policy
	 * @param retryableResponseStatuses the set of retryable HTTP status codes
	 * @param configurer a consumer to configure additional options
	 */
	public RetryableClientHttpRequestInterceptor(BackOff backOff, Set<Integer> retryableResponseStatuses,
			Consumer<Options> configurer) {
		Options options = new Options();
		configurer.accept(options);
		this.backOff = backOff;
		this.retryableResponseStatuses = retryableResponseStatuses;
		this.retryableIOExceptionPredicate = options.retryableIOExceptionPredicates.stream()
			.reduce(e -> false, Predicate::or);
		this.retryableHttpResponsePredicate = options.retryableHttpResponsePredicate == null
				? new DefaultRetryableHttpResponsePredicate() : options.retryableHttpResponsePredicate;
		this.loadBalanceStrategy = options.loadBalanceStrategy;
		this.retryLifecycle = options.retryLifecycle;
		this.sensitiveHeaderPredicate = options.sensitiveHeaderPredicate == null ? options.sensitiveHeaders::contains
				: options.sensitiveHeaderPredicate;
	}

	/**
	 * Intercepts the HTTP request and retries based on configured retry policies.
	 * @param request the HTTP request
	 * @param body the request body
	 * @param execution the request execution
	 * @return the HTTP response
	 * @throws IOException if an I/O error occurs during execution
	 */
	@Override
	public ClientHttpResponse intercept(HttpRequest request, byte[] body, ClientHttpRequestExecution execution)
			throws IOException {
		final BackOffExecution backOffExecution = this.backOff.start();
		for (int i = 1; i <= MAX_ATTEMPTS; i++) {
			final long backOff = backOffExecution.nextBackOff();
			final HttpRequest httpRequest = this.loadBalanceStrategy.choose(request);
			try {
				final long begin = System.currentTimeMillis();
				if (log.isDebugEnabled()) {
					StringBuilder message = new StringBuilder("type=req attempts=").append(i)
						.append(" method=")
						.append(httpRequest.getMethod())
						.append(" url=\"")
						.append(httpRequest.getURI())
						.append("\" ");
					maskHeaders(httpRequest.getHeaders())
						.forEach((k, v) -> message.append(k.toLowerCase(Locale.US).replace("-", "_"))
							.append("=\"")
							.append(v.stream()
								.map(RetryableClientHttpRequestInterceptor::escape)
								.collect(Collectors.joining(",")))
							.append("\" "));
					log.debug(message.toString().trim());
				}
				ClientHttpResponse response = execution.execute(httpRequest, body);
				if (log.isDebugEnabled()) {
					long duration = System.currentTimeMillis() - begin;
					StringBuilder message = new StringBuilder("type=res attempts=").append(i)
						.append(" method=")
						.append(httpRequest.getMethod())
						.append(" url=\"")
						.append(httpRequest.getURI())
						.append("\" response_code=")
						.append(response.getStatusCode().value())
						.append(" duration=")
						.append(duration)
						.append(" ");
					maskHeaders(response.getHeaders())
						.forEach((k, v) -> message.append(k.toLowerCase(Locale.US).replace("-", "_"))
							.append("=\"")
							.append(v.stream()
								.map(RetryableClientHttpRequestInterceptor::escape)
								.collect(Collectors.joining(",")))
							.append("\" "));
					log.debug(message.toString().trim());
				}
				if (!this.retryableHttpResponsePredicate.isRetryableHttpResponse(response)) {
					if (response.getStatusCode().isError()) {
						this.retryLifecycle.onFailure(httpRequest, ResponseOrException.ofResponse(response));
					}
					else {
						this.retryLifecycle.onSuccess(httpRequest, response);
					}
					return response;
				}
				if (backOff == BackOffExecution.STOP) {
					if (log.isWarnEnabled()) {
						log.warn(String.format(
								"type=fin attempts=%d method=%s url=\"%s\" reason=\"No longer retryable\"", i,
								httpRequest.getMethod(), httpRequest.getURI()));
					}
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
					if (log.isWarnEnabled()) {
						log.warn(String.format(
								"type=fin attempts=%d method=%s url=\"%s\" reason=\"No longer retryable\"", i,
								httpRequest.getMethod(), httpRequest.getURI()), e);
					}
					this.retryLifecycle.onNoLongerRetryable(httpRequest, ResponseOrException.ofException(e));
					throw e;
				}
				else {
					this.retryLifecycle.onRetry(httpRequest, ResponseOrException.ofException(e));
					if (log.isInfoEnabled()) {
						log.info(String.format(
								"type=exp attempts=%d method=%s url=\"%s\" exception_class=\"%s\" exception_message=\"%s\"",
								i, httpRequest.getMethod(), httpRequest.getURI(), e.getClass().getName(),
								e.getMessage()));
					}
				}
			}
			if (log.isInfoEnabled()) {
				log.info(String.format("type=wtg attempts=%d method=%s url=\"%s\" backoff=\"%s\"", i,
						httpRequest.getMethod(), httpRequest.getURI(), backOffExecution));
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
	 * Determines if the provided IOException is retryable based on the configured
	 * predicate.
	 * @param e the IOException
	 * @return true if the exception is retryable, false otherwise
	 */
	private boolean isRetryableIOException(IOException e) {
		return this.retryableIOExceptionPredicate.test(e);
	}

	/**
	 * Default implementation of {@link RetryableHttpResponsePredicate} that checks if the
	 * HTTP response status is retryable.
	 */
	private class DefaultRetryableHttpResponsePredicate implements RetryableHttpResponsePredicate {

		@Override
		public boolean isRetryableHttpResponse(ClientHttpResponse response) throws IOException {
			// to work with both Spring 5 (HttpStatus) and 6 (HttpStatusCode)
			return isRetryableHttpStatus(response.getStatusCode().isError(), response.getStatusCode().value());
		}

	}

	/**
	 * Checks if an HTTP status code is retryable based on the error state and configured
	 * retryable statuses.
	 * @param isErrorStatus whether the status code represents an error
	 * @param statusCode the HTTP status code
	 * @return true if the status code is retryable, false otherwise
	 */
	private boolean isRetryableHttpStatus(boolean isErrorStatus, int statusCode) throws IOException {
		return isErrorStatus && this.retryableResponseStatuses.contains(statusCode);
	}

	/**
	 * Masks sensitive HTTP headers based on the configured predicate.
	 * @param headers the HTTP headers
	 * @return a map of headers with sensitive values masked
	 */
	private Map<String, List<String>> maskHeaders(HttpHeaders headers) {
		return headers.entrySet().stream().map(entry -> {
			if (sensitiveHeaderPredicate.test(entry.getKey().toLowerCase())) {
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

	/**
	 * Escapes special characters in a string to ensure safe logging.
	 * @param input the input string
	 * @return the escaped string
	 */
	private static String escape(String input) {
		if (input == null) {
			return null;
		}

		StringBuilder escapedString = new StringBuilder();
		for (char c : input.toCharArray()) {
			switch (c) {
				case '"':
					escapedString.append("\\\"");
					break;
				case '\\':
					escapedString.append("\\\\");
					break;
				case '\b':
					escapedString.append("\\b");
					break;
				case '\f':
					escapedString.append("\\f");
					break;
				case '\n':
					escapedString.append("\\n");
					break;
				case '\r':
					escapedString.append("\\r");
					break;
				case '\t':
					escapedString.append("\\t");
					break;
				default:
					if (c <= 0x1F) {
						escapedString.append(String.format("\\u%04x", (int) c));
					}
					else {
						escapedString.append(c);
					}
					break;
			}
		}
		return escapedString.toString();
	}

}
