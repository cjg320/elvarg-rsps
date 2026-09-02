package com.elvarg.game.content.combat;

import com.elvarg.game.entity.impl.npc.NPC;
import com.elvarg.game.model.Location;
import com.elvarg.util.timers.TimerKey;

/**
 * E7 deterministic combat-termination verifier -- exercises the SHIPPED {@link Combat} tail-
 * completion semantics on real NPC instances. No server, no world, no network.
 *
 * <p>Deliberately drives the production class rather than a replica (this project has twice been
 * burned by replica drift, and once by VACUOUS PASSES from unregistered entities -- hence the
 * {@code setRegistered(true)} / {@code setHitpoints(100)} setup below, without which
 * {@code validTarget()} short-circuits and every case would pass for the wrong reason).
 *
 * <p>Run after {@code :game:compileJava}:
 *
 * <pre>java -cp game/build/classes/java/main com.elvarg.game.content.combat.CombatTerminationVerifier</pre>
 *
 * <p>Exit 0 iff every case passes; prints one line per case and a final count.
 */
public final class CombatTerminationVerifier {

    private static int passed = 0;
    private static int failed = 0;

    private static void check(String name, Object actual, Object expected) {
        boolean ok = (expected == null) ? actual == null : expected.equals(actual);
        if (ok) {
            passed++;
        } else {
            failed++;
        }
        System.out.println((ok ? "  PASS  " : "  FAIL  ") + name
                + "  expected=" + expected + " actual=" + actual);
    }

    /**
     * A registered, alive NPC at the given tile -- the non-vacuous setup.
     *
     * <p>Tiles are synthetic and carry no scenario identity, but their SEPARATION matters:
     * {@code CombatFactory.validTarget} returns false at a distance of 40 or more, and
     * {@code canReach} then resets combat for an unrelated reason -- which would make every case
     * below pass or fail for the wrong reason. Pairs here stay well inside that radius.
     */
    private static NPC npc(int x, int y) {
        NPC n = new NPC(1, new Location(x, y, 0));
        n.setRegistered(true);
        n.setHitpoints(100);
        return n;
    }

    /**
     * Advance one tick the way {@code NPC.process()} does: timers first (NPC.java:197), then the
     * combat barrier (NPC.java:210). That order is what makes a completed tail observable to
     * {@link Combat#process()} on the very tick it completes.
     */
    private static void tick(NPC n) {
        n.getTimers().process();
        n.getCombat().process();
    }

    /** Arm a tail directly, as {@code resolveBareExpiry()} does, and latch it as live. */
    private static void armTailAndLatch(NPC n, NPC target) {
        n.getCombat().setTarget(target);
        n.getTimers().register(TimerKey.FLINCH_IN_COMBAT, 8);
        // One tick with the tail live: resolveBareExpiry() takes the end-of-resolution snapshot
        // that the completion check compares against. Nothing is special-cased -- this is the
        // ordinary path a bare COMBAT_ATTACK expiry would have produced.
        tick(n);
    }

    private static void runTailToCompletion(NPC n) {
        for (int i = 0; i < 12; i++) {
            tick(n);
        }
    }

