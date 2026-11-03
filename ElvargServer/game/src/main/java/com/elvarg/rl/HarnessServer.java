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

	public static long currentTick() {
		return tickCount;
	}

	private record Pending(String line, java.util.concurrent.CompletableFuture<String> reply) {
	}

	private record Deferred(Pending p, long dueTick) {
	}

	private static final Map<String, java.util.List<com.elvarg.game.entity.impl.npc.NPC>> devNpcs = new ConcurrentHashMap<>();
	private static final java.util.List<Deferred> deferred = new java.util.ArrayList<>();

	private static void run(Pending p) {
		String out;
		try {
			out = handle(p.line());
		} catch (Throwable t) {
			out = "{\"ok\":false,\"error\":\"" + esc(String.valueOf(t)) + "\"}";
		}
		p.reply().complete(out);
	}

	private HarnessServer() {
	}

	public static void start(int port) {
		TaskManager.submit(new Task(1, "harness-drain", false) {
			@Override
			protected void execute() {
				tickCount++;
				// Deferred commands (wait_ticks=k): answered at the drain stage of the tick k ticks after they were
				// received, so a learner can step the world a fixed number of game ticks per decision.
				java.util.Iterator<Deferred> it = deferred.iterator();
				while (it.hasNext()) {
					Deferred d = it.next();
					if (tickCount >= d.dueTick()) {
						it.remove();
						run(d.p());
					}
				}
				Pending p;
				while ((p = queue.poll()) != null) {
					Integer wait = intField(p.line(), "wait_ticks");
					if (wait != null && wait > 0 && wait <= 600) {
						deferred.add(new Deferred(p, tickCount + wait));
					} else {
						run(p);
					}
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
									// Accounts logged in over this connection: logged out (and saved through the stock
									// path) if the client disconnects without a logout, so sessions cannot leak.
									private final java.util.Set<String> mine = ConcurrentHashMap.newKeySet();

									@Override
									protected void channelRead0(ChannelHandlerContext ctx, String msg) {
										var f = new java.util.concurrent.CompletableFuture<String>();
										queue.add(new Pending(msg, f));
										String cmd = field(msg, "cmd");
										String u = field(msg, "user");
										f.whenCompleteAsync((r, e) -> {
											if (e == null && r != null && r.startsWith("{\"ok\":true") && u != null) {
												if ("login".equals(cmd)) {
													mine.add(u);
												} else if ("logout".equals(cmd)) {
													mine.remove(u);
												}
											}
											ctx.writeAndFlush((e == null ? r : "{\"ok\":false}") + "\n");
										}, ctx.executor());
									}

									@Override
									public void channelInactive(ChannelHandlerContext ctx) throws Exception {
										for (String u : mine) {
											queue.add(new Pending("{\"cmd\":\"logout\",\"user\":\"" + u + "\"}",
													new java.util.concurrent.CompletableFuture<String>()));
										}
										mine.clear();
										super.channelInactive(ctx);
									}
								});
					}
				});
		try {
			Channel c = b.bind("127.0.0.1", port).sync().channel();
			logger.info("[Harness] listening on port " + port);
		} catch (InterruptedException e) {
			throw new RuntimeException(e);
		}
	}

	private static String field(String line, String name) {
		Matcher m = Pattern.compile("\"" + name + "\"\\s*:\\s*\"([^\"]*)\"").matcher(line);
		return m.find() ? m.group(1) : null;
	}

	private static Integer intField(String line, String name) {
		Matcher m = Pattern.compile("\"" + name + "\"\\s*:\\s*(-?\\d+)").matcher(line);
		return m.find() ? Integer.valueOf(m.group(1)) : null;
	}

	/** NPCs within 15 tiles on the same plane: what a player sees on screen (index, id, position, health bar). */
	private static String npcs(Player p) {
		StringBuilder sb = new StringBuilder("[");
		boolean first = true;
		for (com.elvarg.game.entity.impl.npc.NPC n : World.getNpcs()) {
			// Visible like on the client: same plane, within the view distance, same private area (instance).
			if (n == null || n.isDying() || n.getHitpoints() <= 0 || n.getLocation().getZ() != p.getLocation().getZ()
					|| n.getLocation().getDistance(p.getLocation()) > 15 || n.getPrivateArea() != p.getPrivateArea()) {
				continue;
			}
			sb.append(first ? "" : ",").append("[").append(n.getIndex()).append(",").append(n.getId()).append(",")
					.append(n.getLocation().getX()).append(",").append(n.getLocation().getY()).append(",")
					.append(healthBarShown(n)).append(",")
					.append(n.getDefinition() != null && n.getDefinition().isAttackable() ? 1 : 0).append(",")
					.append(n.getDefinition() == null ? 0 : n.getDefinition().getCombatLevel()).append("]");
			first = false;
		}
		return sb.append("]").toString();
	}

	/**
	 * The bar a player actually sees: the client only draws an NPC's health bar for about 6 seconds (10 ticks) after it
	 * took damage; otherwise nothing is shown and the value is 0 (a shown bar is always at least 1 pixel).
	 */
	private static int healthBarShown(com.elvarg.game.entity.impl.npc.NPC n) {
		return tickCount - n.lastDamagedGameTick <= 10 ? healthBar(n) : 0;
	}

	/** Health bar as a player sees it: 0..30 pixels, never the exact hitpoints. */
	private static int healthBar(com.elvarg.game.entity.impl.npc.NPC n) {
		int max = Math.max(1, n.getDefinition() == null ? n.getHitpoints() : n.getDefinition().getHitpoints());
		return (int) Math.ceil(30.0 * Math.min(n.getHitpoints(), max) / max);
	}

	private static String esc(String s) {
		return s.replace("\\", "\\\\").replace("\"", "'").replace("\n", " ");
	}

	private static String handle(String line) {
		String cmd = field(line, "cmd");
		String user = field(line, "user");
		if (user != null && !user.matches("[A-Za-z0-9_]{1,12}")) {
			return "{\"ok\":false,\"error\":\"bad user\"}";
		}
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
				// New accounts start at Lumbridge (3222,3218), like the real game after Tutorial Island; saved accounts load their own position.
				String spawnEnv = System.getenv("HARNESS_NEW_ACCOUNT_SPAWN");   // "x,y" override (tests); default Lumbridge
				int sx = 3222, sy = 3218;
				if (spawnEnv != null && spawnEnv.matches("\\d+,\\d+")) {
					sx = Integer.parseInt(spawnEnv.split(",")[0]);
					sy = Integer.parseInt(spawnEnv.split(",")[1]);
				}
				Player p = new Player(new PlayerBotSession(), new com.elvarg.game.model.Location(sx, sy));
				p.setHarnessAccount(true);
				p.setUsername(user).setLongUsername(Misc.stringToLong(user)).setHostAddress("127.0.0.1");
				LoginDetailsMessage msg = new LoginDetailsMessage(null, user, pass, "127.0.0.1", null, null);
				int res = LoginResponses.evaluate(p, msg);
				if (res != LoginResponses.LOGIN_SUCCESSFUL) {
					return "{\"ok\":false,\"error\":\"login response " + res + "\"}";
				}
				if (p.isNewPlayer() && "reward_list".equals(System.getenv("STARTER_KIT"))) {
					grantStarterKit(p);
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
			case "walk": {
				Player p = user == null ? null : accounts.get(user);
				Integer dx = intField(line, "dx"), dy = intField(line, "dy");
				if (p == null || dx == null || dy == null || Math.abs(dx) > 1 || Math.abs(dy) > 1) {
					return "{\"ok\":false,\"error\":\"bad walk\"}";
				}
				p.getMovementQueue().reset();
				p.getMovementQueue().walkToReset();
				com.elvarg.game.model.Location cur = p.getLocation();
				if (!com.elvarg.game.collision.RegionManager.canMove(cur, cur.transform(dx, dy), p.size(), p.size(),
						p.getPrivateArea())) {
					return "{\"ok\":true,\"moved\":false}";
				}
				p.getMovementQueue().walkStep(dx, dy);
				return "{\"ok\":true,\"moved\":true}";
			}
			case "attack": {
				Player p = user == null ? null : accounts.get(user);
				Integer idx = intField(line, "npc");
				if (p == null || idx == null) {
					return "{\"ok\":false,\"error\":\"bad attack\"}";
				}
				for (com.elvarg.game.entity.impl.npc.NPC n : World.getNpcs()) {
					if (n != null && n.getIndex() == idx) {
						if (n.getDefinition() == null || !n.getDefinition().isAttackable()) {
							return "{\"ok\":false,\"error\":\"not attackable\"}";
						}
						if (n.isDying() || n.getHitpoints() <= 0 || p.getHitpoints() <= 0) {
							return "{\"ok\":false,\"error\":\"cannot attack\"}";
						}
						p.getCombat().attack(n);
						return "{\"ok\":true}";
					}
				}
				return "{\"ok\":false,\"error\":\"no such npc\"}";
			}
			case "teleport": {
				// Same selection the client sends from the teleport interface: Teleportable by name (e.g. "BARROWS").
				Player p = user == null ? null : accounts.get(user);
				String name = field(line, "name");
				if (p == null || name == null) {
					return "{\"ok\":false,\"error\":\"bad teleport\"}";
				}
				com.elvarg.game.model.teleportation.Teleportable t;
				try {
					t = com.elvarg.game.model.teleportation.Teleportable.valueOf(name);
				} catch (IllegalArgumentException e) {
					return "{\"ok\":false,\"error\":\"unknown destination\"}";
				}
				if (!com.elvarg.game.model.teleportation.TeleportHandler.checkReqs(p, t.getPosition())) {
					return "{\"ok\":false,\"error\":\"teleport refused\"}";
				}
				p.getPreviousTeleports().put(t.getTeleportButton(), t.getPosition());
				com.elvarg.game.model.teleportation.TeleportHandler.teleport(p, t.getPosition(), p.getSpellbook().getTeleportType(), true);
				return "{\"ok\":true}";
			}
			case "packet": {
				// Feeds a real client packet (opcode + raw payload hex) to the stock packet listener, exactly as the
				// network decoder would. Player-action path for everything (interfaces, shops, bank, objects).
				Player p = user == null ? null : accounts.get(user);
				Integer op = intField(line, "opcode");
				String hex = field(line, "hex");
				if (p == null || op == null || hex == null || op < 0 || op > 256
						|| com.elvarg.net.packet.PacketConstants.PACKETS[op] == null || !hex.matches("([0-9a-fA-F]{2})*")) {
					return "{\"ok\":false,\"error\":\"bad packet\"}";
				}
				byte[] bytes = new byte[hex.length() / 2];
				for (int i = 0; i < bytes.length; i++) {
					bytes[i] = (byte) Integer.parseInt(hex.substring(2 * i, 2 * i + 2), 16);
				}
				com.elvarg.net.packet.Packet pkt = new com.elvarg.net.packet.Packet(op, io.netty.buffer.Unpooled.wrappedBuffer(bytes));
				try {
					// Executed by the stock listener on the game thread (same code path processPackets uses).
					com.elvarg.net.packet.PacketConstants.PACKETS[op].execute(p, pkt);
				} catch (Throwable t) {
					return "{\"ok\":false,\"error\":\"listener threw " + esc(String.valueOf(t)) + "\"}";
				} finally {
					pkt.getBuffer().release();
				}
				return "{\"ok\":true}";
			}
			case "diag_reach": {
				// DIAGNOSTIC ONLY (never a policy input): BFS over the static collision map from (sx,sy,z) inside a search
				// window, reports whether a target box is reachable on foot and the path length to it.
				Integer sx = intField(line, "sx"), sy = intField(line, "sy"), z = intField(line, "z");
				Integer wx0 = intField(line, "wx0"), wx1 = intField(line, "wx1"), wy0 = intField(line, "wy0"), wy1 = intField(line, "wy1");
				Integer tx0 = intField(line, "tx0"), tx1 = intField(line, "tx1"), ty0 = intField(line, "ty0"), ty1 = intField(line, "ty1");
				if (sx == null || sy == null || z == null || wx0 == null || wx1 == null || wy0 == null || wy1 == null
						|| tx0 == null || tx1 == null || ty0 == null || ty1 == null || (wx1 - wx0) * (wy1 - wy0) > 4_000_000) {
					return "{\"ok\":false,\"error\":\"bad diag_reach\"}";
				}
				int w = wx1 - wx0 + 1, h = wy1 - wy0 + 1;
				int[] dist = new int[w * h];
				java.util.Arrays.fill(dist, -1);
				java.util.ArrayDeque<int[]> dq = new java.util.ArrayDeque<>();
				dist[(sy - wy0) * w + (sx - wx0)] = 0;
				dq.add(new int[] { sx, sy });
				int reached = 0, best = -1;
				com.elvarg.game.model.Direction[] dirs = com.elvarg.game.model.Direction.values();
				while (!dq.isEmpty()) {
					int[] c = dq.poll();
					int d0 = dist[(c[1] - wy0) * w + (c[0] - wx0)];
					reached++;
					if (c[0] >= tx0 && c[0] <= tx1 && c[1] >= ty0 && c[1] <= ty1 && best < 0) {
						best = d0;
					}
					com.elvarg.game.model.Location here = new com.elvarg.game.model.Location(c[0], c[1], z);
					for (com.elvarg.game.model.Direction d : dirs) {
						if (d.getId() < 0 || d.getId() > 7 || (d.getX() == 0 && d.getY() == 0)) {
							continue;
						}
						int nx = c[0] + d.getX(), ny = c[1] + d.getY();
						if (nx < wx0 || nx > wx1 || ny < wy0 || ny > wy1 || dist[(ny - wy0) * w + (nx - wx0)] >= 0) {
							continue;
						}
						if (!com.elvarg.game.collision.RegionManager.canMove(here, d.getId(), null)) {
							continue;
						}
						if (com.elvarg.game.collision.RegionManager.blocked(nx, ny, z)) {
							continue;
						}
						dist[(ny - wy0) * w + (nx - wx0)] = d0 + 1;
						dq.add(new int[] { nx, ny });
					}
				}
				return "{\"ok\":true,\"reachable_tiles\":" + reached + ",\"target_reachable\":" + (best >= 0)
						+ ",\"path_length\":" + best + "}";
			}
			case "diag_objects": {
				// DIAGNOSTIC ONLY (never a policy input): static map objects in a box on one plane, for content verification.
				Integer ox0 = intField(line, "x0"), ox1 = intField(line, "x1"), oy0 = intField(line, "y0"), oy1 = intField(line, "y1"), oz = intField(line, "z");
				if (ox0 == null || ox1 == null || oy0 == null || oy1 == null || oz == null || (ox1 - ox0 + 1) * (oy1 - oy0 + 1) > 40000) {
					return "{\"ok\":false,\"error\":\"bad diag_objects\"}";
				}
				com.elvarg.game.collision.RegionManager.loadMapFiles(ox0, oy0);
				com.elvarg.game.collision.RegionManager.loadMapFiles(ox1, oy1);
				StringBuilder sb = new StringBuilder("{\"ok\":true,\"objects\":[");
				boolean first = true;
				for (int ox = ox0; ox <= ox1; ox++) {
					for (int oy = oy0; oy <= oy1; oy++) {
						java.util.ArrayList<com.elvarg.game.entity.impl.object.GameObject> l = com.elvarg.game.entity.impl.object.MapObjects.mapObjects
								.get(com.elvarg.game.entity.impl.object.MapObjects.getHash(ox, oy, oz));
						if (l == null) {
							continue;
						}
						for (com.elvarg.game.entity.impl.object.GameObject go : l) {
							sb.append(first ? "" : ",").append("[").append(go.getId()).append(",").append(ox).append(",").append(oy)
									.append(",").append(go.getType()).append(",").append(go.getFace()).append(",\"")
									.append(esc(String.valueOf(com.elvarg.game.definition.ObjectDefinition.forId(go.getId()) == null ? "" : com.elvarg.game.definition.ObjectDefinition.forId(go.getId()).name))).append("\"]");
							first = false;
						}
					}
				}
				return sb.append("]}").toString();
			}
			case "dev_clear": {
				// DEV SANDBOX ONLY: removes every NPC this account's dev_spawn created (no respawn is pending for them).
				if (!"1".equals(System.getenv("HARNESS_DEV"))) {
					return "{\"ok\":false,\"error\":\"dev commands disabled\"}";
				}
				java.util.List<com.elvarg.game.entity.impl.npc.NPC> l = user == null ? null : devNpcs.remove(user);
				int n = 0;
				if (l != null) {
					for (com.elvarg.game.entity.impl.npc.NPC x : l) {
						if (x.isRegistered()) {
							x.remove();
							n++;
						}
					}
				}
				return "{\"ok\":true,\"removed\":" + n + "}";
			}
			case "diag_ground": {
				// DIAGNOSTIC ONLY: ground items in a box on one plane (id, amount, tile, state, pending-removal).
				Integer gx0 = intField(line, "x0"), gx1 = intField(line, "x1"), gy0 = intField(line, "y0"), gy1 = intField(line, "y1"), gz = intField(line, "z");
				if (gx0 == null || gx1 == null || gy0 == null || gy1 == null || gz == null) {
					return "{\"ok\":false,\"error\":\"bad diag_ground\"}";
				}
				StringBuilder sb = new StringBuilder("{\"ok\":true,\"items\":[");
				boolean first = true;
				java.util.Iterator<com.elvarg.game.entity.impl.grounditem.ItemOnGround> it = com.elvarg.game.World.getItems().iterator();
				while (it.hasNext()) {
					com.elvarg.game.entity.impl.grounditem.ItemOnGround g = it.next();
					if (g == null) {
						continue;
					}
					com.elvarg.game.model.Location gl = g.getLocation();
					if (gl.getZ() != gz || gl.getX() < gx0 || gl.getX() > gx1 || gl.getY() < gy0 || gl.getY() > gy1) {
						continue;
					}
					sb.append(first ? "" : ",").append("[").append(g.getItem().getId()).append(",").append(g.getItem().getAmount()).append(",")
							.append(gl.getX()).append(",").append(gl.getY()).append(",\"").append(g.getState()).append("\",")
							.append(g.isPendingRemoval()).append("]");
					first = false;
				}
				return sb.append("]}").toString();
			}
			case "dev_move": {
				// DEV SANDBOX ONLY: places the account on a tile (probe setup; never used in training).
				if (!"1".equals(System.getenv("HARNESS_DEV"))) {
					return "{\"ok\":false,\"error\":\"dev commands disabled\"}";
				}
				Player p = user == null ? null : accounts.get(user);
				Integer mx = intField(line, "x"), my = intField(line, "y"), mz = intField(line, "z");
				if (p == null || mx == null || my == null || mz == null) {
					return "{\"ok\":false,\"error\":\"bad dev_move\"}";
				}
				p.moveTo(new com.elvarg.game.model.Location(mx, my, mz));
				return "{\"ok\":true}";
			}
			case "dev_hurt": {
				// DEV SANDBOX ONLY: sets current hitpoints (to set up eating tests; never used in training).
				if (!"1".equals(System.getenv("HARNESS_DEV"))) {
					return "{\"ok\":false,\"error\":\"dev commands disabled\"}";
				}
				Player p = user == null ? null : accounts.get(user);
				Integer hp = intField(line, "hp");
				if (p == null || hp == null || hp < 1) {
					return "{\"ok\":false,\"error\":\"bad dev_hurt\"}";
				}
				p.setHitpoints(hp);
				return "{\"ok\":true,\"hp\":" + p.getHitpoints() + "}";
			}
			case "dev_give": {
				// DEV SANDBOX ONLY: puts items in the inventory (e.g. food) so recovery behaviour can be learned.
				if (!"1".equals(System.getenv("HARNESS_DEV"))) {
					return "{\"ok\":false,\"error\":\"dev commands disabled\"}";
				}
				Player p = user == null ? null : accounts.get(user);
				Integer id = intField(line, "item_id"), amt = intField(line, "amount");
				if (p == null || id == null || amt == null || amt < 1 || amt > 100) {
					return "{\"ok\":false,\"error\":\"bad dev_give\"}";
				}
				p.getInventory().add(id, amt);
				p.getInventory().refreshItems();
				return "{\"ok\":true}";
			}
			case "dev_spawn": {
				// DEV SANDBOX ONLY (env HARNESS_DEV=1): spawns an NPC near the account for combat-learning experiments.
				// Never used in a certification run; reports must label any run that used it.
				if (!"1".equals(System.getenv("HARNESS_DEV"))) {
					return "{\"ok\":false,\"error\":\"dev commands disabled\"}";
				}
				Player p = user == null ? null : accounts.get(user);
				Integer id = intField(line, "npc_id"), dx = intField(line, "dx"), dy = intField(line, "dy");
				if (p == null || id == null || dx == null || dy == null || Math.abs(dx) > 10 || Math.abs(dy) > 10) {
					return "{\"ok\":false,\"error\":\"bad dev_spawn\"}";
				}
				com.elvarg.game.model.Location at = new com.elvarg.game.model.Location(p.getLocation().getX() + dx,
						p.getLocation().getY() + dy, p.getLocation().getZ());
				com.elvarg.game.entity.impl.npc.NPC n = com.elvarg.game.entity.impl.npc.NPC.create(id, at);
				n.devNoRespawn = true;
				devNpcs.computeIfAbsent(user, k -> new java.util.ArrayList<>()).add(n);
				World.getAddNPCQueue().add(n);
				return "{\"ok\":true}";
			}
			case "eat": {
				Player p = user == null ? null : accounts.get(user);
				Integer slot = intField(line, "slot");
				if (p == null || slot == null || slot < 0 || slot >= p.getInventory().capacity()) {
					return "{\"ok\":false,\"error\":\"bad eat\"}";
				}
				Item it = p.getInventory().getItems()[slot];
				boolean ate = false;
				if (it != null && it.getId() > 0) {
					int before = p.getInventory().getAmount(it.getId());
					com.elvarg.game.content.Food.consume(p, it.getId(), slot);
					ate = p.getInventory().getAmount(it.getId()) < before;
				}
				return "{\"ok\":true,\"ate\":" + ate + "}";
			}
			case "equip": {
				Player p = user == null ? null : accounts.get(user);
				Integer slot = intField(line, "slot");
				if (p == null || slot == null || slot < 0 || slot >= p.getInventory().capacity()) {
					return "{\"ok\":false,\"error\":\"bad equip\"}";
				}
				Item it = p.getInventory().getItems()[slot];
				if (it == null || it.getId() <= 0) {
					return "{\"ok\":false,\"error\":\"empty slot\"}";
				}
				com.elvarg.net.packet.impl.EquipPacketListener.equip(p, it.getId(), slot,
						com.elvarg.game.model.container.impl.Inventory.INTERFACE_ID);
				Item after = p.getInventory().getItems()[slot];
				boolean equipped = after == null || after.getId() != it.getId();
				return "{\"ok\":true,\"equipped\":" + equipped + "}";
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

	private static boolean bankOpen(Player p) {
		return p.getStatus() == com.elvarg.game.model.PlayerStatus.BANKING && p.getInterfaceId() == 5292;
	}

	/** Bank contents as [tab, slot, id, amount], visible only while the bank interface is open. */
	private static String bank(Player p) {
		StringBuilder sb = new StringBuilder("[");
		boolean first = true;
		com.elvarg.game.model.container.impl.Bank[] banks = p.getBanks();
		for (int t = 0; t < banks.length; t++) {
			if (banks[t] == null) {
				continue;
			}
			Item[] arr = banks[t].getItems();
			for (int i = 0; i < arr.length; i++) {
				if (arr[i] == null || arr[i].getId() <= 0) {
					continue;
				}
				sb.append(first ? "" : ",").append("[").append(t).append(",").append(i).append(",").append(arr[i].getId())
						.append(",").append(arr[i].getAmount()).append("]");
				first = false;
			}
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
				+ items(p.getInventory().getItems()) + ",\"equipment\":" + items(p.getEquipment().getItems()) + ",\"npcs\":" + npcs(p) + ",\"interface_id\":" + p.getInterfaceId() + ",\"deaths\":" + p.harnessDeaths + ",\"busy\":" + p.busy() + ",\"bank_open\":" + bankOpen(p) + ",\"bank\":" + (bankOpen(p) ? bank(p) : "null") + "}";
	}

	/**
	 * One-time kit for a NEW harness account only (env STARTER_KIT=reward_list). Items are the OSRS Wiki
	 * "Learning the Ropes" reward list (inventory) plus 25 coins in the bank. Placement and empty equipment are
	 * initialization choices, not verified arrival facts. Never called for a loaded (retained) character.
	 */
	private static void grantStarterKit(Player p) {
		int[][] kit = {{1351, 1}, {1265, 1}, {590, 1}, {303, 1}, {315, 1}, {1205, 1}, {1277, 1}, {1171, 1},
				{841, 1}, {882, 25}, {556, 25}, {558, 15}, {1925, 1}, {1931, 1}, {2309, 1}, {555, 6}, {557, 4},
				{559, 2}};
		for (int[] it : kit) {
			p.getInventory().add(new Item(it[0], it[1]), false);
		}
		p.getBank(0).add(new Item(995, 25), false);
	}
}
