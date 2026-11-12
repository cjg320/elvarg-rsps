package com.elvarg.rl;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Diagnostic-only combat event log (DECISIONS M2-cond-next-intervention-consult). Never read by the game, never a policy input.
 * Enabled only when HARNESS_EVENTS=1. Records are long[] rows; draws no randomness. Kinds:
 * 0 ROLL   [0, tick, attackerIsNpc, attackerNpcId(-1 player), attackerIndex, targetIsNpc, targetIndex, combatTypeOrdinal, attRoll, defRoll, accurate, rawDamage]
 * 1 DAMAGE [1, tick, mobileIsNpc, mobileIndex, requested, applied, hpBefore, hpAfter]
 * 2 HEAL   [2, tick, nominalHeal, appliedHeal, hpBefore, hpAfter]
 * 3 HPSET  [3, tick, hpBefore, hpAfter]   (player hitpoint changes through setHitpoints)
 * 4 REGEN  [4, tick, hpBefore, hpAfter]   (natural player hitpoint regeneration, which bypasses setHitpoints)
 */
public final class HarnessEvents {
    public static final boolean ENABLED = "1".equals(System.getenv("HARNESS_EVENTS"));
    private static final List<long[]> LOG = new CopyOnWriteArrayList<>();

    private HarnessEvents() {
    }

    public static void add(long... row) {
        if (ENABLED) {
            LOG.add(row);
        }
    }

    public static String drainJson() {
        StringBuilder sb = new StringBuilder("[");
        boolean first = true;
        for (long[] r : LOG) {
            sb.append(first ? "" : ",").append("[");
            for (int i = 0; i < r.length; i++) {
                sb.append(i == 0 ? "" : ",").append(r[i]);
            }
            sb.append("]");
            first = false;
        }
        LOG.clear();
        return sb.append("]").toString();
    }
}
