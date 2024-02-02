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

import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.util.Arrays;
import java.util.Collections;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.springframework.http.ResponseEntity;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.http.client.support.BasicAuthenticationInterceptor;
import org.springframework.util.backoff.ExponentialBackOff;
import org.springframework.util.backoff.FixedBackOff;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.ResponseErrorHandler;
import org.springframework.web.client.RestTemplate;

import static am.ik.spring.http.client.RetryableClientHttpRequestInterceptor.DEFAULT_RETRYABLE_RESPONSE_STATUSES;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RetryableClientHttpRequestInterceptorTest {

	private final MockServerRunner mockServerRunner = new MockServerRunner(9999);

	@BeforeEach
	void init() throws Exception {
		this.mockServerRunner.run();
	}

	@AfterEach
	void destroy() throws Exception {
		this.mockServerRunner.destroy();
	}

	@Test
	void retry_fixed_recover() {
		final RestTemplate restTemplate = new RestTemplate();
		restTemplate.setInterceptors(Arrays.asList(new BasicAuthenticationInterceptor("username", "password"),
				new RetryableClientHttpRequestInterceptor(new FixedBackOff(100, 2))));
		final ResponseEntity<String> response = restTemplate
			.getForEntity(String.format("http://localhost:%d/hello", this.mockServerRunner.port()), String.class);
		assertThat(response.getBody()).isEqualTo("Hello World!");
		assertThat(response.toString()).contains("200"); // to work with both Spring 5 and
		// 6
	}

	@Test
	void retry_fixed_fail() {
		final RestTemplate restTemplate = new RestTemplate();
		restTemplate.setInterceptors(
				Collections.singletonList(new RetryableClientHttpRequestInterceptor(new FixedBackOff(100, 1))));
		restTemplate.setErrorHandler(new NoOpResponseErrorHandler());
		final ResponseEntity<String> response = restTemplate
			.getForEntity(String.format("http://localhost:%d/hello", this.mockServerRunner.port()), String.class);
		assertThat(response.getBody()).isEqualTo("Oops!");
		assertThat(response.toString()).contains("503"); // to work with both Spring 5 and
		// 6
	}

	@Test
	void not_recoverable() {
		final RestTemplate restTemplate = new RestTemplate();
		restTemplate.setInterceptors(Collections.singletonList(
				new RetryableClientHttpRequestInterceptor(new FixedBackOff(100, 2), Collections.singleton(500))));
		restTemplate.setErrorHandler(new NoOpResponseErrorHandler());
		final ResponseEntity<String> response = restTemplate
			.getForEntity(String.format("http://localhost:%d/hello", this.mockServerRunner.port()), String.class);
		assertThat(response.getBody()).isEqualTo("Oops!");
		assertThat(response.toString()).contains("503"); // to work with both Spring 5 and
		// 6
	}

	@Test
	void timeout_recover() {
		final RestTemplate restTemplate = new RestTemplate();
		final SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
		requestFactory.setReadTimeout(100);
		restTemplate.setRequestFactory(requestFactory);
		restTemplate.setInterceptors(
				Collections.singletonList(new RetryableClientHttpRequestInterceptor(new FixedBackOff(100, 2))));
		final ResponseEntity<String> response = restTemplate
			.getForEntity(String.format("http://localhost:%d/slow", this.mockServerRunner.port()), String.class);
		assertThat(response.getBody()).isEqualTo("Hello World!");
		assertThat(response.toString()).contains("200"); // to work with both Spring 5 and
		// 6
	}

	@Test
	void timeout_fail() {
		final RestTemplate restTemplate = new RestTemplate();
		final SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
		requestFactory.setReadTimeout(100);
		restTemplate.setRequestFactory(requestFactory);
		restTemplate.setInterceptors(
				Collections.singletonList(new RetryableClientHttpRequestInterceptor(new FixedBackOff(100, 1))));
		assertThatThrownBy(() -> restTemplate
			.getForEntity(String.format("http://localhost:%d/slow", this.mockServerRunner.port()), String.class))
			.isInstanceOf(ResourceAccessException.class)
			.hasCauseInstanceOf(SocketTimeoutException.class);
	}

	@Test
	void no_retry_for_timeout() {
		final RestTemplate restTemplate = new RestTemplate();
		final SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
		requestFactory.setReadTimeout(100);
		restTemplate.setRequestFactory(requestFactory);
		restTemplate.setInterceptors(
				Collections.singletonList(new RetryableClientHttpRequestInterceptor(new FixedBackOff(100, 2),
						DEFAULT_RETRYABLE_RESPONSE_STATUSES, options -> options.retryClientTimeout(false))));
		assertThatThrownBy(() -> restTemplate
			.getForEntity(String.format("http://localhost:%d/slow", this.mockServerRunner.port()), String.class))
			.isInstanceOf(ResourceAccessException.class)
			.hasCauseInstanceOf(SocketTimeoutException.class);
	}

	@Test
	void retry_exponential_recover() {
		final RestTemplate restTemplate = new RestTemplate();
		restTemplate.setInterceptors(
				Collections.singletonList(new RetryableClientHttpRequestInterceptor(new ExponentialBackOff(100, 2))));
		final ResponseEntity<String> response = restTemplate
			.getForEntity(String.format("http://localhost:%d/hello", this.mockServerRunner.port()), String.class);
		assertThat(response.getBody()).isEqualTo("Hello World!");
		assertThat(response.toString()).contains("200"); // to work with both Spring 5 and
		// 6
	}

	@Test
	void retry_exponential_fail() {
		final RestTemplate restTemplate = new RestTemplate();
		restTemplate.setInterceptors(
				Collections.singletonList(new RetryableClientHttpRequestInterceptor(new FixedBackOff(100, 1))));
		restTemplate.setErrorHandler(new NoOpResponseErrorHandler());
		final ResponseEntity<String> response = restTemplate
			.getForEntity(String.format("http://localhost:%d/hello", this.mockServerRunner.port()), String.class);
		assertThat(response.getBody()).isEqualTo("Oops!");
		assertThat(response.toString()).contains("503"); // to work with both Spring 5 and
		// 6
	}

	@Test
	void connection_refused_recover() {
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
		final ResponseEntity<String> response = restTemplate
			.getForEntity(String.format("http://localhost:%d/hello", port), String.class);
		assertThat(response.getBody()).isEqualTo("Hello World!");
		assertThat(response.toString()).contains("200"); // to work with both Spring 5 and
		latch.countDown();
		// 6
	}

	@Test
	void connection_refused_fail() {
		int port = this.mockServerRunner.port() + 1;
		final RestTemplate restTemplate = new RestTemplate();
		restTemplate.setInterceptors(
				Collections.singletonList(new RetryableClientHttpRequestInterceptor(new FixedBackOff(100, 10))));
		assertThatThrownBy(
				() -> restTemplate.getForEntity(String.format("http://localhost:%d/hello", port), String.class))
			.isInstanceOf(ResourceAccessException.class)
			.hasCauseInstanceOf(ConnectException.class);
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