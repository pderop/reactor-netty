/*
 * Copyright (c) 2019-2022 VMware, Inc. or its affiliates, All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package reactor.netty5.http.client;

import io.netty5.channel.Channel;
import io.netty5.handler.codec.http.HttpHeaderNames;
import io.netty5.handler.codec.http.HttpHeaderValues;
import io.netty5.handler.codec.http.headers.HttpHeaders;
import io.netty5.handler.codec.http.HttpMethod;
import io.netty5.handler.codec.http.HttpResponseStatus;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.netty5.BufferFlux;
import reactor.netty5.TomcatServer;
import reactor.netty5.resources.ConnectionProvider;
import reactor.util.function.Tuple2;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * @author Violeta Georgieva
 */
class HttpClientWithTomcatTest {
	private static TomcatServer tomcat;

	@BeforeAll
	static void startTomcat() throws Exception {
		tomcat = new TomcatServer();
		tomcat.createDefaultContext();
		tomcat.start();
	}

	@AfterAll
	static void stopTomcat() throws Exception {
		if (tomcat != null) {
			tomcat.stop();
		}
	}

	@Test
	void nettyNetChannelAcceptsNettyChannelHandlers() throws Exception {
		HttpClient client = HttpClient.create()
		                              .port(getPort())
		                              .wiretap(true);

		final CountDownLatch latch = new CountDownLatch(1);
		String response = client.get()
		                        .uri("/?q=test%20d%20dq")
		                        .responseContent()
		                        .aggregate()
		                        .asString()
		                        .doOnSuccess(v -> latch.countDown())
		                        .block(Duration.ofSeconds(30));

		assertThat(latch.await(15, TimeUnit.SECONDS)).as("Latch didn't time out").isTrue();
		assertThat(response)
				.isNotNull()
				.contains("q=test%20d%20dq");
	}

	@Test
	void simpleTest404() {
		doSimpleTest404(HttpClient.create()
		                          .baseUrl(getURL()));
	}

	@Test
	void simpleTest404_1() {
		ConnectionProvider pool = ConnectionProvider.create("simpleTest404_1", 1);
		HttpClient client =
				HttpClient.create(pool)
				          .port(getPort())
				          .host("localhost")
				          .wiretap(true);
		doSimpleTest404(client);
		doSimpleTest404(client);
		pool.dispose();
	}

	private void doSimpleTest404(HttpClient client) {
		Integer res = client.followRedirect(true)
		                    .get()
		                    .uri("/status/404")
		                    .responseSingle((r, buf) -> Mono.just(r.status().code()))
		                    .log()
		                    .block();

		assertThat(res).isNotNull();
		if (res != 404) {
			throw new IllegalStateException("test status failed with " + res);
		}
	}

	@Test
	void disableChunkForced() {
		AtomicReference<HttpHeaders> headers = new AtomicReference<>();
		Tuple2<HttpResponseStatus, String> r =
				HttpClient.newConnection()
				          .host("localhost")
				          .port(getPort())
				          .headers(h -> h.set(HttpHeaderNames.TRANSFER_ENCODING, HttpHeaderValues.CHUNKED))
				          .wiretap(true)
				          .doAfterRequest((req, connection) -> headers.set(req.requestHeaders()))
				          .request(HttpMethod.GET)
				          .uri("/status/400")
				          .send(BufferFlux.fromString(Flux.just("hello")))
				          .responseSingle((res, conn) -> Mono.just(res.status())
				                                             .zipWith(conn.asString()))
				          .block(Duration.ofSeconds(30));

		assertThat(r).isNotNull();

		assertThat(r.getT1()).isEqualTo(HttpResponseStatus.BAD_REQUEST);
		assertThat(headers.get().get("Content-Length")).isEqualTo("5");
		assertThat(headers.get().get("Transfer-Encoding")).isNull();
	}

