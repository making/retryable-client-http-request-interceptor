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
import java.util.List;
import java.util.Objects;

public interface EndpointResolver {

	List<Endpoint> resolve(Endpoint service);

	class Endpoint {

		private final String host;

		private final int port;

		public static Endpoint of(String host, int port) {
			return new Endpoint(host, port);
		}

		public static Endpoint of(URI uri) {
			return new Endpoint(uri.getHost(), uri.getPort());
		}

		private Endpoint(String host, int port) {
			this.host = host;
			this.port = port;
		}

		public String host() {
			return host;
		}

		public int port() {
			return port;
		}

		@Override
		public boolean equals(Object o) {
			if (this == o)
				return true;
			if (o == null || getClass() != o.getClass())
				return false;
			Endpoint that = (Endpoint) o;
			return port == that.port && Objects.equals(host, that.host);
		}

		@Override
		public int hashCode() {
			return Objects.hash(host, port);
		}

		@Override
		public String toString() {
			return "Endpoint{" + "host='" + host + '\'' + ", port=" + port + '}';
		}

	}

}
