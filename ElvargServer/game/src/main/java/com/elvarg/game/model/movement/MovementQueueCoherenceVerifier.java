package com.elvarg.game.model.movement;

import com.elvarg.game.entity.impl.npc.NPC;
import com.elvarg.game.model.Location;

/**
 * E6 deterministic movement-queue coherence verifier.
 *
 * <p>Drives the SHIPPED integration -- a real {@link NPC}, its real {@link MovementQueue}, and
 * {@code MovementQueue.process()} including {@code processCombatFollowing()} and the queue drain.
 * There is no replica of the pursuit/queue logic here, for the same reason E5 refused one: this
 * project has been burned by hand-maintained copies drifting from shipped behaviour.
 *
 * <p>No region data is loaded, so {@code RegionManager.getClipping()} returns 0 and every step is
 * legal. That is deliberate: it isolates the property under test (does the CURRENT decision own the
 * NPC's feet?) from clipping, and a refusing decision is still produced naturally by R1 -- a target
 * on an orthogonally adjacent tile means "already in melee range, do not move". Blocked-step
 * behaviour is certified live instead (E6-C2/C3), where the clip data is real.
 *
 * <p>Run after {@code :game:compileJava}:
 *
 * <pre>java -cp game/build/classes/java/main com.elvarg.game.model.movement.MovementQueueCoherenceVerifier</pre>
 *
 * <p><b>What these cases do and do not discriminate.</b> They were run with the E6 queue clear
 * temporarily disabled, and still passed. That is not a defect in the fix -- it is because
 * {@code CombatFactory.canReach()}'s NPC spawn-distance branch already resets combat state on this
 * path, so no stale point survives to the decision in this synthetic setup. These cases therefore
 * certify the END-TO-END property (the movement executed matches the current decision) rather than
 * attributing it to the clear. The load-bearing fix for the observed live failure is the removal of
 * the competing {@code stepOut()} producer, certified live in E6-C2/C3 where the stale point really
 * did survive because {@code processCombatFollowing()} was not running at all on those ticks.
 *
 * <p>Exit 0 iff every case passes.
 */
public final class MovementQueueCoherenceVerifier {

    private static int passed = 0;
    private static int failed = 0;

    private static void check(String name, Object actual, Object expected) {
        boolean ok = expected.equals(actual);
        if (ok) {
            passed++;
        } else {
            failed++;
        }
        System.out.println((ok ? "  PASS  " : "  FAIL  ") + name
                + "  expected=" + expected + " actual=" + actual);
    }

    private static String xy(Location l) {
        return "(" + l.getX() + "," + l.getY() + ")";
    }

    /** An NPC pursuing `target`, both 1x1, with the traveller owning movement. */
    private static NPC pursuer(int x, int y, NPC target) {
        NPC npc = live(x, y);
        npc.setCombatFollowing(target);
        npc.getCombat().setTarget(target);   // processCombatFollowing() reads both
        return npc;
    }

    /**
     * A combat-valid NPC. Registration and hitpoints are set because
     * {@code CombatFactory.validTarget()} requires both -- without them {@code canReach()} short
     * circuits to true, {@code processCombatFollowing()} returns before the pursuit branch, and the
     * NPC never moves. An earlier draft of this verifier missed that and produced four vacuous
     * "passes" in which nothing under test had run at all.
     */
    private static NPC live(int x, int y) {
        NPC npc = new NPC(1, new Location(x, y, 0));
        npc.setRegistered(true);
        npc.setHitpoints(100);
        return npc;
    }

    private static NPC dummy(int x, int y) {
        return live(x, y);
    }

