package com.elvarg;

import com.elvarg.game.GameBuilder;
import com.elvarg.game.GameConstants;
import com.elvarg.game.World;
import com.elvarg.game.entity.impl.npc.NPC;
import com.elvarg.game.model.Location;
import com.elvarg.net.NetworkBuilder;
import com.elvarg.net.NetworkConstants;
import com.elvarg.plugin.event.EventManager;
import com.elvarg.plugin.event.impl.ServerBootEvent;
import com.elvarg.plugin.event.impl.ServerStartedEvent;
import com.elvarg.rl.MinimalEnvironmentBot;
import com.elvarg.rl.MinimalSocketServer;
import com.elvarg.util.ShutdownHook;
import com.elvarg.util.flood.Flooder;

import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * The starting point of the application. Initializes the bootstrap.
 *
 * @author Professor Oak
 * @author Lare96
 */
public class Server {

    /**
     * The flooder used to stress-test the server.
     */
    private static final Flooder flooder = new Flooder();

    /**
     * Is the server running in production mode?
     */
    public static boolean PRODUCTION = false;

    /**
     * Enable various debugging logs?
     */
    public static boolean DEBUG_LOGGING = false;

    /**
     * The logger that will print important information.
     */
    private static Logger logger = Logger.getLogger(Server.class.getSimpleName());

    /**
     * The flag that determines if the server is currently being updated or not.
     */
    private static boolean updating = false;

    /**
     * The main method that will put the server online.
     */
    public static void main(String[] args) {
        try {
            Runtime.getRuntime().addShutdownHook(new ShutdownHook());

            if (args.length == 1) {
                PRODUCTION = Integer.parseInt(args[0]) == 1;
            }

            logger.info("Initializing " + GameConstants.NAME + " in " + (PRODUCTION ? "production" : "non-production") + " mode..");
            new GameBuilder().initialize();
            EventManager.INSTANCE.postAndWait(new ServerBootEvent());
            new NetworkBuilder().initialize(NetworkConstants.GAME_PORT);
            logger.info(GameConstants.NAME + " is now online!");
            // PlayerBot's own constructor already queues itself via World.getAddPlayerQueue()
            // (confirmed: PlayerBot.java:77-79) - do not add it again here. Traced and confirmed
            // harmless if done (MobileList.add()'s isRegistered() guard no-ops the second add,
            // so there's no double-processing), but redundant and worth not doing.
            MinimalEnvironmentBot bot = new MinimalEnvironmentBot(GameConstants.PLAYER_BOTS[0]);
            // Hobgoblin (npc id 3049), 1 tile east of the bot's spawn (3089, 3466) - non-diagonal,
            // so Chebyshev distance is unambiguously 1 regardless of any diagonal-melee fidelity
            // question. (3089/3090, 3466) is verified non-wilderness, non-multi, walkable, and
            // >55 tiles from the nearest default NPC spawn (see MinimalEnvironmentBot.withMeleeLoadout()
            // for the matching bot-side spawn override and the verification evidence).
            NPC target = NPC.create(3049, new Location(3090, 3466));
            World.getAddNPCQueue().add(target);
            bot.setTarget(target);
            // PURSUIT-RACE FIX + PATHFINDING CORRECTION pass (PROJECT_STATE.md section 13): NPC.create()
            // bypasses NpcSpawnDefinitionLoader (json-spawn-only), so NPCMovementCoordinator.radius was
            // never set and stayed at Java's default 0 -- a degenerate leash (NPCMovementCoordinator.java's
            // AWAY/RETREATING transition fires on ANY nonzero drift from spawn) that both routes the NPC
            // via the REAL PathFinder toward its spawn tile and calls npc.getCombat().reset() every tick
            // while RETREATING+inCombat, independent of and compounding the already-fixed pursuit race.
            // 6 is the mode among the NONZERO radius values on the closest real precedent in
            // data/definitions/npc_spawns.json: no id=3049 spawn exists there, but 11 real spawns of
            // Hobgoblin id=2241 (same species, combatFollowDistance=7 like ours) do -- radius distribution
            // {0:5, 6:3, 1:1, 2:1, 3:1}; 6 is both the nonzero mode and comfortably covers the arena's
            // designed cover-site distance (Chebyshev 4 from spawn) without being an unbounded/fabricated
            // leash. Approximation pending an OSRS Wiki cross-check, not a Java default omission anymore.
            target.getMovementCoordinator().setRadius(6);
            new MinimalSocketServer(7070).start();
            EventManager.INSTANCE.post(new ServerStartedEvent());
        } catch (Exception e) {
            logger.log(Level.SEVERE, "An error occurred while binding the Bootstrap!", e);

            // No point in continuing server startup when the
            // bootstrap either failed to bind or was bound
            // incorrectly.
            System.exit(1);
        }
    }

    public static void logDebug(String logMessage) {
        if (!DEBUG_LOGGING) {
            return;
        }

        getLogger().info(logMessage);
    }

    public static Logger getLogger() {
        return logger;
    }

    public static boolean isUpdating() {
        return updating;
    }

    public static void setUpdating(boolean isUpdating) {
        Server.updating = isUpdating;
    }

    public static Flooder getFlooder() {
        return flooder;
    }
}