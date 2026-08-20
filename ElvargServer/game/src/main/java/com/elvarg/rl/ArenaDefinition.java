package com.elvarg.rl;

import com.elvarg.game.entity.impl.object.GameObject;
import com.elvarg.game.entity.impl.object.ObjectManager;
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

    // MAP FACTORY ROUND ONE INCREMENT 1 -- Part C/D: generates a full rectangular ObstacleSpec
    // perimeter (type-0 walls, one object per edge tile) from a donor site's swept footprint
    // bounds, instead of a hand-placed L-corner. Corners naturally receive TWO objects (one per
    // adjoining edge -- same convention as ARENA_01/03's own L-corner) since each edge loop covers
    // its own full side independently; no special-cased corner logic needed. Direction values
    // follow addClippingForVariableObject()'s type-0 mapping, confirmed from source
    // (RegionManager.java): dir=0 west-blocking, dir=1 north-blocking, dir=2 east-blocking,
    // dir=3 south-blocking.
    private static List<ObstacleSpec> buildRectangularBoundary(int minX, int minY, int maxX, int maxY, int wallObjectId) {
        List<ObstacleSpec> list = new java.util.ArrayList<>();
        for (int y = minY; y <= maxY; y++) {
            list.add(new ObstacleSpec(wallObjectId, new Location(minX, y, 0), 0, 0)); // west edge
            list.add(new ObstacleSpec(wallObjectId, new Location(maxX, y, 0), 0, 2)); // east edge
        }
        for (int x = minX; x <= maxX; x++) {
            list.add(new ObstacleSpec(wallObjectId, new Location(x, maxY, 0), 0, 1)); // north edge
            list.add(new ObstacleSpec(wallObjectId, new Location(x, minY, 0), 0, 3)); // south edge
        }
        return Collections.unmodifiableList(list);
    }

    // MAP FACTORY ROUND ONE INCREMENT 1 -- Part B/C/D: the first donor-region proof map (ABSENT
    // type -- ground + boundary only, zero tactic geometry, per Part 7's own framing: "the
    // cheapest full-pipeline exercise"). Site found via a footprint-sweep+BFS chase directed at a
    // user-supplied coordinate (docs/PROJECT_STATE.md MAP FACTORY subsection, ROUND ONE INCREMENT
    // 1 ADDENDUM): 35x35 reachable square at world (3388,2953)-(3422,2987), region 13614,
    // isolation 553 tiles to the nearest NPC spawn -- clears the ~32x32 target and beats every one
    // of the four originally-ranked candidates (best was 13x13). Full rectangular boundary (140
    // wall objects, object id 979 -- the same wall type ARENA_01/03 already use) rather than a
    // single L-corner, since this map's whole purpose is sealing a donor footprint, not shaping
    // one cover interaction. Occupant: Chaos druid warrior (2890), the measured-parity default
    // (HARDER-NPC FIDELITY AUDIT pass) -- an ABSENT map teaches "just fight," Part 3.5's own logic.
    public static final ArenaDefinition ARENA_04 = new ArenaDefinition(
            "ARENA_04",
            new Location(3405, 2970),
            2890,
            new Location(3406, 2970),
            6,
            buildRectangularBoundary(3388, 2953, 3422, 2987, 979));

    // MOUNTAIN TROLL ADDITION + FLINCH ARENA + FLINCH CERTIFICATION pass -- the flinch-family
    // PRESENT map, ARENA_05. Site (II.1): reuses ARENA_04's own swept donor footprint verbatim
    // (same 35x35 site, same 140-object rectangular boundary, same spawn pair) -- flinch is
    // opponent-choice, not geometry (Part 7.2), so no new sweep is needed; the fixture-status
    // caveat (environmental suitability unassessed, docs/PROJECT_STATE.md's own A.2 record) rides
    // into this arena's own record unchanged, not silently dropped. Occupant: Mountain troll
    // (id 7749, Wiki-verified this pass -- see the doc record for verbatim quotes; NOT any of the
    // 8 pre-existing "Mountain troll" npc_defs.json entries, ids 936-942/4143, which are confirmed
    // wrong on attackSpeed=4 (Wiki says 6) and maxHit=13 (Wiki says 11) -- the standing
    // dataset-untrusted ledger finding, concretely reconfirmed). npcCoordinatorRadius=6 and the
    // troll's own combatFollowDistance=7 mirror the Chaos druid warrior/Earth warrior precedent
    // exactly (I.2's own instruction).
    public static final ArenaDefinition ARENA_05 = new ArenaDefinition(
            "ARENA_05",
            new Location(3405, 2970),
            7749,
            new Location(3406, 2970),
            6,
            buildRectangularBoundary(3388, 2953, 3422, 2987, 979));

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
            case "ARENA_04" -> ARENA_04;
            case "ARENA_05" -> ARENA_05;
            default -> throw new IllegalArgumentException(
                    "Unknown ARENA_ID: " + requested + " (expected ARENA_00, ARENA_01, ARENA_02, ARENA_03, ARENA_04, or ARENA_05)");
        };
    }

    /**
     * THREAD 2a RESPACE (docs/PROJECT_STATE.md "THREAD 2a DESIGN" record): resolves an
     * ArenaDefinition by id string for the trainer-controlled per-episode arena switch. Throws on
     * ANY unrecognized value -- mirrors {@link #select}'s own actual throw-on-unrecognized
     * behavior (verified against that method's CODE above, not its doc comment, which the design
     * record already flagged as imprecise: only null/blank falls back to ARENA_00 there; a
     * non-blank unknown value throws, same as here). Fail-loud, deliberately: a bad {@code
     * arena_id} from the trainer must surface as a loud error, never a silent ARENA_00 fallback --
     * silently training on the wrong arena is a corrupted experiment, worse than a crash. Does NOT
     * handle "absent" -- an absent {@code arena_id} on the wire means "no switch requested" and
     * this method is never called at all for that case (see MinimalEnvironmentBot's own switch
     * sequence). {@link #select} (boot-time, env-var-driven) is completely untouched by this
     * method's existence -- a separate resolver, not a refactor of the boot path.
     */
    public static ArenaDefinition byId(String id) {
        return switch (id) {
            case "ARENA_00" -> ARENA_00;
            case "ARENA_01" -> ARENA_01;
            case "ARENA_02" -> ARENA_02;
            case "ARENA_03" -> ARENA_03;
            case "ARENA_04" -> ARENA_04;
            case "ARENA_05" -> ARENA_05;
            default -> throw new IllegalArgumentException(
                    "Unknown arena_id: " + id + " (expected ARENA_00, ARENA_01, ARENA_02, ARENA_03, ARENA_04, or ARENA_05)");
        };
    }

    /**
     * RESET-CYCLE CORRECTNESS PASS -- single source of truth for turning an {@link ObstacleSpec}
     * into the {@link GameObject} identity {@code ObjectManager.register()}/{@code deregister()}
     * operate on. Extracted so the boot path (Server.java) and the new teardown/re-register
     * methods below build the IDENTICAL object -- not merely similar-looking duplicated
     * construction -- since {@code ObjectManager.deregister()}'s
     * {@code World.getObjects().removeIf(o -> o.equals(object))} depends on
     * {@link GameObject#equals} matching (value-based: location/id/face/type/privateArea,
     * confirmed from source, not assumed). A future field added to {@code ObstacleSpec} (e.g. for
     * varied map-factory geometry) only needs updating HERE to stay correct on both the
     * registration and teardown sides -- the failure mode a duplicated-construction design would
     * silently reintroduce.
     */
    public static GameObject buildObstacleObject(ObstacleSpec obstacle) {
        return new GameObject(obstacle.objectId, obstacle.location, obstacle.type, obstacle.direction, null);
    }

    /**
     * The first production teardown primitive for arena obstacles. Server.java's boot loop
     * registers obstacles once, at boot, and nothing has ever torn them down (DEREGISTRATION /
     * RESET-CYCLE AUDIT's own finding: no caller anywhere deregisters a type 0-3 object). This is
     * the primitive thread 2's reset-time arena switch will call -- NOT wired into
     * MinimalEnvironmentBot.performReset() or any live reset path by this pass, per its own scope
     * (that integration needs the v5 protocol bump to carry per-episode arena selection).
     * {@code playerUpdate=true}, matching every real obstacle registration call -- {@code
     * ObjectManager.deregister()}'s own Javadoc (this pass's Part 2 addition) documents that the
     * flag doesn't currently gate anything on this path anyway, so {@code true} costs nothing and
     * stays consistent with the registration side.
     * <p>
     * Deregistering already reaches clipping removal with no extra call needed: {@code deregister()}
     * -&gt; {@code perform(DESPAWN)} (unconditional) -&gt; {@code MapObjects.remove()} -&gt;
     * {@code RegionManager.removeObjectClipping()} (traced this pass, current source -- the earlier
     * assumption that teardown might need to call clipping removal explicitly does not hold).
     */
    public static void deregisterObstacles(ArenaDefinition arena) {
        for (ObstacleSpec obstacle : arena.obstacles) {
            ObjectManager.deregister(buildObstacleObject(obstacle), true);
        }
    }

    /**
     * Companion to {@link #deregisterObstacles} -- registers the same obstacle set, for a
     * register-&gt;teardown-&gt;re-register cycle (this pass's own certification) and for thread 2's
     * future per-episode arena switch. NOT called by Server.java's own boot loop, deliberately: that
     * loop logs a message per obstacle (`logger.info(...)`), which this method does not do, so
     * routing boot through it would silently drop that log line -- a real behavior change the
     * behavior-preservation constraint rules out. Server.java instead calls
     * {@link #buildObstacleObject} directly for construction only, keeping its own loop/logging
     * structure fully intact -- see the call site's own comment. Both this method and the boot loop
     * therefore build via the exact same {@link #buildObstacleObject}, symmetric by construction,
     * while boot's loop shape and its log line are untouched.
     */
    public static void registerObstacles(ArenaDefinition arena) {
        for (ObstacleSpec obstacle : arena.obstacles) {
            ObjectManager.register(buildObstacleObject(obstacle), true);
        }
    }
}
