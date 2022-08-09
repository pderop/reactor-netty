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
package reactor.netty5.resources;

import java.net.ProtocolFamily;
import java.nio.channels.spi.SelectorProvider;
import java.util.concurrent.ThreadFactory;

import io.netty5.channel.Channel;
import io.netty5.channel.EventLoop;
import io.netty5.channel.EventLoopGroup;
import io.netty5.channel.ServerChannel;
import io.netty5.channel.socket.DatagramChannel;
import io.netty5.channel.socket.ServerSocketChannel;
import io.netty5.channel.socket.SocketChannel;
import io.netty5.channel.socket.SocketProtocolFamily;
import io.netty5.channel.socket.nio.NioDatagramChannel;
import io.netty5.channel.socket.nio.NioServerSocketChannel;
import io.netty5.channel.socket.nio.NioSocketChannel;
import reactor.util.annotation.Nullable;

/**
 * {@link DefaultLoop} that uses {@code NIO} transport.
 *
 * @author Stephane Maldini
 * @author Violeta Georgieva
 * @since 0.9.8
 */
final class DefaultLoopNIO implements DefaultLoop {

	@Override
	@Nullable
	@SuppressWarnings("unchecked")
	public <CHANNEL extends Channel> CHANNEL getChannel(Class<CHANNEL> channelClass, EventLoop eventLoop,
			@Nullable ProtocolFamily protocolFamily) {
		if (channelClass.equals(SocketChannel.class)) {
			return eventLoop.isCompatible(NioSocketChannel.class) ?
					(CHANNEL) new NioSocketChannel(eventLoop, SelectorProvider.provider(), protocolFamily) : null;
		}
		if (channelClass.equals(DatagramChannel.class)) {
			if (protocolFamily == SocketProtocolFamily.UNIX) {
				throw new IllegalArgumentException("Channel type: NioDatagramChannel does not support Unix Domain Sockets");
			}
			return eventLoop.isCompatible(NioDatagramChannel.class) ?
					(CHANNEL) new NioDatagramChannel(eventLoop, SelectorProvider.provider(), protocolFamily) : null;
		}
		throw new IllegalArgumentException("Unsupported channel type: " + channelClass.getSimpleName());
	}

	@Override
	public String getName() {
		return "nio";
	}

	@Override
	@Nullable
	@SuppressWarnings("unchecked")
	public <SERVERCHANNEL extends ServerChannel> SERVERCHANNEL getServerChannel(Class<SERVERCHANNEL> channelClass, EventLoop eventLoop,
			EventLoopGroup childEventLoopGroup, @Nullable ProtocolFamily protocolFamily) {
		if (channelClass.equals(ServerSocketChannel.class)) {
			return eventLoop.isCompatible(NioServerSocketChannel.class) ?
					(SERVERCHANNEL) new NioServerSocketChannel(eventLoop, childEventLoopGroup,
							SelectorProvider.provider(), protocolFamily) : null;
		}
		throw new IllegalArgumentException("Unsupported channel type: " + channelClass.getSimpleName());
	}

	@Override
	public EventLoopGroup newEventLoopGroup(int threads, ThreadFactory factory) {
		throw new IllegalStateException("Missing Epoll/KQueue on current system");
	}
}