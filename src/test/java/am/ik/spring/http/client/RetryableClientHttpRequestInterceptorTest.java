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

import java.util.Collections;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.springframework.http.ResponseEntity;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.util.backoff.ExponentialBackOff;
import org.springframework.util.backoff.FixedBackOff;
import org.springframework.web.client.ResponseErrorHandler;
import org.springframework.web.client.RestTemplate;

import static org.assertj.core.api.Assertions.assertThat;

class RetryableClientHttpRequestInterceptorTest {

	private final MockServerRunner mockServerRunner = new MockServerRunner();

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
		restTemplate.setInterceptors(
				Collections.singletonList(new RetryableClientHttpRequestInterceptor(new FixedBackOff(100, 2))));
		final ResponseEntity<String> response = restTemplate
			.getForEntity(String.format("http://localhost:%d/hello", MockServerRunner.port), String.class);
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
			.getForEntity(String.format("http://localhost:%d/hello", MockServerRunner.port), String.class);
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
			.getForEntity(String.format("http://localhost:%d/hello", MockServerRunner.port), String.class);
		assertThat(response.getBody()).isEqualTo("Oops!");
		assertThat(response.toString()).contains("503"); // to work with both Spring 5 and
	}

	@Test
	void retry_exponential_recover() {
		final RestTemplate restTemplate = new RestTemplate();
		restTemplate.setInterceptors(
				Collections.singletonList(new RetryableClientHttpRequestInterceptor(new ExponentialBackOff(100, 2))));
		final ResponseEntity<String> response = restTemplate
			.getForEntity(String.format("http://localhost:%d/hello", MockServerRunner.port), String.class);
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
			.getForEntity(String.format("http://localhost:%d/hello", MockServerRunner.port), String.class);
		assertThat(response.getBody()).isEqualTo("Oops!");
		assertThat(response.toString()).contains("503"); // to work with both Spring 5 and
		// 6
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