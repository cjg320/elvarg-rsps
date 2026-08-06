package com.elvarg.rl;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.LineBasedFrameDecoder;
import io.netty.handler.codec.string.StringDecoder;
import io.netty.handler.codec.string.StringEncoder;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Logger;

/**
 * Minimal RL round-trip proof-of-concept socket server. Netty, newline-delimited text. Stage 3:
 * each received line is handed to the single {@link MinimalEnvironmentBot} instance's
 * queueMessage(), and the response is written back once that bot's game-tick thread resolves the
 * step's deferred future - so this handler never blocks and never touches game state itself; it
 * only hands off and reacts to completion (trimmed near-verbatim from the naton1-reference's
 * SimpleSocketServer.java transport layer, without its Gson-based multi-route dispatch).
 */
public class MinimalSocketServer {

	private static final Logger logger = Logger.getLogger(MinimalSocketServer.class.getSimpleName());

	private final int port;

	private Channel channel;
	private EventLoopGroup workerGroup;

	public MinimalSocketServer(int port) {
		this.port = port;
	}

	public synchronized void start() {
		if (this.channel != null) {
			throw new IllegalStateException("Already started");
		}
		this.workerGroup = new NioEventLoopGroup();
		final ServerBootstrap bootstrap = new ServerBootstrap();
		bootstrap
				.group(workerGroup)
				.channel(NioServerSocketChannel.class)
				.childHandler(new ChannelInitializer<SocketChannel>() {
					@Override
					protected void initChannel(SocketChannel ch) {
						ch.pipeline().addLast(new LineBasedFrameDecoder(8192));
						ch.pipeline().addLast(new StringDecoder(StandardCharsets.UTF_8));
						ch.pipeline().addLast(new StringEncoder(StandardCharsets.UTF_8));
						ch.pipeline().addLast(new MessageHandler());
					}
				})
				.childOption(ChannelOption.SO_REUSEADDR, true);
		try {
			final ChannelFuture future = bootstrap.bind(this.port).sync();
			this.channel = future.channel();
			logger.info("[MinimalEnv] socket server listening on port " + this.port);
		} catch (InterruptedException e) {
			throw new RuntimeException(e);
		}
	}

	public synchronized void close() {
		if (this.channel != null) {
			this.channel.close().syncUninterruptibly();
			this.workerGroup.shutdownGracefully().syncUninterruptibly();
			this.channel = null;
			this.workerGroup = null;
		}
	}

	private static class MessageHandler extends SimpleChannelInboundHandler<String> {

		@Override
		protected void channelRead0(ChannelHandlerContext ctx, String msg) {
			logger.info("[MinimalEnv] received line: " + msg);
			final MinimalEnvironmentBot bot = MinimalEnvironmentBot.getInstance();
			if (bot == null) {
				logger.warning("[MinimalEnv] no bot instance available, dropping message");
				return;
			}
			final CompletableFuture<String> future = new CompletableFuture<>();
			bot.queueMessage(msg, future);
			future.whenCompleteAsync((response, err) -> {
				if (err != null) {
					logger.warning("[MinimalEnv] step failed: " + err);
					ctx.writeAndFlush("{\"error\":\"" + err.getMessage() + "\"}\n");
				} else {
					ctx.writeAndFlush(response + "\n");
				}
			}, ctx.executor());
		}

		@Override
		public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
			logger.severe("[MinimalEnv] exception in channel: " + cause);
			ctx.close();
		}
	}
}
