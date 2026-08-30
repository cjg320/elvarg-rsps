package com.elvarg.game.model.movement;

import com.elvarg.game.model.Direction;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * E5 deterministic traveller verifier -- exercises the SHIPPED {@link PursuitStep#next} against
 * synthetic grids. No server, no world, no network: legality is a set of blocked tiles.
 *
 * <p>Deliberately drives the production function rather than a replica (see PursuitStep's javadoc
 * on replica drift). Run after {@code :game:compileJava}:
 *
 * <pre>java -cp game/build/classes/java/main com.elvarg.game.model.movement.PursuitStepVerifier</pre>
 *
 * <p>Exit 0 iff every case passes; prints one line per case and a final count.
 */
public final class PursuitStepVerifier {

    private static int passed = 0;
    private static int failed = 0;

    /** Legality over a synthetic grid: a step is legal iff the destination tile is not blocked. */
    private static PursuitStep.StepLegality grid(int selfX, int selfY, Set<String> blocked) {
        return d -> !blocked.contains((selfX + d.getX()) + "," + (selfY + d.getY()));
    }

    private static Set<String> blocked(String... tiles) {
        return new LinkedHashSet<>(List.of(tiles));
    }

    private static void check(String name, Direction actual, Direction expected) {
        boolean ok = actual == expected;
        if (ok) {
            passed++;
        } else {
            failed++;
        }
        System.out.println((ok ? "  PASS  " : "  FAIL  ") + name
                + "  expected=" + expected + " actual=" + actual);
    }

    /** self at (sx,sy), target at (tx,ty), blocked tiles as "x,y". */
    private static Direction step(int sx, int sy, int tx, int ty, Set<String> blocked) {
        return PursuitStep.next(tx - sx, ty - sy, grid(sx, sy, blocked));
    }

    public static void main(String[] args) {
        System.out.println("E5 PURSUIT-STEP VERIFIER -- deterministic, no server\n");

        System.out.println("R1 MELEE-STOP (orthogonally adjacent -> no movement)");
        check("R1 target east",  step(0, 0, 1, 0, blocked()), Direction.NONE);
        check("R1 target west",  step(0, 0, -1, 0, blocked()), Direction.NONE);
        check("R1 target north", step(0, 0, 0, 1, blocked()), Direction.NONE);
        check("R1 target south", step(0, 0, 0, -1, blocked()), Direction.NONE);

        System.out.println("\nR2 DIAGONALLY-ADJACENT (X-axis ONLY; never diagonal, never Y)");
        check("R2 NE corner -> EAST", step(0, 0, 1, 1, blocked()), Direction.EAST);
        check("R2 SE corner -> EAST", step(0, 0, 1, -1, blocked()), Direction.EAST);
        check("R2 NW corner -> WEST", step(0, 0, -1, 1, blocked()), Direction.WEST);
        check("R2 SW corner -> WEST", step(0, 0, -1, -1, blocked()), Direction.WEST);
        // The safespot rule: X blocked -> STUCK. Explicitly NOT the diagonal, NOT the Y step,
        // even though both are open. This single assertion is what makes a safespot possible.
        check("R2 X blocked -> STUCK (Y open, diagonal open)",
                step(0, 0, 1, 1, blocked("1,0")), Direction.NONE);
        check("R2 X blocked -> STUCK (mirrored, W side)",
                step(0, 0, -1, -1, blocked("-1,0")), Direction.NONE);

        System.out.println("\nR3 ORDINARY STEP (diagonal first, then X, then Y when max>1)");
        check("R3a diagonal first", step(0, 0, 3, 3, blocked()), Direction.NORTH_EAST);
        check("R3b X-fallback when diagonal blocked",
                step(0, 0, 3, 3, blocked("1,1")), Direction.EAST);
        check("R3c Y-fallback when diagonal and X blocked, max>1",
                step(0, 0, 3, 3, blocked("1,1", "1,0")), Direction.NORTH);
        check("R3d stationary when all blocked",
                step(0, 0, 3, 3, blocked("1,1", "1,0", "0,1")), Direction.NONE);
        check("R3 pure-X target at range", step(0, 0, 4, 0, blocked()), Direction.EAST);
        check("R3 pure-Y target at range", step(0, 0, 0, 4, blocked()), Direction.NORTH);
        // R3(a) must not fire on a degenerate 'diagonal' with a zero component.
        check("R3a skipped when a signum is 0 (pure-Y, X blocked is irrelevant)",
                step(0, 0, 0, 4, blocked("1,1")), Direction.NORTH);
        // R3(c) guard: dy != 0 but max(|dx|,|dy|) == 1 is R1/R2 territory, never a Y fallback.
        check("R3c guard: no Y-step when max==1 and X blocked (that is R2 STUCK)",
                step(0, 0, 1, 1, blocked("1,0")), Direction.NONE);

        System.out.println("\nR4 LEGALITY / no route-around");
        // A blocked step is never routed around: the traveller stands still, tick after tick.
        Set<String> wall = blocked("1,0", "1,1", "1,-1");
        Direction first = step(0, 0, 5, 0, wall);
        check("blocked X at range -> no detour, stationary", first, Direction.NONE);
        check("still stationary next tick (no memory, no replan)",
                step(0, 0, 5, 0, wall), Direction.NONE);

        System.out.println("\nOPEN-FIELD CONVERGENCE (reaches the plus, then stops)");
        int x = 0, y = 0, tx = 5, ty = 3, ticks = 0;
        List<String> path = new ArrayList<>();
        while (ticks++ < 20) {
            Direction d = step(x, y, tx, ty, blocked());
            if (d == Direction.NONE) {
                break;
            }
            x += d.getX();
            y += d.getY();
            path.add("(" + x + "," + y + ")");
        }
        boolean inPlus = Math.abs(tx - x) + Math.abs(ty - y) == 1;
        System.out.println("  path " + String.join(" ", path));
        check("converges to the plus and halts", inPlus ? Direction.NONE : Direction.EAST,
                Direction.NONE);

        System.out.println("\nFOUR-ORIENTATION PILLAR CONSEQUENCE (emergent; no special-casing)");
        // pillar at (0,0); player perpendicular-adjacent to it; NPC on the other axis.
        Set<String> pillar = blocked("0,0");
        // W and E: the R2 X-step runs into the pillar -> STUCK, NPC never enters the plus.
        check("W of pillar, player S  -> STUCK", step(-1, 0, 0, -1, pillar), Direction.NONE);
        check("E of pillar, player S  -> STUCK", step(1, 0, 0, -1, pillar), Direction.NONE);
        // N and S: the R2 X-step is sideways and open -> FLANK into orthogonal reach.
        Direction n = step(0, 1, 1, 0, pillar);
        check("N of pillar, player E  -> X-FLANK (EAST)", n, Direction.EAST);
        check("  ... and the flank lands in the plus",
                step(0 + n.getX(), 1 + n.getY(), 1, 0, pillar), Direction.NONE);
        Direction s = step(0, -1, 1, 0, pillar);
        check("S of pillar, player E  -> X-FLANK (EAST)", s, Direction.EAST);
        check("  ... and the flank lands in the plus",
                step(0 + s.getX(), -1 + s.getY(), 1, 0, pillar), Direction.NONE);

        System.out.println("\n==================================================");
        System.out.println("PURSUIT-STEP VERIFIER: " + passed + " passed, " + failed + " failed");
        System.out.println("==================================================");
        if (failed > 0) {
            System.exit(1);
        }
    }
}
