/*
 * Copyright 2013-2020 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springframework.cloud.netflix.zuul.filters;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.springframework.mock.web.MockHttpServletRequest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@link ServletTraceableRequest}.
 */
class ServletTraceableRequestTests {

	private MockHttpServletRequest request;

	@BeforeEach
	public void setup() {
		request = new MockHttpServletRequest("GET", "/script");
	}

	@Test
	public void getUriWithoutQueryStringShouldReturnUri() {
		validate("http://localhost/script");
	}

	@Test
	public void getUriShouldReturnUriWithQueryString() {
		request.setQueryString("a=b");
		validate("http://localhost/script?a=b");
	}

	@Test
	public void getUriWithSpecialCharactersInQueryStringShouldEncode() {
		request.setQueryString("a=${b}");
		validate("http://localhost/script?a%3D%24%7Bb%7D");
	}

	@Test
	public void getUriWithSpecialCharactersEncodedShouldNotDoubleEncode() {
		request.setQueryString("a%3D%24%7Bb%7D");
		validate("http://localhost/script?a%3D%24%7Bb%7D");
	}

	private void validate(String expectedUri) {
		ServletTraceableRequest trace = new ServletTraceableRequest(request);
		assertThat(trace.getUri().toString()).isEqualTo(expectedUri);
	}

}
