package com.elvarg.rl;

import com.elvarg.game.model.Location;

import java.util.Collections;
import java.util.List;

/**
 * ArenaDefinition (ARENA 01 -- CHOREOGRAPHY + ARENA DEFINITION pass, PROJECT_STATE.md section 13):
 * the scoped, previously-deferred parameterization of what used to be Server.java's own hardcoded
 * bot/NPC spawn tiles and NPCMovementCoordinator radius, plus (new with ARENA_01) a wall-obstacle
 * list placed at boot via ObjectManager.register(..., true) -- the same runtime-registration path
 * every prior pass's TEMP wall instrumentation used, now a permanent, config-driven part of server
 * bootstrap rather than code that has to be added and stripped by hand every time.
 * <p>
 * Two definitions exist so far:
 * <ul>
 * <li>ARENA_00 -- the pre-this-pass baseline: same spawn pair, same radius=6, zero obstacles.
 * Booting with ARENA_00 selected must be byte-identical to every prior pass's hardcoded behavior --
 * this is the regression anchor, not a new arena design.</li>
 * <li>ARENA_01 -- the ARENA VALIDATOR / ARENA 01 CHOREOGRAPHY passes' winning geometry: the 2-wall
 * L-corner (object id 979 at (3086,3465), dir=1 north-blocking + dir=2 east-blocking) confirmed
 * durable across 4 reach-denial cycles under correct step-out choreography (this pass's own live
 * tick data).</li>
 * </ul>
 * <p>
 * Selection: the {@code ARENA_ID} environment variable, read once in {@code Server.main()} -- the
 * same convention {@code GameEngine.java}'s {@code TICK_RATE} already uses
 * ({@code agent.elvarg_launcher.ElvargServerProcess} sets both the same way). Unset or
 * unrecognized -> ARENA_00, so booting this server any way OTHER than through this env var (a bare
 * {@code .\gradlew run}, an IDE run config) reproduces today's exact behavior with zero
 * configuration required.
 * <p>
 * {@code combatFollowDistance} (7) is deliberately NOT a field here -- it's NPC-DEFINITION data
 * ({@code npc_defs.json}'s own Hobgoblin/id=3049 entry), not per-arena spawn config, and this class
 * does not duplicate it. The one leash number that IS arena-level config, the per-NPC coordinator
 * radius, is {@link #npcCoordinatorRadius} below (formerly Server.java's own hardcoded
 * {@code target.getMovementCoordinator().setRadius(6)} line).
 */
public final class ArenaDefinition {

    public static final class ObstacleSpec {
        public final int objectId;
        public final Location location;
        public final int type;
        public final int direction;

        public ObstacleSpec(int objectId, Location location, int type, int direction) {
            this.objectId = objectId;
            this.location = location;
            this.type = type;
            this.direction = direction;
        }
    }

    public final String id;
    public final Location botSpawn;
    public final int npcId;
    public final Location npcSpawn;
    public final int npcCoordinatorRadius;
    public final List<ObstacleSpec> obstacles;

    public ArenaDefinition(String id, Location botSpawn, int npcId, Location npcSpawn,
                            int npcCoordinatorRadius, List<ObstacleSpec> obstacles) {
        this.id = id;
        this.botSpawn = botSpawn;
        this.npcId = npcId;
        this.npcSpawn = npcSpawn;
        this.npcCoordinatorRadius = npcCoordinatorRadius;
        this.obstacles = Collections.unmodifiableList(obstacles);
    }

    // Bot spawn (3089,3466) / NPC id 3049 at (3090,3466) / radius 6: unchanged from every prior
    // pass's own hardcoded literals -- see MinimalEnvironmentBot's former withMeleeLoadout() and
    // Server.java's own removed comments for the full provenance of each number. Zero obstacles --
    // today's open arena, exactly as it's always been.
    public static final ArenaDefinition ARENA_00 = new ArenaDefinition(
            "ARENA_00",
            new Location(3089, 3466),
            3049,
            new Location(3090, 3466),
            6,
            Collections.emptyList());

    // ARENA VALIDATOR / ARENA 01 CHOREOGRAPHY passes (PROJECT_STATE.md section 13): same spawn
    // pair/radius as ARENA_00, plus the 2-wall L-corner at (3086,3465) confirmed durable under
    // correct step-out choreography (4/4 cycles, zero incoming damage while hidden, this pass's
    // own live data).
    public static final ArenaDefinition ARENA_01 = new ArenaDefinition(
            "ARENA_01",
            new Location(3089, 3466),
            3049,
            new Location(3090, 3466),
            6,
            List.of(
                    new ObstacleSpec(979, new Location(3086, 3465, 0), 0, 1),  // north-blocking
                    new ObstacleSpec(979, new Location(3086, 3465, 0), 0, 2)   // east-blocking
            ));

    /** Reads {@code ARENA_ID} from the environment; unset/unrecognized -> ARENA_00 (today's behavior). */
    public static ArenaDefinition select() {
        String requested = System.getenv("ARENA_ID");
        if (requested == null || requested.isBlank()) {
            return ARENA_00;
        }
        return switch (requested) {
            case "ARENA_00" -> ARENA_00;
            case "ARENA_01" -> ARENA_01;
            default -> throw new IllegalArgumentException(
                    "Unknown ARENA_ID: " + requested + " (expected ARENA_00 or ARENA_01)");
        };
    }
}
