package com.elvarg.rl;

import com.elvarg.game.World;
import com.elvarg.game.content.skill.SkillManager;
import com.elvarg.game.entity.impl.player.Player;
import com.elvarg.game.model.Item;
import com.elvarg.game.model.Skill;
import com.elvarg.game.task.Task;
import com.elvarg.game.task.TaskManager;
import com.elvarg.net.PlayerBotSession;
import com.elvarg.net.login.LoginDetailsMessage;
import com.elvarg.net.login.LoginResponses;
import com.elvarg.util.Misc;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.LineBasedFrameDecoder;
import io.netty.handler.codec.string.StringDecoder;
import io.netty.handler.codec.string.StringEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * M1 harness: persistent, stock-path accounts for the learning agent (docs/M1_HARNESS_CONTRACT.md).
 *
 * Unlike {@link MinimalEnvironmentBot} this performs NO arena resets and NO stat restores. An account is
 * created or loaded through the stock {@link LoginResponses#evaluate} path (same persistence, same
 * new-account setup), runs as an ordinary player in the world, and is saved through the stock
 * {@code Player.onLogout} path. Only started when the HARNESS_PORT environment variable is set, so the
 * default server behaviour is unchanged.
 *
 * Commands are one JSON line each ({"cmd":"login|state|logout|save", "user":..., "pass":...}); every
 * command is executed on the game thread by a recurring task, never on the netty thread.
 * Observation (state) contains only what a player can see: skill levels/XP, inventory, equipment, position.
 */
public final class HarnessServer {

	private static final Logger logger = Logger.getLogger(HarnessServer.class.getSimpleName());
	public static final int PROTOCOL_VERSION = 1;
	private static final Map<String, Player> accounts = new ConcurrentHashMap<>();
	private static final Queue<Pending> queue = new ConcurrentLinkedQueue<>();
	private static long tickCount = 0;

	private record Pending(String line, java.util.concurrent.CompletableFuture<String> reply) {
	}

	private HarnessServer() {
	}

	public static void start(int port) {
		TaskManager.submit(new Task(1, "harness-drain", false) {
			@Override
			protected void execute() {
				tickCount++;
				Pending p;
				while ((p = queue.poll()) != null) {
					String out;
					try {
						out = handle(p.line());
					} catch (Throwable t) {
						out = "{\"ok\":false,\"error\":\"" + esc(String.valueOf(t)) + "\"}";
					}
					p.reply().complete(out);
				}
			}
		});
		EventLoopGroup group = new NioEventLoopGroup(1);
		ServerBootstrap b = new ServerBootstrap().group(group).channel(NioServerSocketChannel.class)
				.childHandler(new ChannelInitializer<SocketChannel>() {
					@Override
					protected void initChannel(SocketChannel ch) {
						ch.pipeline().addLast(new LineBasedFrameDecoder(8192), new StringDecoder(StandardCharsets.UTF_8),
								new StringEncoder(StandardCharsets.UTF_8), new SimpleChannelInboundHandler<String>() {
									@Override
									protected void channelRead0(ChannelHandlerContext ctx, String msg) {
										var f = new java.util.concurrent.CompletableFuture<String>();
										queue.add(new Pending(msg, f));
										f.whenCompleteAsync((r, e) -> ctx.writeAndFlush((e == null ? r : "{\"ok\":false}") + "\n"),
												ctx.executor());
									}
								});
					}
				});
		try {
			Channel c = b.bind(port).sync().channel();
			logger.info("[Harness] listening on port " + port);
		} catch (InterruptedException e) {
			throw new RuntimeException(e);
		}
	}

	private static String field(String line, String name) {
		Matcher m = Pattern.compile("\"" + name + "\"\\s*:\\s*\"([^\"]*)\"").matcher(line);
		return m.find() ? m.group(1) : null;
	}

	private static String esc(String s) {
		return s.replace("\\", "\\\\").replace("\"", "'").replace("\n", " ");
	}

	private static String handle(String line) {
		String cmd = field(line, "cmd");
		String user = field(line, "user");
		if (cmd == null) {
			return "{\"ok\":false,\"error\":\"no cmd\"}";
		}
		switch (cmd) {
			case "login": {
				String pass = field(line, "pass");
				if (user == null || pass == null) {
					return "{\"ok\":false,\"error\":\"user/pass required\"}";
				}
				if (accounts.containsKey(user)) {
					return "{\"ok\":false,\"error\":\"already logged in\"}";
				}
				Player p = new Player(new PlayerBotSession());
				p.setUsername(user).setLongUsername(Misc.stringToLong(user)).setHostAddress("127.0.0.1");
				LoginDetailsMessage msg = new LoginDetailsMessage(null, user, pass, "127.0.0.1", null, null);
				int res = LoginResponses.evaluate(p, msg);
				if (res != LoginResponses.LOGIN_SUCCESSFUL) {
					return "{\"ok\":false,\"error\":\"login response " + res + "\"}";
				}
				accounts.put(user, p);
				World.getAddPlayerQueue().add(p);
				return "{\"ok\":true,\"new\":" + p.isNewPlayer() + ",\"protocol_version\":" + PROTOCOL_VERSION + "}";
			}
			case "state": {
				Player p = user == null ? null : accounts.get(user);
				if (p == null) {
					return "{\"ok\":false,\"error\":\"not logged in\"}";
				}
				return state(p);
			}
			case "save": {
				Player p = user == null ? null : accounts.get(user);
				if (p == null) {
					return "{\"ok\":false,\"error\":\"not logged in\"}";
				}
				com.elvarg.game.GameConstants.PLAYER_PERSISTENCE.save(p);
				return "{\"ok\":true}";
			}
			case "logout": {
				Player p = user == null ? null : accounts.remove(user);
				if (p == null) {
					return "{\"ok\":false,\"error\":\"not logged in\"}";
				}
				World.getRemovePlayerQueue().add(p);
				return "{\"ok\":true}";
			}
			default:
				return "{\"ok\":false,\"error\":\"unknown cmd\"}";
		}
	}

	private static String items(Item[] arr) {
		StringBuilder sb = new StringBuilder("[");
		boolean first = true;
		for (int i = 0; i < arr.length; i++) {
			Item it = arr[i];
			if (it == null || it.getId() <= 0) {
				continue;
			}
			sb.append(first ? "" : ",").append("[").append(i).append(",").append(it.getId()).append(",")
					.append(it.getAmount()).append("]");
			first = false;
		}
		return sb.append("]").toString();
	}

	private static String state(Player p) {
		SkillManager sm = p.getSkillManager();
		StringBuilder sk = new StringBuilder("{");
		boolean first = true;
		for (Skill s : Skill.values()) {
			sk.append(first ? "" : ",").append("\"").append(s.name()).append("\":[").append(sm.getCurrentLevel(s)).append(",")
					.append(sm.getMaxLevel(s)).append(",").append(sm.getExperience(s)).append("]");
			first = false;
		}
		sk.append("}");
		return "{\"ok\":true,\"protocol_version\":" + PROTOCOL_VERSION + ",\"game_tick\":" + tickCount
				+ ",\"registered\":" + p.isRegistered() + ",\"x\":" + p.getLocation().getX() + ",\"y\":"
				+ p.getLocation().getY() + ",\"z\":" + p.getLocation().getZ() + ",\"skills\":" + sk + ",\"inventory\":"
				+ items(p.getInventory().getItems()) + ",\"equipment\":" + items(p.getEquipment().getItems()) + "}";
	}
}
