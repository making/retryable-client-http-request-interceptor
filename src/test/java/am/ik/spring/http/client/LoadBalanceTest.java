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

import java.io.OutputStream;
import java.io.PrintWriter;
import java.time.Clock;
import java.time.Duration;
import java.util.Arrays;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicInteger;

import am.ik.spring.http.client.EndpointResolver.Endpoint;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.util.backoff.FixedBackOff;
import org.springframework.web.client.RestTemplate;

import static org.assertj.core.api.Assertions.assertThat;

class LoadBalanceTest {

	private MockServerRunner service1 = new MockServerRunner(9997);

	private MockServerRunner service2 = new MockServerRunner(9998);

	@BeforeEach
	void init() throws Exception {
		this.service1.run();
		this.service2.run();
	}

	@AfterEach
	void destroy() throws Exception {
		this.service1.destroy();
		this.service2.destroy();
	}

	@Test
	void test() throws Exception {
		final RestTemplate restTemplate = new RestTemplate();
		ThreadPoolTaskScheduler taskScheduler = new ThreadPoolTaskScheduler();
		taskScheduler.afterPropertiesSet();
		RoundRobinLoadBalanceStrategy retryTargetStrategy = new RoundRobinLoadBalanceStrategy(
				service -> Arrays.asList(Endpoint.of("localhost", 9997), Endpoint.of("localhost", 9998)), taskScheduler,
				Duration.ofSeconds(1), Duration.ofSeconds(1), Clock.systemUTC());
		restTemplate.setInterceptors(Collections.singletonList(new RetryableClientHttpRequestInterceptor(
				new FixedBackOff(100, 10), options -> options.loadBalanceStrategy(retryTargetStrategy))));

		AtomicInteger count1 = new AtomicInteger(0);
		AtomicInteger count2 = new AtomicInteger(0);
		// service1 is healthy.
		this.service1.addContext("/test", exchange -> {
			count1.incrementAndGet();
			try (final OutputStream stream = exchange.getResponseBody()) {
				final PrintWriter printWriter = new PrintWriter(stream);
				final String body = "OK";
				exchange.sendResponseHeaders(200, body.length());
				printWriter.write(body);
				printWriter.flush();
			}
		});
		// service2 is down
		this.service2.addContext("/test", exchange -> {
			count2.incrementAndGet();
			exchange.sendResponseHeaders(503, 0);
		});

		for (int i = 0; i < 30; i++) {
			String result = restTemplate.getForObject("http://hello/test", String.class);
			assertThat(result).isEqualTo("OK");
			Thread.sleep(100);
		}
		assertThat(count1.get()).isEqualTo(30);
		assertThat(count2.get()).isEqualTo(2);
		count1.set(0);
		count2.set(0);
		// service2 became healthy
		this.service2.destroy();
		this.service2 = new MockServerRunner(9998);
		this.service2.run();
		this.service2.addContext("/test", exchange -> {
			count2.incrementAndGet();
			try (final OutputStream stream = exchange.getResponseBody()) {
				final PrintWriter printWriter = new PrintWriter(stream);
				final String body = "OK";
				exchange.sendResponseHeaders(200, body.length());
				printWriter.write(body);
				printWriter.flush();
			}
		});
		for (int i = 0; i < 20; i++) {
			String result = restTemplate.getForObject("http://hello/test", String.class);
			assertThat(result).isEqualTo("OK");
			Thread.sleep(100);
		}
		assertThat(count2.get()).isGreaterThanOrEqualTo(1);
		assertThat(count1.get() + count2.get()).isEqualTo(20);
	}

}