    public static void main(String[] args) {
        System.out.println("E7 COMBAT-TERMINATION VERIFIER");
        System.out.println("==================================================");

        System.out.println("\n(1) ACTIVE INTERACTION + TAIL NOT EXPIRED -> STILL ACTIVE");
        {
            NPC a = npc(100, 200);
            NPC t = npc(85, 200);
            armTailAndLatch(a, t);
            tick(a);
            tick(a);
            check("tail still running -> target retained", a.getCombat().getTarget(), t);
            check("tail still live", a.getTimers().has(TimerKey.FLINCH_IN_COMBAT), Boolean.TRUE);
        }

        System.out.println("\n(2) VALID COMPLETION (out of range throughout, no execution) -> TERMINATES");
        {
            NPC a = npc(100, 200);
            NPC t = npc(85, 200);
            armTailAndLatch(a, t);
            runTailToCompletion(a);
            check("tail completed -> offensive interaction terminated",
                    a.getCombat().getTarget(), null);
            check("combatFollowing cleared", a.getCombatFollowing(), null);
        }

        System.out.println("\n(2b) COUNTERPART -- EXECUTION MID-TAIL EARNS NO TERMINATION ON THAT SCHEDULE");
        {
            NPC a = npc(100, 200);
            NPC t = npc(85, 200);
            armTailAndLatch(a, t);
            tick(a);
            tick(a);
            // An attack execution cancels the tail (performNewAttack's isNpc cancel block). Model
            // that cancellation exactly as the engine performs it, including the latch clear.
            a.getTimers().cancel(TimerKey.FLINCH_IN_COMBAT);
            a.getCombat().markTailCancelled();
            a.getTimers().register(TimerKey.COMBAT_ATTACK, 4);
            // Tick only through the window in which the OLD tail would have expired. Running
            // longer would let the fresh COMBAT_ATTACK bare-expire and arm a SECOND tail, whose
            // own valid completion is correct behaviour and a different assertion (see (4b)).
            for (int i = 0; i < 4; i++) {
                tick(a);
            }
            check("cancelled tail earns no termination on the old schedule",
                    a.getCombat().getTarget(), t);
        }

        System.out.println("\n(3) SUBSEQUENT ORDINARY INITIATION STARTS A FRESH INTERACTION");
        {
            NPC a = npc(100, 200);
            NPC t = npc(85, 200);
            armTailAndLatch(a, t);
            runTailToCompletion(a);
            check("terminated first", a.getCombat().getTarget(), null);
            NPC fresh = npc(86, 200);
            a.getCombat().setTarget(fresh);
            check("fresh target takes effect", a.getCombat().getTarget(), fresh);
            check("no stale tail carried into the fresh interaction",
                    a.getTimers().has(TimerKey.FLINCH_IN_COMBAT), Boolean.FALSE);
        }

        System.out.println("\n(4) OUT-OF-ORDER EXECUTION BEFORE EXPIRY -> NO OLD-SCHEDULE TERMINATION");
        {
            // The regression that motivated clearing the latch at the cancel site: a Task in
            // TaskManager.process() executes an attack BEFORE npc.process() on the same tick.
            NPC a = npc(100, 200);
            NPC t = npc(85, 200);
            armTailAndLatch(a, t);
            tick(a);
            a.getTimers().cancel(TimerKey.FLINCH_IN_COMBAT);
            a.getCombat().markTailCancelled();
            a.getTimers().register(TimerKey.COMBAT_ATTACK, 4);
            tick(a);
            check("no spurious termination on the tick after an out-of-order cancel",
                    a.getCombat().getTarget(), t);
            for (int i = 0; i < 4; i++) {
                tick(a);
            }
            check("and none through the old expiry window", a.getCombat().getTarget(), t);
        }

        System.out.println("\n(4b) THE RESET SEMANTICS RESTART -- TERMINATION FOLLOWS THE NEW SCHEDULE");
        {
            // The counterpart of (4): after an execution the interaction is not terminated on the
            // old schedule, but the ordinary machinery still runs. The fresh COMBAT_ATTACK stamp
            // bare-expires with no attack (target unreachable), resolveBareExpiry() arms a NEW
            // tail, and THAT tail's own valid completion terminates. Pinned so the correction is
            // never mistaken for "an execution grants permanent immunity from termination".
            NPC a = npc(100, 200);
            NPC t = npc(85, 200);
            armTailAndLatch(a, t);
            tick(a);
            a.getTimers().cancel(TimerKey.FLINCH_IN_COMBAT);
            a.getCombat().markTailCancelled();
            a.getTimers().register(TimerKey.COMBAT_ATTACK, 4);
            for (int i = 0; i < 4; i++) {
                tick(a);
            }
            check("still engaged at the old expiry window", a.getCombat().getTarget(), t);
            for (int i = 0; i < 12; i++) {
                tick(a);
            }
            check("restarted tail completes -> terminates on the NEW schedule",
                    a.getCombat().getTarget(), null);
        }

        System.out.println("\n(5)(6) NO DOUBLE-CLEAR FAULT; TERMINATION IS IDEMPOTENT");
        {
            NPC a = npc(100, 200);
            NPC t = npc(85, 200);
            armTailAndLatch(a, t);
            runTailToCompletion(a);
            check("terminated", a.getCombat().getTarget(), null);
            for (int i = 0; i < 6; i++) {
                tick(a);
            }
            check("repeated processing does not fault or re-fire",
                    a.getCombat().getTarget(), null);
            check("tail still absent", a.getTimers().has(TimerKey.FLINCH_IN_COMBAT), Boolean.FALSE);
        }

        System.out.println("\n(7) AN UNRELATED COMBAT ACTOR IS NOT CLEARED");
        {
            NPC a = npc(100, 200);
            NPC t = npc(85, 200);
            NPC bystander = npc(10, 300);
            NPC bystanderTarget = npc(11, 300);
            bystander.getCombat().setTarget(bystanderTarget);
            armTailAndLatch(a, t);
            runTailToCompletion(a);
            check("terminating NPC lost its target", a.getCombat().getTarget(), null);
            check("bystander keeps its own target",
                    bystander.getCombat().getTarget(), bystanderTarget);
        }

        System.out.println("\n(8) AGGRESSION STATE IS NOT REWRITTEN (separate subsystem)");
        {
            // NpcAggression acquires targets independently, gated on NpcDefinition.isAggressive();
            // Combat.reset() touches neither it nor the player's aggression tolerance. Asserted
            // structurally: termination clears only the offensive interaction's own fields.
            NPC a = npc(100, 200);
            NPC t = npc(85, 200);
            armTailAndLatch(a, t);
            boolean aggressiveBefore = a.getDefinition().isAggressive();
            runTailToCompletion(a);
            check("definition aggression flag untouched by termination",
                    a.getDefinition().isAggressive(), aggressiveBefore);
            check("termination cleared only the interaction", a.getCombat().getTarget(), null);
        }

        System.out.println("\n(9)(10) EARLY RE-ENTRY REMAINS ATTACKABLE; COMPLETED TAIL LEAVES NO STALE OWNERSHIP");
        {
            NPC early = npc(100, 200);
            NPC t1 = npc(85, 200);
            armTailAndLatch(early, t1);
            tick(early);
            early.getTimers().cancel(TimerKey.FLINCH_IN_COMBAT);
            early.getCombat().markTailCancelled();
            early.getTimers().register(TimerKey.COMBAT_ATTACK, 4);
            for (int i = 0; i < 4; i++) {
                tick(early);
            }
            check("(9) early re-entry: interaction intact, NPC still entitled to attack",
                    early.getCombat().getTarget(), t1);

            NPC late = npc(100, 200);
            NPC t2 = npc(85, 200);
            armTailAndLatch(late, t2);
            runTailToCompletion(late);
            check("(10) completed tail: no stale attack ownership remains",
                    late.getCombat().getTarget(), null);
            check("(10) and no residual following", late.getCombatFollowing(), null);
        }

        System.out.println("\n==================================================");
        System.out.println("COMBAT-TERMINATION VERIFIER: " + passed + " passed, " + failed + " failed");
        System.out.println("==================================================");
        if (failed > 0) {
            System.exit(1);
        }
    }
}