	@Test
	void disableChunkForced2() {
		AtomicReference<HttpHeaders> headers = new AtomicReference<>();
		Tuple2<HttpResponseStatus, String> r =
				HttpClient.newConnection()
				          .host("localhost")
				          .port(getPort())
				          .wiretap(true)
				          .doAfterRequest((req, connection) -> headers.set(req.requestHeaders()))
				          .keepAlive(false)
				          .get()
				          .uri("/status/404")
				          .responseSingle((res, conn) -> Mono.just(res.status())
				                                             .zipWith(conn.asString()))
				          .block(Duration.ofSeconds(30));

		assertThat(r).isNotNull();

		assertThat(r.getT1()).isEqualTo(HttpResponseStatus.NOT_FOUND);
		assertThat(headers.get().get("Content-Length")).isEqualTo("0");
		assertThat(headers.get().get("Transfer-Encoding")).isNull();
	}

	@Test
	void simpleClientPooling() {
		ConnectionProvider p = ConnectionProvider.create("simpleClientPooling", 1);
		AtomicReference<Channel> ch1 = new AtomicReference<>();
		AtomicReference<Channel> ch2 = new AtomicReference<>();

		HttpResponseStatus r =
				HttpClient.create(p)
				          .doOnResponse((res, c) -> ch1.set(c.channel()))
				          .wiretap(true)
				          .get()
				          .uri(getURL() + "/status/404")
				          .responseSingle((res, buf) -> buf.thenReturn(res.status()))
				          .block(Duration.ofSeconds(30));

		HttpClient.create(p)
		          .doOnResponse((res, c) -> ch2.set(c.channel()))
		          .wiretap(true)
		          .get()
		          .uri(getURL() + "/status/404")
		          .responseSingle((res, buf) -> buf.thenReturn(res.status()))
		          .block(Duration.ofSeconds(30));

		AtomicBoolean same = new AtomicBoolean();

		same.set(ch1.get() == ch2.get());

		assertThat(same.get()).isTrue();

		assertThat(r).isEqualTo(HttpResponseStatus.NOT_FOUND);
		p.dispose();
	}

	@Test
	void disableChunkImplicitDefault() {
		ConnectionProvider p = ConnectionProvider.create("disableChunkImplicitDefault", 1);
		HttpClient client =
				HttpClient.create(p)
				          .host("localhost")
				          .port(getPort())
				          .wiretap(true);

		Tuple2<HttpResponseStatus, Channel> r =
				client.get()
				      .uri("/status/404")
				      .responseConnection((res, conn) -> Mono.just(res.status())
				                                             .delayUntil(s -> conn.inbound().receive())
				                                             .zipWith(Mono.just(conn.channel())))
				      .blockLast(Duration.ofSeconds(30));

		assertThat(r).isNotNull();

		Channel r2 =
				client.get()
				      .uri("/status/404")
				      .responseConnection((res, conn) -> Mono.just(conn.channel())
				                                             .delayUntil(s -> conn.inbound().receive()))
				      .blockLast(Duration.ofSeconds(30));

		assertThat(r2).isNotNull();

		assertThat(r.getT2()).isSameAs(r2);

		assertThat(r.getT1()).isEqualTo(HttpResponseStatus.NOT_FOUND);
		p.dispose();
	}

	@Test
	void contentHeader() {
		ConnectionProvider fixed = ConnectionProvider.create("contentHeader", 1);
		HttpClient client =
				HttpClient.create(fixed)
				          .wiretap(true)
				          .headers(h -> h.add("content-length", "1"));

		HttpResponseStatus r =
				client.request(HttpMethod.GET)
				      .uri(getURL())
				      .send(BufferFlux.fromString(Mono.just(" ")))
				      .responseSingle((res, buf) -> Mono.just(res.status()))
				      .block(Duration.ofSeconds(30));

		client.request(HttpMethod.GET)
		      .uri(getURL())
		      .send(BufferFlux.fromString(Mono.just(" ")))
		      .responseSingle((res, buf) -> Mono.just(res.status()))
		      .block(Duration.ofSeconds(30));

		assertThat(r).isEqualTo(HttpResponseStatus.BAD_REQUEST);
		fixed.dispose();
	}

	private int getPort() {
		return tomcat.port();
	}

	private String getURL() {
		return "http://localhost:" + tomcat.port();
	}
}