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
import java.lang.reflect.Method;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.UnknownHostException;
import java.nio.channels.UnresolvedAddressException;
import java.util.Arrays;
import java.util.Collections;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.beans.BeanUtils;
import org.springframework.core.SpringVersion;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.http.client.support.BasicAuthenticationInterceptor;
import org.springframework.http.client.support.HttpRequestWrapper;
import org.springframework.util.ClassUtils;
import org.springframework.util.ReflectionUtils;
import org.springframework.util.backoff.ExponentialBackOff;
import org.springframework.util.backoff.FixedBackOff;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.ResponseErrorHandler;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import static am.ik.spring.http.client.RetryableIOExceptionPredicate.CLIENT_TIMEOUT;
import static am.ik.spring.http.client.RetryableClientHttpRequestInterceptor.DEFAULT_RETRYABLE_RESPONSE_STATUSES;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RetryableClientHttpRequestInterceptorTest {

	private final MockServerRunner mockServerRunner = new MockServerRunner(9999);

	private final Logger logger = LoggerFactory.getLogger(RetryableClientHttpRequestInterceptorTest.class);

	@BeforeEach
	void init() throws Exception {
		logger.info("Spring Version: {}", SpringVersion.getVersion());
		Assertions.setMaxStackTraceElementsDisplayed(1000);
		this.mockServerRunner.run();
	}

	@AfterEach
	void destroy() throws Exception {
		this.mockServerRunner.destroy();
	}

	static Stream<ClientHttpRequestFactory> requestFactories() {
		return Stream
			.of("org.springframework.http.client.SimpleClientHttpRequestFactory",
					"org.springframework.http.client.OkHttp3ClientHttpRequestFactory",
					"org.springframework.http.client.JdkClientHttpRequestFactory")
			.filter(className -> ClassUtils.isPresent(className, null))
			.map(className -> {
				try {
					return (ClientHttpRequestFactory) BeanUtils.instantiateClass(ClassUtils.forName(className, null));
				}
				catch (ClassNotFoundException e) {
					throw new IllegalStateException(e);
				}
			});
	}

	static Stream<ClientHttpRequestFactory> requestFactoriesReadTimeout100() {
		return requestFactories().map(requestFactory -> {
			Method setReadTimeout = ReflectionUtils.findMethod(requestFactory.getClass(), "setReadTimeout", int.class);
			ReflectionUtils.makeAccessible(setReadTimeout);
			ReflectionUtils.invokeMethod(setReadTimeout, requestFactory, 100);
			return requestFactory;
		});
	}

	@ParameterizedTest
	@MethodSource("requestFactories")
	void retry_fixed_recover(ClientHttpRequestFactory requestFactory) {
		final RestTemplate restTemplate = new RestTemplate();
		restTemplate.setInterceptors(Arrays.asList(new BasicAuthenticationInterceptor("username", "password"),
				new RetryableClientHttpRequestInterceptor(new FixedBackOff(100, 2))));
		restTemplate.setRequestFactory(requestFactory);
		final ResponseEntity<String> response = restTemplate
			.getForEntity(String.format("http://localhost:%d/hello", this.mockServerRunner.port()), String.class);
		assertThat(response.getBody()).isEqualTo("Hello World!");
		// to work with both Spring 5 and 6
		assertThat(response.toString()).contains("200");
	}

	@ParameterizedTest
	@MethodSource("requestFactories")
	void retry_fixed_fail(ClientHttpRequestFactory requestFactory) {
		final RestTemplate restTemplate = new RestTemplate();
		restTemplate.setInterceptors(
				Collections.singletonList(new RetryableClientHttpRequestInterceptor(new FixedBackOff(100, 1))));
		restTemplate.setErrorHandler(new NoOpResponseErrorHandler());
		restTemplate.setRequestFactory(requestFactory);
		final ResponseEntity<String> response = restTemplate
			.getForEntity(String.format("http://localhost:%d/hello", this.mockServerRunner.port()), String.class);
		assertThat(response.getBody()).isEqualTo("Oops!");
		// to work with both Spring 5 and 6
		assertThat(response.toString()).contains("503");
	}

	@ParameterizedTest
	@MethodSource("requestFactories")
	void not_recoverable(ClientHttpRequestFactory requestFactory) {
		final RestTemplate restTemplate = new RestTemplate();
		restTemplate.setInterceptors(Collections.singletonList(
				new RetryableClientHttpRequestInterceptor(new FixedBackOff(100, 2), Collections.singleton(500))));
		restTemplate.setErrorHandler(new NoOpResponseErrorHandler());
		restTemplate.setRequestFactory(requestFactory);
		final ResponseEntity<String> response = restTemplate
			.getForEntity(String.format("http://localhost:%d/hello", this.mockServerRunner.port()), String.class);
		assertThat(response.getBody()).isEqualTo("Oops!");
		// to work with both Spring 5 and 6
		assertThat(response.toString()).contains("503");
	}

	@ParameterizedTest
	@MethodSource("requestFactoriesReadTimeout100")
	void timeout_recover(ClientHttpRequestFactory requestFactory) {
		final RestTemplate restTemplate = new RestTemplate();
		restTemplate.setRequestFactory(requestFactory);
		restTemplate.setInterceptors(
				Collections.singletonList(new RetryableClientHttpRequestInterceptor(new FixedBackOff(100, 2))));
		final ResponseEntity<String> response = restTemplate
			.getForEntity(String.format("http://localhost:%d/slow", this.mockServerRunner.port()), String.class);
		assertThat(response.getBody()).isEqualTo("Hello World!");
		// to work with both Spring 5 and 6
		assertThat(response.toString()).contains("200");
	}

	@ParameterizedTest
	@MethodSource("requestFactoriesReadTimeout100")
	void timeout_fail(ClientHttpRequestFactory requestFactory) {
		final RestTemplate restTemplate = new RestTemplate();
		restTemplate.setRequestFactory(requestFactory);
		restTemplate.setInterceptors(
				Collections.singletonList(new RetryableClientHttpRequestInterceptor(new FixedBackOff(100, 1))));
		assertThatThrownBy(() -> restTemplate
			.getForEntity(String.format("http://localhost:%d/slow", this.mockServerRunner.port()), String.class))
			.isInstanceOf(ResourceAccessException.class)
			.matches(e -> {
				Throwable cause = e.getCause();
				return cause instanceof SocketTimeoutException
						|| (cause instanceof IOException && cause.getCause() instanceof TimeoutException)
						|| cause.getClass().getName().equals("java.net.http.HttpTimeoutException");
			});
	}

	@ParameterizedTest
	@MethodSource("requestFactoriesReadTimeout100")
	void no_retry_for_timeout(ClientHttpRequestFactory requestFactory) {
		final RestTemplate restTemplate = new RestTemplate();
		restTemplate.setRequestFactory(requestFactory);
		restTemplate.setInterceptors(Collections.singletonList(
				new RetryableClientHttpRequestInterceptor(new FixedBackOff(100, 2), DEFAULT_RETRYABLE_RESPONSE_STATUSES,
						options -> options.removeRetryableIOException(CLIENT_TIMEOUT))));
		assertThatThrownBy(() -> restTemplate
			.getForEntity(String.format("http://localhost:%d/slow", this.mockServerRunner.port()), String.class))
			.isInstanceOf(ResourceAccessException.class)
			.matches(e -> {
				Throwable cause = e.getCause();
				return cause instanceof SocketTimeoutException
						|| (cause instanceof IOException && cause.getCause() instanceof TimeoutException)
						|| cause.getClass().getName().equals("java.net.http.HttpTimeoutException");
			});
	}

	@ParameterizedTest
	@MethodSource("requestFactories")
	void retry_exponential_recover(ClientHttpRequestFactory requestFactory) {
		final RestTemplate restTemplate = new RestTemplate();
		restTemplate.setInterceptors(
				Collections.singletonList(new RetryableClientHttpRequestInterceptor(new ExponentialBackOff(100, 2))));
		restTemplate.setRequestFactory(requestFactory);
		final ResponseEntity<String> response = restTemplate
			.getForEntity(String.format("http://localhost:%d/hello", this.mockServerRunner.port()), String.class);
		assertThat(response.getBody()).isEqualTo("Hello World!");
		// to work with both Spring 5 and 6
		assertThat(response.toString()).contains("200");
	}

	@ParameterizedTest
	@MethodSource("requestFactories")
	void retry_exponential_fail(ClientHttpRequestFactory requestFactory) {
		final RestTemplate restTemplate = new RestTemplate();
		restTemplate.setInterceptors(
				Collections.singletonList(new RetryableClientHttpRequestInterceptor(new FixedBackOff(100, 1))));
		restTemplate.setErrorHandler(new NoOpResponseErrorHandler());
		restTemplate.setRequestFactory(requestFactory);
		final ResponseEntity<String> response = restTemplate
			.getForEntity(String.format("http://localhost:%d/hello", this.mockServerRunner.port()), String.class);
		assertThat(response.getBody()).isEqualTo("Oops!");
		// to work with both Spring 5 and 6
		assertThat(response.toString()).contains("503");
	}

	@ParameterizedTest
	@MethodSource("requestFactories")
	void connection_refused_recover(ClientHttpRequestFactory requestFactory) {
		int port = this.mockServerRunner.port() + 1;
		CountDownLatch latch = new CountDownLatch(1);
		ScheduledExecutorService executorService = Executors.newSingleThreadScheduledExecutor();
		executorService.schedule(() -> {
			try {
				MockServerRunner runner = new MockServerRunner(port);
				runner.run();
				latch.await(30, TimeUnit.SECONDS);
				runner.destroy();
			}
			catch (Exception e) {
				throw new RuntimeException(e);
			}
		}, 100, TimeUnit.MILLISECONDS);
		final RestTemplate restTemplate = new RestTemplate();
		restTemplate.setInterceptors(
				Collections.singletonList(new RetryableClientHttpRequestInterceptor(new FixedBackOff(100, 10))));
		restTemplate.setRequestFactory(requestFactory);
		final ResponseEntity<String> response = restTemplate
			.getForEntity(String.format("http://localhost:%d/hello", port), String.class);
		assertThat(response.getBody()).isEqualTo("Hello World!");
		// to work with both Spring 5 and 6
		assertThat(response.toString()).contains("200");
		latch.countDown();
	}

	@ParameterizedTest
	@MethodSource("requestFactories")
	void connection_refused_fail(ClientHttpRequestFactory requestFactory) {
		int port = this.mockServerRunner.port() + 1;
		final RestTemplate restTemplate = new RestTemplate();
		restTemplate.setInterceptors(
				Collections.singletonList(new RetryableClientHttpRequestInterceptor(new FixedBackOff(100, 10))));
		restTemplate.setRequestFactory(requestFactory);
		assertThatThrownBy(
				() -> restTemplate.getForEntity(String.format("http://localhost:%d/hello", port), String.class))
			.isInstanceOf(ResourceAccessException.class)
			.hasCauseInstanceOf(ConnectException.class);
	}

	@ParameterizedTest
	@MethodSource("requestFactories")
	void unknown_host_recover(ClientHttpRequestFactory requestFactory) {
		final RestTemplate restTemplate = new RestTemplate();
		final AtomicInteger count = new AtomicInteger(0);
		final LoadBalanceStrategy loadBalanceStrategy = request -> {
			count.getAndIncrement();
			return new HttpRequestWrapper(request) {
				@Override
				public URI getURI() {
					return UriComponentsBuilder.fromUri(request.getURI())
						.port(mockServerRunner.port())
						.host(count.get() == 1 ? "noanswer.example.com" : "127.0.0.1")
						.build()
						.toUri();
				}
			};
		};
		restTemplate.setInterceptors(Collections.singletonList(new RetryableClientHttpRequestInterceptor(
				new FixedBackOff(100, 10), options -> options.loadBalanceStrategy(loadBalanceStrategy))));
		restTemplate.setRequestFactory(requestFactory);
		final ResponseEntity<String> response = restTemplate.getForEntity("http://hello-service/hello", String.class);
		assertThat(response.getBody()).isEqualTo("Hello World!");
		// to work with both Spring 5 and 6
		assertThat(response.toString()).contains("200");
	}

	@ParameterizedTest
	@MethodSource("requestFactories")
	void unknown_host_recover_fail(ClientHttpRequestFactory requestFactory) {
		final RestTemplate restTemplate = new RestTemplate();
		restTemplate.setInterceptors(
				Collections.singletonList(new RetryableClientHttpRequestInterceptor(new FixedBackOff(100, 1))));
		restTemplate.setRequestFactory(requestFactory);
		assertThatThrownBy(() -> restTemplate.getForEntity("http://noanswer.example.com/hello", String.class))
			.isInstanceOf(ResourceAccessException.class)
			.matches(e -> {
				Throwable cause = e.getCause();
				return cause instanceof UnknownHostException
						|| (cause instanceof ConnectException && cause.getCause() instanceof ConnectException
								&& cause.getCause().getCause() instanceof UnresolvedAddressException);
			});
	}

	private static class NoOpResponseErrorHandler implements ResponseErrorHandler {

		@Override
		public boolean hasError(ClientHttpResponse response) {
			return false;
		}

		@Override
		public void handleError(ClientHttpResponse response) {

		}

	}

}