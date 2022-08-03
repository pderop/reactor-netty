/*
 * Copyright (c) 2020-2022 VMware, Inc. or its affiliates, All Rights Reserved.
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
package reactor.netty5.udp;

import io.netty5.channel.Channel;
import io.netty5.channel.ChannelOption;
import io.netty5.channel.EventLoopGroup;
import io.netty5.channel.ServerChannel;
import io.netty5.channel.ServerChannelFactory;
import io.netty5.channel.group.ChannelGroup;
import io.netty5.channel.socket.DatagramChannel;
import io.netty5.handler.logging.LogLevel;
import io.netty5.handler.logging.LoggingHandler;
import reactor.netty5.ChannelPipelineConfigurer;
import reactor.netty5.Connection;
import reactor.netty5.ConnectionObserver;
import reactor.netty5.channel.ChannelMetricsRecorder;
import reactor.netty5.channel.ChannelOperations;
import reactor.netty5.channel.MicrometerChannelMetricsRecorder;
import reactor.netty5.resources.LoopResources;
import reactor.netty5.transport.TransportConfig;
import reactor.netty5.transport.logging.AdvancedBufferFormat;
import reactor.util.annotation.Nullable;

import java.net.ProtocolFamily;
import java.net.SocketAddress;
import java.nio.charset.Charset;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Encapsulate all necessary configuration for UDP server transport. The public API is read-only.
 *
 * @author Violeta Georgieva
 * @since 1.0.0
 */
public final class UdpServerConfig extends TransportConfig {

	@Override
	public ChannelOperations.OnSetup channelOperationsProvider() {
		return DEFAULT_OPS;
	}

	/**
	 * Return the configured callback or null
	 *
	 * @return the configured callback or null
	 */
	@Nullable
	public final Consumer<? super UdpServerConfig> doOnBind() {
		return doOnBind;
	}

	/**
	 * Return the configured callback or null
	 *
	 * @return the configured callback or null
	 */
	@Nullable
	public final Consumer<? super Connection> doOnBound() {
		return doOnBound;
	}

	/**
	 * Return the configured callback or null
	 *
	 * @return the configured callback or null
	 */
	@Nullable
	public final Consumer<? super Connection> doOnUnbound() {
		return doOnUnbound;
	}


	// Protected/Package private write API

	Consumer<? super UdpServerConfig> doOnBind;
	Consumer<? super Connection>      doOnBound;
	Consumer<? super Connection>      doOnUnbound;

	UdpServerConfig(Map<ChannelOption<?>, ?> options, Supplier<? extends SocketAddress> bindAddress) {
		super(options, bindAddress);
	}

	UdpServerConfig(UdpServerConfig parent) {
		super(parent);
		this.doOnBind = parent.doOnBind;
		this.doOnBound = parent.doOnBound;
		this.doOnUnbound = parent.doOnUnbound;
	}

	@Override
	protected Class<? extends Channel> channelType() {
		return DatagramChannel.class;
	}

	@Override
	protected ConnectionObserver defaultConnectionObserver() {
		if (channelGroup() == null && doOnBound() == null && doOnUnbound() == null) {
			return ConnectionObserver.emptyListener();
		}
		return new UdpServerDoOn(channelGroup(), doOnBound(), doOnUnbound());
	}

	@Override
	protected LoggingHandler defaultLoggingHandler() {
		return LOGGING_HANDLER;
	}

	@Override
	protected LoopResources defaultLoopResources() {
		return UdpResources.get();
	}

	@Override
	protected ChannelMetricsRecorder defaultMetricsRecorder() {
		return MicrometerUdpServerMetricsRecorder.INSTANCE;
	}

	@Override
	protected ChannelPipelineConfigurer defaultOnChannelInit() {
		return ChannelPipelineConfigurer.emptyConfigurer();
	}

	@Override
	protected EventLoopGroup eventLoopGroup() {
		return loopResources().onClient(isPreferNative());
	}

	@Override
	protected Class<? extends ServerChannel> serverChannelType() {
		throw new UnsupportedOperationException();
	}

	@Override
	protected ServerChannelFactory<? extends ServerChannel> serverConnectionFactory(ProtocolFamily protocolFamily) {
		throw new UnsupportedOperationException();
	}

	static final ChannelOperations.OnSetup DEFAULT_OPS = (ch, c, msg) -> new UdpOperations(ch, c);

	static final LoggingHandler LOGGING_HANDLER =
			AdvancedBufferFormat.HEX_DUMP
					.toLoggingHandler(UdpServer.class.getName(), LogLevel.DEBUG, Charset.defaultCharset());

	static final class MicrometerUdpServerMetricsRecorder extends MicrometerChannelMetricsRecorder {

		static final MicrometerUdpServerMetricsRecorder INSTANCE = new MicrometerUdpServerMetricsRecorder();

		MicrometerUdpServerMetricsRecorder() {
			super(reactor.netty5.Metrics.UDP_SERVER_PREFIX, "udp");
		}
	}

	static final class UdpServerDoOn implements ConnectionObserver {

		final ChannelGroup                 channelGroup;
		final Consumer<? super Connection> doOnBound;
		final Consumer<? super Connection> doOnUnbound;

		UdpServerDoOn(@Nullable ChannelGroup channelGroup,
				@Nullable Consumer<? super Connection> doOnBound,
				@Nullable Consumer<? super Connection> doOnUnbound) {
			this.channelGroup = channelGroup;
			this.doOnBound = doOnBound;
			this.doOnUnbound = doOnUnbound;
		}

		@Override
		public void onStateChange(Connection connection, State newState) {
			if (channelGroup != null && newState == State.CONNECTED) {
				channelGroup.add(connection.channel());
				return;
			}
			if (doOnBound != null && newState == State.CONFIGURED) {
				doOnBound.accept(connection);
				return;
			}
			if (doOnUnbound != null && newState == State.DISCONNECTING) {
				connection.onDispose(() -> doOnUnbound.accept(connection));
			}
		}
	}
}
