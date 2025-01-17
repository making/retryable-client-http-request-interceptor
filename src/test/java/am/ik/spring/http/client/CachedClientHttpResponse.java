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

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.util.StreamUtils;

class CachedClientHttpResponse implements ClientHttpResponse {

	private final ClientHttpResponse delegate;

	private final byte[] cachedBody;

	public CachedClientHttpResponse(ClientHttpResponse delegate) {
		this.delegate = delegate;
		try {
			this.cachedBody = StreamUtils.copyToByteArray(delegate.getBody());
		}
		catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	// to work with both Spring 5 and 6
	@Override
	public HttpStatus getStatusCode() throws IOException {
		return (HttpStatus) delegate.getStatusCode();
	}

	@Override
	@SuppressWarnings("removal")
	public int getRawStatusCode() throws IOException {
		return delegate.getRawStatusCode();
	}

	@Override
	public String getStatusText() throws IOException {
		return delegate.getStatusText();
	}

	@Override
	public void close() {
		delegate.close();
	}

	@Override
	public HttpHeaders getHeaders() {
		return delegate.getHeaders();
	}

	@Override
	public java.io.InputStream getBody() throws IOException {
		// Return the cached response body
		return new ByteArrayInputStream(cachedBody);
	}

}
