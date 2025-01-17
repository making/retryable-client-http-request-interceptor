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

	static Set<Predicate<IOException>> defaults() {
		return Collections
			.unmodifiableSet(new LinkedHashSet<>(Arrays.asList(CLIENT_TIMEOUT, CONNECT_TIMEOUT, UNKNOWN_HOST)));
	}

}