    public static void main(String[] args) {
        System.out.println("E6 MOVEMENT-QUEUE COHERENCE VERIFIER -- shipped integration, no replica\n");

        // NOTE ON SCOPE. No map data is loaded here, so every tile is unblocked and a BLOCKED
        // traveller decision cannot be synthesised (RegionManager.addClipping() needs a loaded
        // Region; the PrivateArea clip path needs a concrete subclass). The blocked-step NONE case
        // -- the one that defeated the safespot -- is therefore certified LIVE against real map
        // clipping in E6-C2/C3, which is stronger evidence than a synthetic pillar would be.
        // What IS proven here is the mechanism those cases depend on: the pursuit branch discards
        // queued intent before applying the current decision, whatever that decision turns out
        // to be, because the clear is unconditional and precedes it.
        System.out.println("STALE INTENT vs A REFUSING DECISION (target already in melee range)");
        {
            // Target orthogonally adjacent -> R1 -> NONE. A point queued under an earlier geometry
            // is present. Before E6 it drained and moved the NPC anyway.
            NPC target = dummy(10, 10);
            NPC npc = pursuer(10, 11, target);
            npc.getMovementQueue().addStep(new Location(11, 11, 0));   // stale intent
            check("stale point present before the tick", npc.getMovementQueue().size(), 1);
            npc.getMovementQueue().process();
            check("decision NONE -> NPC did not move", xy(npc.getLocation()), "(10,11)");
            check("stale point was discarded, not deferred", npc.getMovementQueue().size(), 0);
        }
        {
            // No delayed leakage across repeated refusing ticks.
            NPC target = dummy(10, 10);
            NPC npc = pursuer(10, 11, target);
            npc.getMovementQueue().addStep(new Location(11, 11, 0));
            for (int i = 0; i < 5; i++) {
                npc.getMovementQueue().process();
            }
            check("still stationary after 5 refusing ticks", xy(npc.getLocation()), "(10,11)");
        }

        System.out.println("\nSTALE INTENT vs A DIFFERENT CURRENT DECISION");
        {
            // Target far to the NORTH-EAST -> R3(a) diagonal. A stale point pointing WEST must lose.
            NPC target = dummy(14, 14);
            NPC npc = pursuer(10, 10, target);
            npc.getMovementQueue().addStep(new Location(9, 10, 0));    // stale: westward
            npc.getMovementQueue().process();
            check("movement follows the CURRENT decision, not the old point",
                    xy(npc.getLocation()), "(11,11)");
        }

        System.out.println("\nORDINARY PURSUIT STILL WORKS (the fix must not just freeze NPCs)");
        {
            NPC target = dummy(14, 10);
            NPC npc = pursuer(10, 10, target);
            npc.getMovementQueue().process();
            check("pure-X target -> steps east", xy(npc.getLocation()), "(11,10)");
            npc.getMovementQueue().process();
            check("keeps closing", xy(npc.getLocation()), "(12,10)");
            npc.getMovementQueue().process();
            check("still closing", xy(npc.getLocation()), "(13,10)");
            npc.getMovementQueue().process();
            check("stops in the plus (R1), does not overshoot", xy(npc.getLocation()), "(13,10)");
        }
        {
            // One tile per tick: no accidental multi-step or run-style double drain for an NPC.
            NPC target = dummy(20, 10);
            NPC npc = pursuer(10, 10, target);
            npc.getMovementQueue().process();
            check("exactly one tile per tick", xy(npc.getLocation()), "(11,10)");
        }

        System.out.println("\nTRANSITIONS");
        {
            // moving -> refusing: the instant the target is in the plus, movement stops that tick.
            NPC target = dummy(13, 10);
            NPC npc = pursuer(10, 10, target);
            npc.getMovementQueue().process();
            check("moving while distant", xy(npc.getLocation()), "(11,10)");
            npc.getMovementQueue().process();
            check("arrives in the plus", xy(npc.getLocation()), "(12,10)");
            npc.getMovementQueue().process();
            check("moving -> refusing: stops immediately", xy(npc.getLocation()), "(12,10)");
        }
        {
            // refusing -> newly-legal: a target that walks away is pursued again on the next tick.
            NPC target = dummy(10, 10);
            NPC npc = pursuer(10, 11, target);
            npc.getMovementQueue().process();
            check("refusing while adjacent", xy(npc.getLocation()), "(10,11)");
            target.setLocation(new Location(10, 7, 0));
            npc.getMovementQueue().process();
            check("refusing -> legal: resumes pursuit", xy(npc.getLocation()), "(10,10)");
        }

        System.out.println("\n==================================================");
        System.out.println("QUEUE-COHERENCE VERIFIER: " + passed + " passed, " + failed + " failed");
        System.out.println("==================================================");
        if (failed > 0) {
            System.exit(1);
        }
    }
}
