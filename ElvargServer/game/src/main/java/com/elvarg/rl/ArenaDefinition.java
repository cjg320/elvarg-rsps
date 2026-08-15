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
 * durable across 4 reach-denial cycles under correct step-out choreography. Occupant swapped from
 * Hobgoblin (id 3049) to Chaos druid warrior (id 2890) by the HARDER-NPC FIDELITY AUDIT pass
 * (PROJECT_STATE.md section 13): the Hobgoblin's Wiki-verified stats give a stand-and-trade TTK
 * ratio around 3x in the bot's favor (fight too lopsided for cover to matter), while the Chaos
 * druid warrior's Wiki-verified stats land near parity (~1.24x) -- see that pass's own finalist
 * arithmetic for the full survey and rejected alternatives.</li>
 * <li>ARENA_02 -- the TWO-ARM TRAINING RUN pass's control arm (PROJECT_STATE.md section 13):
 * IDENTICAL spawn pair/radius to ARENA_00 (same ground, zero PLACED obstacles), but with
 * ARENA_01's audited Chaos druid warrior occupant instead of ARENA_00's Hobgoblin -- so the two
 * arms of that experiment differ in EXACTLY one variable (designed cover present or absent),
 * never touching ARENA_00 itself (the historical baseline stays byte-stable). Framed honestly as
 * a NATURAL-GEOMETRY arm, not a sterile no-cover control: the ARENA TOOLCHAIN footprint sweep
 * already proved this ground is a walled ruin with real cover within a few tiles of spawn, so
 * undesigned cover use emerging here is a headline result, not a broken control.</li>
 * <li>ARENA_03 -- the INCENTIVE-GEOMETRY ITERATION TWO pass (PROJECT_STATE.md section 13):
 * ARENA_01's exact geometry (same spawns, radius=6, the 2-wall L-corner) with the occupant
 * swapped Chaos druid warrior (2890) -&gt; Earth warrior (2840). Selected because a stand-and-trade
 * against Chaos druid warrior nets the bot positive terminal EV (~+29 at gamma=0.99, empirical
 * p_trade~0.80) that already beats a clumsy cover attempt (~+7.7) outright -- cover was never the
 * better option, so no policy needed to find it. Earth warrior's Wiki-verified stats
 * (atk42/str42/def42, hp54, maxHit5, attackSpeed4 ticks) push EV(trade) negative across the whole
 * plausible p_trade range (-15.6 at p=0.45 to +7.6 at p=0.80; only clearly positive above p~0.72),
 * making trade unattractive without invoking a timeout penalty (that lever was tried, on record as
 * ineffective at gamma=0.99 due to temporal attenuation -- see the TIMEOUT-PENALTY EXPERIMENT and
 * INCENTIVE-GEOMETRY ITERATION TWO passes). npc_defs.json id 2840 had a real data bug fixed by this
 * pass: attackSpeed was 6, Wiki says 4 -- same class of error as the historical Hobgoblin fix, now
 * corrected. combatFollowDistance is also 7 for this NPC, so npcCoordinatorRadius=6 carries over
 * unchanged, same precedent as ARENA_01/02.</li>
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
    // correct step-out choreography (4/4 cycles, zero incoming damage while hidden).
    //
    // HARDER-NPC FIDELITY AUDIT pass (PROJECT_STATE.md section 13): npcId swapped from Hobgoblin
    // (3049) to Chaos druid warrior (2890) -- melee-only, size 1, aggressive to combat level 35
    // (37*2=74 >= 35), retreats=true, and the ONLY finalist surveyed whose Wiki-verified stats
    // (atk32/str34/def25, hp40, maxHit5, attackSpeed5) land the stand-and-trade TTK ratio near
    // parity (~1.24x in the bot's favor) rather than heavily lopsided either direction. Its
    // npc_defs.json entry (id 2890, the only ID variant -- no copy-paste-across-variants risk)
    // matched the Wiki exactly field-for-field; no data fix was needed, unlike the Hobgoblin.
    // combatFollowDistance is also 7 for this NPC (same as Hobgoblin's), so npcCoordinatorRadius=6
    // carries over under the same precedent reasoning, unchanged.
    public static final ArenaDefinition ARENA_01 = new ArenaDefinition(
            "ARENA_01",
            new Location(3089, 3466),
            2890,
            new Location(3090, 3466),
            6,
            List.of(
                    new ObstacleSpec(979, new Location(3086, 3465, 0), 0, 1),  // north-blocking
                    new ObstacleSpec(979, new Location(3086, 3465, 0), 0, 2)   // east-blocking
            ));

    // TWO-ARM TRAINING RUN pass (PROJECT_STATE.md section 13): control arm. Same botSpawn/npcSpawn/
    // radius as ARENA_00 -- zero PLACED obstacles -- but ARENA_01's audited Chaos druid warrior
    // (id 2890) occupant, so the only variable differing from ARENA_01 is the designed L-corner
    // cover. ARENA_00 itself is untouched (still Hobgoblin, still the byte-stable historical
    // baseline) -- this is a THIRD definition, not a mutation of an existing one.
    public static final ArenaDefinition ARENA_02 = new ArenaDefinition(
            "ARENA_02",
            new Location(3089, 3466),
            2890,
            new Location(3090, 3466),
            6,
            Collections.emptyList());

    // INCENTIVE-GEOMETRY ITERATION TWO pass (PROJECT_STATE.md section 13): ARENA_01's exact
    // geometry (spawns, radius, the 2-wall L-corner) with npcId swapped 2890 -> 2840 (Earth
    // warrior). One variable changed from ARENA_01 -- the occupant -- everything else identical.
    public static final ArenaDefinition ARENA_03 = new ArenaDefinition(
            "ARENA_03",
            new Location(3089, 3466),
            2840,
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
            case "ARENA_02" -> ARENA_02;
            case "ARENA_03" -> ARENA_03;
            default -> throw new IllegalArgumentException(
                    "Unknown ARENA_ID: " + requested + " (expected ARENA_00, ARENA_01, ARENA_02, or ARENA_03)");
        };
    }
}
