package com.elvarg;

import com.elvarg.game.GameBuilder;
import com.elvarg.game.GameConstants;
import com.elvarg.game.World;
import com.elvarg.game.definition.NpcDefinition;
import com.elvarg.game.entity.impl.npc.NPC;
import com.elvarg.game.content.combat.Combat;
import com.elvarg.game.entity.impl.object.GameObject;
import com.elvarg.game.entity.impl.object.ObjectManager;
import com.elvarg.net.NetworkBuilder;
import com.elvarg.net.NetworkConstants;
import com.elvarg.plugin.event.EventManager;
import com.elvarg.plugin.event.impl.ServerBootEvent;
import com.elvarg.plugin.event.impl.ServerStartedEvent;
import com.elvarg.rl.ArenaDefinition;
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
            // ARENA 01 -- CHOREOGRAPHY + ARENA DEFINITION pass (PROJECT_STATE.md section 13): bot
            // spawn, NPC id/spawn, NPCMovementCoordinator radius, and (new with ARENA_01) the wall
            // obstacle list all now come from a selected ArenaDefinition rather than being hardcoded
            // here -- see that class's own doc for the ARENA_ID env-var selection mechanism and the
            // full provenance of ARENA_00's numbers (unchanged from every prior pass's literals).
            ArenaDefinition arena = ArenaDefinition.select();
            logger.info("[Server] selected arena: " + arena.id);

            // PlayerBot's own constructor already queues itself via World.getAddPlayerQueue()
            // (confirmed: PlayerBot.java:77-79) - do not add it again here. Traced and confirmed
            // harmless if done (MobileList.add()'s isRegistered() guard no-ops the second add,
            // so there's no double-processing), but redundant and worth not doing.
            MinimalEnvironmentBot bot = new MinimalEnvironmentBot(GameConstants.PLAYER_BOTS[0], arena);
            NPC target = NPC.create(arena.npcId, arena.npcSpawn);
            // ARENA OCCUPANT GROUND TRUTH (osrsproject docs/PROJECT_STATE.md, "FLINCH -- E3: DO.2C
            // ARENA CAPABILITY"). Emits the LIVE NpcDefinition of the instance this boot actually
            // spawned, so a preregistration's assumed occupant attributes can be verified against
            // the running server rather than against npc_defs.json read by hand or against a
            // design document's own arithmetic. A silently wrong attack speed, size, hitpoint
            // total or aggression flag would invalidate a frozen timing model with no combat check
            // able to catch it; this line is the check.
            //
            // GENERAL, not DO.2C-specific: it prints whatever occupant the selected arena carries,
            // for every arena. TRACE-ONLY: no control flow, no combat semantics, no timer, no
            // mutation of the definition or the NPC. Gated by FLINCH_CERT_TRACE_ENABLED like every
            // other ground-truth line, so ordinary training runs are unaffected.
            if (Combat.FLINCH_CERT_TRACE_ENABLED) {
                NpcDefinition def = target.getDefinition();
                System.out.println("FLINCH_FIDELITY_GROUND_TRUTH ARENA_OCCUPANT arena=" + arena.id
                        + " npcId=" + arena.npcId
                        + " name=" + def.getName()
                        + " hitpoints=" + def.getHitpoints()
                        + " attackSpeed=" + def.getAttackSpeed()
                        + " size=" + def.getSize()
                        + " aggressive=" + def.isAggressive()
                        + " fightsBack=" + def.doesFightBack()
                        + " attackable=" + def.isAttackable()
                        + " maxHit=" + def.getMaxHit()
                        + " attackLevel=" + def.getStats()[0]
                        + " strengthLevel=" + def.getStats()[1]
                        + " defenceLevel=" + def.getStats()[2]
                        + " spawn=" + arena.npcSpawn
                        + " index=" + target.getIndex());
                System.out.flush();
            }
            World.getAddNPCQueue().add(target);
            bot.setTarget(target);
            // PURSUIT-RACE FIX + PATHFINDING CORRECTION pass (PROJECT_STATE.md section 13): NPC.create()
            // bypasses NpcSpawnDefinitionLoader (json-spawn-only), so NPCMovementCoordinator.radius was
            // never set and stayed at Java's default 0 -- a degenerate leash (NPCMovementCoordinator.java's
            // AWAY/RETREATING transition fires on ANY nonzero drift from spawn) that both routes the NPC
            // via the REAL PathFinder toward its spawn tile and calls npc.getCombat().reset() every tick
            // while RETREATING+inCombat, independent of and compounding the already-fixed pursuit race.
            // See ArenaDefinition's own doc for where the radius=6 value itself came from (unchanged
            // by this pass's refactor, just relocated out of this hardcoded line).
            target.getMovementCoordinator().setRadius(arena.npcCoordinatorRadius);

            // ARENA_01's L-corner (or whatever future arena's obstacle list) registered here, at
            // boot, permanently -- the same ObjectManager.register(obj, true) runtime-registration
            // path every prior pass's TEMP wall instrumentation used by hand, now config-driven.
            // RESET-CYCLE CORRECTNESS PASS: construction now goes through
            // ArenaDefinition.buildObstacleObject() (single source of truth shared with the new
            // deregisterObstacles()/registerObstacles() teardown primitives), not an inline copy --
            // this loop's own shape, order, and per-obstacle log line are otherwise unchanged.
            for (ArenaDefinition.ObstacleSpec obstacle : arena.obstacles) {
                GameObject object = ArenaDefinition.buildObstacleObject(obstacle);
                ObjectManager.register(object, true);
                logger.info("[Server] registered arena obstacle: id=" + obstacle.objectId
                        + " loc=" + obstacle.location + " dir=" + obstacle.direction);
            }

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