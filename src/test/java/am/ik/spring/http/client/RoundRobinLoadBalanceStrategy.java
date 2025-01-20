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

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicInteger;

import am.ik.spring.http.client.EndpointResolver.Endpoint;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.http.HttpRequest;
import org.springframework.http.client.support.HttpRequestWrapper;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.web.util.UriComponentsBuilder;

public class RoundRobinLoadBalanceStrategy implements LoadBalanceStrategy, RetryLifecycle {

	private final EndpointResolver endpointResolver;

	private final AtomicInteger count = new AtomicInteger(0);

	private final Logger log = LoggerFactory.getLogger(RoundRobinLoadBalanceStrategy.class);

	private final ConcurrentMap<Endpoint, FailedEndpointCache> failedEndpoints = new ConcurrentHashMap<>();

	private final Clock clock;

	public RoundRobinLoadBalanceStrategy(EndpointResolver endpointResolver, TaskScheduler taskScheduler, Duration ttl,
			Duration cleanupInterval, Clock clock) {
		this.endpointResolver = endpointResolver;
		this.clock = clock;
		taskScheduler.scheduleAtFixedRate(() -> {
			Iterator<Map.Entry<Endpoint, FailedEndpointCache>> iterator = failedEndpoints.entrySet().iterator();
			Instant now = clock.instant();
			while (iterator.hasNext()) {
				Map.Entry<Endpoint, FailedEndpointCache> cache = iterator.next();
				if (cache.getValue().failedAt().plusSeconds(ttl.toSeconds()).isBefore(now)) {
					log.info("Remove {}", cache.getValue());
					iterator.remove();
				}
			}

		}, cleanupInterval);
	}

	@Override
	public HttpRequest choose(HttpRequest request) {
		List<Endpoint> endpoints = endpointResolver.resolve(Endpoint.of(request.getURI()));
		int numberOfEndpoints = endpoints.size();
		Endpoint endpoint = null;
		for (int n = 0; n < numberOfEndpoints; n++) {
			int i = count.getAndIncrement() % numberOfEndpoints;
			endpoint = endpoints.get(i);
			if (failedEndpoints.containsKey(endpoint)) {
				log.debug("{} is marked as a failed endpoint.", endpoint);
			}
			else {
				break;
			}
		}
		final Endpoint ep = endpoint != null ? endpoint : Endpoint.of(request.getURI());
		return new HttpRequestWrapper(request) {
			@Override
			public URI getURI() {
				return UriComponentsBuilder.fromUri(request.getURI()).host(ep.host()).port(ep.port()).build().toUri();
			}
		};
	}

	@Override
	public void onRetry(HttpRequest request, ResponseOrException responseOrException) {
		Endpoint endpoint = Endpoint.of(request.getURI());
		failedEndpoints.computeIfAbsent(endpoint, k -> new FailedEndpointCache(endpoint, this.clock.instant()));
	}

	static class FailedEndpointCache {

		private final Endpoint endpoint;

		private final Instant failedAt;

		public FailedEndpointCache(Endpoint endpoint, Instant failedAt) {
			this.endpoint = endpoint;
			this.failedAt = failedAt;
		}

		public Endpoint endpoint() {
			return endpoint;
		}

		public Instant failedAt() {
			return failedAt;
		}

		@Override
		public String toString() {
			return "FailedEndpointCache{" + "endpoint='" + endpoint + '\'' + ", failedAt=" + failedAt + '}';
		}

	}

}
