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

import java.io.IOException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.URLConnection;
import java.net.UnknownHostException;
import java.nio.channels.UnresolvedAddressException;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.TimeoutException;
import java.util.function.Predicate;

public enum RetryableIOExceptionPredicate implements Predicate<IOException> {

	/**
	 * @see {@link URLConnection#setConnectTimeout(int)}
	 * @see {@link URLConnection#setReadTimeout(int)}
	 */
	CLIENT_TIMEOUT {
		@Override
		public boolean test(IOException e) {
			return (e instanceof SocketTimeoutException || e.getCause() instanceof TimeoutException || e.getClass()
				.getName()
				.equals("java.net.http.HttpTimeoutException") /*
																 * Check class name for
																 * the compatibility with
																 * Java 8-10
																 */);
		}
	},
	CONNECT_TIMEOUT {
		@Override
		public boolean test(IOException e) {
			return (e instanceof ConnectException || e.getClass()
				.getName()
				.equals("java.net.http.HttpConnectTimeoutException") /*
																		 * Check class
																		 * name for the
																		 * compatibility
																		 * with Java 8-10
																		 */);
		}
	},
	UNKNOWN_HOST {
		@Override
		public boolean test(IOException e) {
			return (e instanceof UnknownHostException
					|| (e instanceof ConnectException && e.getCause() instanceof ConnectException
							&& e.getCause().getCause() instanceof UnresolvedAddressException));
		}
	},
	ANY {
		@Override
		public boolean test(IOException e) {
			return true;
		}
	};

	public static Set<Predicate<IOException>> defaults() {
		return Collections
			.unmodifiableSet(new LinkedHashSet<>(Arrays.asList(CLIENT_TIMEOUT, CONNECT_TIMEOUT, UNKNOWN_HOST)));
	}

}
