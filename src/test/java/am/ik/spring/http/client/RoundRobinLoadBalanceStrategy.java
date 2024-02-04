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

import am.ik.spring.http.client.UrlResolver.HostAndPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.http.HttpRequest;
import org.springframework.http.client.support.HttpRequestWrapper;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.web.util.UriComponentsBuilder;

public class RoundRobinLoadBalanceStrategy implements LoadBalanceStrategy, RetryLifecycle {

	private final UrlResolver urlResolver;

	private final AtomicInteger count = new AtomicInteger(0);

	private final Logger log = LoggerFactory.getLogger(RoundRobinLoadBalanceStrategy.class);

	private final ConcurrentMap<HostAndPort, FailedTargetCache> failedTargets = new ConcurrentHashMap<>();

	private final Clock clock;

	public RoundRobinLoadBalanceStrategy(UrlResolver urlResolver, TaskScheduler taskScheduler, Duration ttl,
			Duration cleanupInterval, Clock clock) {
		this.urlResolver = urlResolver;
		this.clock = clock;
		taskScheduler.scheduleAtFixedRate(() -> {
			Iterator<Map.Entry<HostAndPort, FailedTargetCache>> iterator = failedTargets.entrySet().iterator();
			Instant now = clock.instant();
			while (iterator.hasNext()) {
				Map.Entry<HostAndPort, FailedTargetCache> cache = iterator.next();
				if (cache.getValue().failedAt().plusSeconds(ttl.toSeconds()).isBefore(now)) {
					log.info("Remove {}", cache.getValue());
					iterator.remove();
				}
			}

		}, cleanupInterval);
	}

	@Override
	public HttpRequest choose(HttpRequest request) {
		List<HostAndPort> targets = urlResolver.resolve(HostAndPort.of(request.getURI()));
		int numberOfTargets = targets.size();
		HostAndPort target = null;
		for (int n = 0; n < numberOfTargets; n++) {
			int i = count.getAndIncrement() % numberOfTargets;
			target = targets.get(i);
			if (failedTargets.containsKey(target)) {
				log.debug("{} is marked as a failed target.", target);
			}
			else {
				break;
			}
		}
		final HostAndPort t = target != null ? target : HostAndPort.of(request.getURI());
		return new HttpRequestWrapper(request) {
			@Override
			public URI getURI() {
				return UriComponentsBuilder.fromUri(request.getURI()).host(t.host()).port(t.port()).build().toUri();
			}
		};
	}

	@Override
	public void onRetry(HttpRequest request, ResponseOrException responseOrException) {
		HostAndPort target = HostAndPort.of(request.getURI());
		failedTargets.computeIfAbsent(target, k -> new FailedTargetCache(target, this.clock.instant()));
	}

	static class FailedTargetCache {

		private final HostAndPort target;

		private final Instant failedAt;

		public FailedTargetCache(HostAndPort target, Instant failedAt) {
			this.target = target;
			this.failedAt = failedAt;
		}

		public HostAndPort target() {
			return target;
		}

		public Instant failedAt() {
			return failedAt;
		}

		@Override
		public String toString() {
			return "FailedTargetCache{" + "target='" + target + '\'' + ", failedAt=" + failedAt + '}';
		}

	}

}
