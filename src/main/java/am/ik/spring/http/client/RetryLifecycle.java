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

import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpResponse;

/**
 * Retry LifeCycle
 *
 * @since 0.3.0
 */
public interface RetryLifecycle {

	default void onSuccess(HttpRequest request, ClientHttpResponse response) {

	}

	default void onRetry(HttpRequest request, ResponseOrException responseOrException) {

	}

	default void onNoLongerRetryable(HttpRequest request, ResponseOrException responseOrException) {

	}

	default void onFailure(HttpRequest request, ResponseOrException responseOrException) {

	}

	RetryLifecycle NOOP = new RetryLifecycle() {
	};

	class ResponseOrException {

		private final ClientHttpResponse response;

		private final Exception exception;

		public static ResponseOrException ofResponse(ClientHttpResponse response) {
			return new ResponseOrException(response, null);
		}

		public static ResponseOrException ofException(Exception e) {
			return new ResponseOrException(null, e);
		}

		private ResponseOrException(ClientHttpResponse response, Exception exception) {
			this.response = response;
			this.exception = exception;
		}

		public ClientHttpResponse response() {
			return this.response;
		}

		public Exception exception() {
			return this.exception;
		}

		public boolean hasResponse() {
			return this.response != null;
		}

		public boolean hasException() {
			return this.exception != null;
		}

		@Override
		public String toString() {
			if (this.response != null) {
				return this.response.toString();
			}
			else if (this.exception != null) {
				return this.exception.toString();
			}
			return "null";
		}

	}

}
