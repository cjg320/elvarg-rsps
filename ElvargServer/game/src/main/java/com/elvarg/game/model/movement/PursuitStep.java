package com.elvarg.game.model.movement;

import com.elvarg.game.model.Direction;

/**
 * OSRS "dumb traveller" combat-pursuit step selection (E5, rules R1-R4).
 *
 * <p>PURE by construction: no world reads, no side effects, no engine state. Legality is supplied
 * by the caller as a predicate so this function is the SINGLE SOURCE OF TRUTH for pursuit step
 * choice and can be exercised directly by the deterministic verifier -- this project has twice been
 * burned by hand-maintained replicas drifting from the shipped logic
 * ({@code isTargetInMeleeRange()}), so there is deliberately no second copy of these rules.
 *
 * <p>Canonical record: docs/PROJECT_STATE.md, "E5 -- NPC COMBAT-PURSUIT FIDELITY". The rules are
 * frozen contract, reproduced verbatim:
 *
 * <pre>
 * R1 MELEE-STOP           |dx| + |dy| == 1 (target orthogonally adjacent -- the standard-melee
 *                         PLUS, no diagonals) -> no movement.
 * R2 DIAGONALLY-ADJACENT  |dx| == 1 AND |dy| == 1 (target on a corner tile) -> attempt ONLY the
 *                         X-axis step (signum(dx), 0). NO diagonal, NO Y-step. If X is blocked ->
 *                         remain stationary this tick (stuck).
 * R3 ORDINARY STEP        otherwise, in order:
 *                           (a) diagonal (signum(dx), signum(dy)) if legal;
 *                           (b) X-axis (signum(dx), 0) if dx != 0 and legal;
 *                           (c) Y-axis (0, signum(dy)) if dy != 0 AND max(|dx|,|dy|) > 1 and legal;
 *                           (d) stationary.
 * R4 LEGALITY             decided by the caller's predicate from static collision only. Entity /
 *                         actor collision is NOT part of step selection.
 * </pre>
 *
 * <p><b>R3(c) is SINGLE-SOURCE / MODERATE evidence</b> (historical RuneLite
 * {@code calculateNextTravellingPoint()} only). It is retained for coherence of the traveller
 * model; it is NOT an independent certification claim and is NOT load-bearing for DO.3.
 *
 * <p>NEVER BFS/A*, route planning, detours, or any escape from a geometrically-stuck state. OSRS
 * NPCs are dumb, and safespots exist precisely because of it. The pillar STUCK/FLANK asymmetry is
 * an EMERGENT consequence of R1-R4 plus ordinary clipping -- there is no orientation, pillar,
 * safespot or flinch special-case anywhere in this class, and none may be added.
 *
 * <p>Note on R3(a): a "diagonal" with a zero component is not a diagonal. When either signum is 0
 * the (a) branch is skipped and selection falls through to (b)/(c), so a purely axis-aligned target
 * at distance > 1 is handled by the axis rules rather than by a degenerate diagonal.
 */
public final class PursuitStep {

    /** Static-collision legality for one candidate step, supplied by the caller (R4). */
    @FunctionalInterface
    public interface StepLegality {
        boolean isLegal(Direction direction);
    }

    private PursuitStep() {
    }

    /**
     * @param dx       edge-axis delta target.x - self.x
     * @param dy       edge-axis delta target.y - self.y
     * @param legality static-collision legality predicate for a candidate direction (R4)
     * @return the direction to step this tick, or {@link Direction#NONE} to stand still
     */
    public static Direction next(int dx, int dy, StepLegality legality) {
        final int adx = Math.abs(dx);
        final int ady = Math.abs(dy);

        // R1 MELEE-STOP: orthogonally adjacent (the plus) -- do not move.
        if (adx + ady == 1) {
            return Direction.NONE;
        }

        final int sx = Integer.signum(dx);
        final int sy = Integer.signum(dy);

        // R2 DIAGONALLY-ADJACENT: X-axis only. No diagonal, no Y fallback -- stuck if X is blocked.
        // This single rule is what makes an OSRS safespot possible.
        if (adx == 1 && ady == 1) {
            final Direction x = Direction.fromDeltas(sx, 0);
            return legality.isLegal(x) ? x : Direction.NONE;
        }

        // R3(a) diagonal first -- only a genuine diagonal (both components non-zero).
        if (sx != 0 && sy != 0) {
            final Direction diagonal = Direction.fromDeltas(sx, sy);
            if (legality.isLegal(diagonal)) {
                return diagonal;
            }
        }
        // R3(b) X-axis.
        if (sx != 0) {
            final Direction x = Direction.fromDeltas(sx, 0);
            if (legality.isLegal(x)) {
                return x;
            }
        }
        // R3(c) Y-axis, only when still more than one tile away on the dominant axis.
        // SINGLE-SOURCE / MODERATE -- see the class javadoc.
        if (sy != 0 && Math.max(adx, ady) > 1) {
            final Direction y = Direction.fromDeltas(0, sy);
            if (legality.isLegal(y)) {
                return y;
            }
        }
        // R3(d) stationary.
        return Direction.NONE;
    }
}
