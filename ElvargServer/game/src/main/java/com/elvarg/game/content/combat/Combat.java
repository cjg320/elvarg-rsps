package com.elvarg.game.content.combat;

import java.util.*;
import java.util.Map.Entry;

import com.elvarg.Server;
import com.elvarg.game.content.combat.hit.HitDamageCache;
import com.elvarg.game.content.combat.hit.HitQueue;
import com.elvarg.game.content.combat.hit.PendingHit;
import com.elvarg.game.content.combat.magic.CombatSpell;
import com.elvarg.game.content.combat.method.CombatMethod;
import com.elvarg.game.content.combat.method.impl.specials.GraniteMaulCombatMethod;
import com.elvarg.game.content.combat.ranged.RangedData.Ammunition;
import com.elvarg.game.content.combat.ranged.RangedData.RangedWeapon;
import com.elvarg.game.content.minigames.impl.CastleWars;
import com.elvarg.game.entity.impl.Mobile;
import com.elvarg.game.entity.impl.player.Player;
import com.elvarg.game.model.SecondsTimer;
import com.elvarg.game.model.dialogues.entries.impl.StatementDialogue;
import com.elvarg.util.Stopwatch;
import com.elvarg.util.timers.TimerKey;

public class Combat {
    // MECHANICS AUDIT / FIDELITY FIX PASS (PROJECT_STATE.md section 13): the unreciprocated-combat
    // attack-skip was wall-clock (Stopwatch.elapsed(6000)), effectively disabled under TICK_RATE=1
    // training (~6000 ticks to fire) while firing every ~10 ticks at stock speed -- a training-vs-
    // watched divergence the cover-cycling arena thread would actually exercise. Converted to a tick
    // count, independent of lastAttack (Stopwatch): lastAttack is ALSO read by NPC.java's 20-second
    // health-regen gate, an unrelated subsystem with its own audit history -- this field exists so
    // that conversion never touches lastAttack at all, not even its type.
    private static final int UNRECIPROCATED_ATTACK_SKIP_TICKS = 10;

    // FLINCH FIDELITY COMPLETION pass (docs/PROJECT_STATE.md): the Wiki's 8-tick "in-combat" timer
    // (Flinching page, Part B.1) -- a SEPARATE constant from UNRECIPROCATED_ATTACK_SKIP_TICKS just
    // above (the give-up-skip threshold, K=10) -- adjacent in concept (both govern "how long before
    // the engine considers something lapsed"), deliberately NOT reused or derived from each other.
    // K=10 is unchanged by this pass; this is a fourth, independent constant coupled to nothing.
    private static final int FLINCH_IN_COMBAT_TICKS = 8;

    private final Mobile character;
    private final HitQueue hitQueue;
    private final Map<Player, HitDamageCache> damageMap = new HashMap<>();
    private final Stopwatch lastAttack = new Stopwatch();
    private int ticksSinceLastAttack = 0;

    private final SecondsTimer poisonImmunityTimer = new SecondsTimer();
    private final SecondsTimer fireImmunityTimer = new SecondsTimer();
    private final SecondsTimer teleblockTimer = new SecondsTimer();
    private final SecondsTimer prayerBlockTimer = new SecondsTimer();
    public RangedWeapon rangedWeapon;
    public Ammunition rangeAmmoData;
    private Mobile target;
    private Mobile attacker;
    private CombatMethod method;
    private CombatSpell castSpell;
    private CombatSpell autoCastSpell;
    private CombatSpell previousCast;

    // FLINCH-RECOGNITION-CUE WIRE (docs/PROJECT_STATE.md, MAP FACTORY ERA, B.1/B.5-gated): the raw
    // deployment-observable "did this character's attack execute this tick" event -- a real player
    // sees the swing animation/hitsplat (0-damage splashes included), never the internal cooldown
    // this flag is derived from resetting/registering. True only for the exact tick the CAN_ATTACK
    // branch below fires (accuracy/damage resolve later, asynchronously, via the hit queue -- this
    // flag fires on the ATTEMPT, matching "the swing triggers, not the damage").
    private boolean attackExecutedThisTick;

    // The out-of-order-path fix (B.5 gate amendment 1, bounded read confirmed real): NpcAggression's
    // sole `attack()` call site (NpcAggression.java, guarded by an `inCombat(npc)` check so it fires
    // EXACTLY ONCE, on initial engagement, never mid-fight) runs from Player.process() AFTER that
    // same tick's PlayerPacketsProcessedEvent dispatch -- i.e. after MinimalEnvironmentBot's payload
    // has already built for this tick (Player.java:397 dispatches before :407's NpcAggression.process()
    // call). Setting attackExecutedThisTick directly from that path would be invisible this tick and
    // then silently wiped by next tick's own reset -- every episode's first engage-swing lost, not a
    // rare edge (arenas re-engage per episode). Fixed narrowly: the out-of-order path sets THIS
    // pending flag instead; process()'s own per-tick entry point (which always runs before that
    // tick's own payload build, per the NPC-before-player World.process() ordering) consumes it into
    // attackExecutedThisTick one tick "late" rather than losing it. The normal in-order path
    // (process() -> performNewAttack()) is completely unaffected -- sets attackExecutedThisTick
    // directly, same tick, as designed.
    private boolean pendingLateEngageSwing;
    private boolean inOutOfOrderEngageCall;

    // GATE SIGN-OFF (P.1/P.1a/L.1-L.3, S.1 amendment, docs/PROJECT_STATE.md): the R.1-generalized
    // arming site needs to detect a bare COMBAT_ATTACK expiry (no attack execution that tick)
    // BEFORE hitQueue.process() resolves any of this tick's incoming hits -- source-pinned (L.2):
    // NPC.java/Player.java call getTimers().process() before getCombat().process(), and
    // hitQueue.process() is Combat.process()'s own first statement, ahead of the arm-or-attack
    // decision -- so without a marker, a hit resolving on the exact expiry tick would read a
    // neither-timer transient that will not survive the tick (P.3(ii)'s rejected third branch).
    // pendingBareExpiry is true only for the span of the tick a bare expiry was detected, until
    // resolveBareExpiry() (called on every process() exit path) consumes it.
    // combatAttackActiveLastCheck remembers COMBAT_ATTACK.has() as of the end of the PREVIOUS
    // tick's resolution -- the only way to detect "just expired this tick" given
    // TimerRepository has no expiry callback (confirmed from source: process() only calls tick()).
    private boolean pendingBareExpiry;
    private boolean combatAttackActiveLastCheck;

    /** Trainer-privileged, deployment-honest read of {@link #attackExecutedThisTick} -- see that
     * field's own doc. Consumed by MinimalEnvironmentBot's payload builder only. */
    public boolean attackExecutedThisTick() {
        return attackExecutedThisTick;
    }

    /** GATE SIGN-OFF -- see {@link #pendingBareExpiry}'s own doc. Read cross-class by
     * CombatFactory's Hunk 3 gate, since that's where the neither-timer transient would otherwise
     * be observable. */
    public boolean isPendingBareExpiry() {
        return pendingBareExpiry;
    }

    public Combat(Mobile character) {
        this.character = character;
        this.hitQueue = new HitQueue();
    }

    /**
     * Attacks an entity by updating our current target.
     *
     * @param target The target to attack.
     */
    public void attack(Mobile target) {
        // Update the target
        setTarget(target);

        if (character != null && character.isNpc() && !character.getAsNpc().getDefinition().doesFightBack()) {
            // Don't follow or face enemy if NPC doesn't fight back
            return;
        }

        // Start facing the target
        character.setMobileInteraction(target);

        // FLINCH-RECOGNITION-CUE WIRE: this is the out-of-order path (see attackExecutedThisTick's
        // own doc) -- mark it so the CAN_ATTACK branch below routes to the pending flag instead of
        // the main one.
        inOutOfOrderEngageCall = true;
        // Perform the first attack now (in same tick)
        performNewAttack(false);
        inOutOfOrderEngageCall = false;
    }

    /**
     * Processes combat.
     */
    public void process() {
        // FLINCH-RECOGNITION-CUE WIRE: reset-then-consume, unconditionally, before either of this
        // method's own return paths below -- this IS the per-tick entry point (called exactly once
        // per tick per entity, confirmed by the existing comment just below), and it runs during
        // NPC-before-player World.process() ordering, i.e. always before this tick's own payload
        // build. Consumes any pending swing from the out-of-order attack() path (one tick late,
        // not lost); a genuine same-tick swing from this method's own performNewAttack() call below
        // (if any) overwrites this with true further down, same tick, same as before this change.
        attackExecutedThisTick = pendingLateEngageSwing;
        pendingLateEngageSwing = false;

        // GATE SIGN-OFF (P.1a/L.2/L.3, docs/PROJECT_STATE.md): latch a bare-expiry detection BEFORE
        // hitQueue.process() -- see pendingBareExpiry's own doc for why this ordering is
        // load-bearing (source-pinned, not stylistic).
        boolean combatAttackRunningNow = character.getTimers().has(TimerKey.COMBAT_ATTACK);
        pendingBareExpiry = combatAttackActiveLastCheck && !combatAttackRunningNow;

        // Process the hit queue
        hitQueue.process(character);

        // Tick-counted (not wall-clock -- see UNRECIPROCATED_ATTACK_SKIP_TICKS above). process() is
        // called exactly once per tick per entity (NPC.java/Player.java each call getCombat().process()
        // once from their own once-per-tick process() barrier, alongside getTimers().process() and
        // getMovementQueue().process()), so this increment is a genuine per-tick counter, not an
        // approximation. Skip this tick's attack attempt if we haven't been attacked in
        // UNRECIPROCATED_ATTACK_SKIP_TICKS ticks; setUnderAttack(null) resets the counter, so this
        // fires once, then restarts counting toward the next skip -- a periodic one-tick attack-skip,
        // not a disengage (target/combatFollowing are untouched by this path).
        ticksSinceLastAttack++;
        if (ticksSinceLastAttack >= UNRECIPROCATED_ATTACK_SKIP_TICKS) {
            setUnderAttack(null);
            // GATE SIGN-OFF (L.1): the give-up-skip path never calls performNewAttack() this tick,
            // so a bare expiry here must still resolve -- L.1's own reasoning: arming keeps the NPC
            // honestly in-combat, closing a phantom window rather than opening one.
            resolveBareExpiry();
            printRDTraceIfNpc();
            return;
        }

        // Handle attacking
        performNewAttack(false);
        resolveBareExpiry();
        printRDTraceIfNpc();
    }

    // TEMP INSTRUMENTATION (R-D COORDINATOR-RESET / TAIL SURVIVAL CHECK, docs/PROJECT_STATE.md) --
    // per-tick trace, NPC-scoped: both timers, the latch pair, the movement coordinator's own
    // state, spawn-distance (max of |deltaX|,|deltaY|, the same metric NPCMovementCoordinator
    // itself uses), and ticksSinceLastAttack. Strip this whole method + its two call sites after
    // R-D's verdict is recorded; NOT via `git checkout --` (real hunks in this file too).
    private void printRDTraceIfNpc() {
        if (!character.isNpc()) {
            return;
        }
        com.elvarg.game.entity.impl.npc.NPC npc = character.getAsNpc();
        // GATE FIX (R-D rerun 1 -- the first attempt printed unconditionally for EVERY world NPC
        // every tick, 114884 lines in a ~45-tick window; zero real HIT/ARMED/NOOP events landed in
        // that run, almost certainly the print volume stalling the TICK_RATE=1 server enough to
        // break the already-documented zero-slack duck timing -- a self-inflicted measurement
        // artifact, not a finding). Scoped to only the NPC actually being traced: engaged in
        // combat (target set) or carrying non-default flinch/coordinator state, matching this
        // project's standing "log only the interesting case" convention.
        boolean interesting = target != null
                || character.getTimers().has(TimerKey.COMBAT_ATTACK)
                || character.getTimers().has(TimerKey.FLINCH_IN_COMBAT)
                || npc.getMovementCoordinator().getCoordinateState() != com.elvarg.game.entity.impl.npc.NPCMovementCoordinator.CoordinateState.HOME;
        if (!interesting) {
            return;
        }
        int dx = Math.abs(npc.getLocation().getX() - npc.getSpawnPosition().getX());
        int dy = Math.abs(npc.getLocation().getY() - npc.getSpawnPosition().getY());
        System.out.println("FLINCH_FIDELITY_GROUND_TRUTH RD_TRACE char=" + character.getIndex()
                + " combatAttackTicks=" + character.getTimers().getTicks(TimerKey.COMBAT_ATTACK)
                + " tailTicks=" + character.getTimers().getTicks(TimerKey.FLINCH_IN_COMBAT)
                + " pendingBareExpiry=" + pendingBareExpiry
                + " combatAttackActiveLastCheck=" + combatAttackActiveLastCheck
                + " coordState=" + npc.getMovementCoordinator().getCoordinateState()
                + " spawnDx=" + dx + " spawnDy=" + dy
                + " coordRadius=" + npc.getMovementCoordinator().getRadius()
                + " ticksSinceLastAttack=" + ticksSinceLastAttack
                + " inCombatFactory=" + CombatFactory.inCombat(npc));
        System.out.flush();
    }

    /**
     * GATE SIGN-OFF (P.1, L.1, S.1 amendment, docs/PROJECT_STATE.md): the single generalized
     * arming site -- ANY bare COMBAT_ATTACK expiry (this tick, no attack execution) arms the tail,
     * no reachability check (L.1 deletes it; within this project's modeled scope -- melee-only
     * basic auto-attackers, 1v1, no stuns/specials/phase behavior -- in-range+ready implies the
     * attack executes, so "out of range" and "no attack executed" are extensionally identical; see
     * L.1's own scope-trigger annotation for when that stops holding). NPC-scoped per S.1: player
     * flinchability stays out of scope, an unread player-side tail would just be dead state
     * polluting reset-residue diagnostics. Called on EVERY process() exit path.
     */
    private void resolveBareExpiry() {
        if (character.isNpc() && pendingBareExpiry && !attackExecutedThisTick) {
            character.getTimers().register(TimerKey.FLINCH_IN_COMBAT, FLINCH_IN_COMBAT_TICKS);
            // TEMP GROUND-TRUTH INSTRUMENTATION (GATE SIGN-OFF pass) -- strip only this
            // println+flush, NOT via `git checkout --` (real hunks too). Recompile + `git diff`
            // after.
            System.out.println("FLINCH_FIDELITY_GROUND_TRUTH TAIL_ARMED_ON_BARE_EXPIRY char=" + character.getIndex()
                    + " ticksAfterArm=" + character.getTimers().getTicks(TimerKey.FLINCH_IN_COMBAT));
            System.out.flush();
        }
        pendingBareExpiry = false;
        combatAttackActiveLastCheck = character.getTimers().has(TimerKey.COMBAT_ATTACK);
    }

    /**
     * Attempts to perform a new attack.
     */
    public void performNewAttack(boolean instant) {
        if (target == null || (character != null && character.isNpc() && !character.getAsNpc().getDefinition().doesFightBack())) {
            // Don't process attacks for NPC's who don't fight back
            return;
        }

        // Fetch the combat method the character will be attacking with
        method = CombatFactory.getMethod(character);

        character.setCombatFollowing(target);

        // Face target
        character.setMobileInteraction(target);

        if (!CombatFactory.canReach(character, method, target)) {
            // Make sure the character is within reach before processing combat
            return;
        }

        // Granite maul special attack, make sure we disregard delay
        // and that we do not reset the attack timer.
        boolean graniteMaulSpecial = (method instanceof GraniteMaulCombatMethod);
        if (graniteMaulSpecial) {
            instant = true;
        }

        if (!instant && character.getTimers().has(TimerKey.COMBAT_ATTACK)) {
            // If attack isn't instant, make sure timer is elapsed.
            Server.logDebug("Combat : Waiting on COMBAT_ATTACK timer");
            return;
        }

        // Check if the character can perform the attack
        switch (CombatFactory.canAttack(character, method, target)) {
            case CAN_ATTACK -> {
                if (character.getCombat().getAttacker() == null) {
                    // Call the onCombatBegan hook once when combat begins
                    method.onCombatBegan(this.character, attacker);
                }
                if (target.getCombat().getAttacker() == null) {
                    // Call the onCombatBegan hook once when combat begins
                    CombatMethod targetMethod = CombatFactory.getMethod(target);
                    targetMethod.onCombatBegan(target, this.character);
                }

                method.start(character, target);
                PendingHit[] hits = method.hits(character, target);
                if (hits == null)
                    return;
                // FLINCH-RECOGNITION-CUE WIRE: the attack attempt just executed (accuracy/damage
                // resolve later, async, via the hit queue below -- this is the "swing triggers, not
                // the damage" observable). Route to the pending flag if this call originated from
                // the out-of-order attack() path (see that field's own doc), the main flag otherwise.
                if (inOutOfOrderEngageCall) {
                    pendingLateEngageSwing = true;
                } else {
                    attackExecutedThisTick = true;
                }
                // TEMP INSTRUMENTATION (U.4 discriminating probes, docs/PROJECT_STATE.md) -- direct,
                // unconditional NPC-scoped "attack executed" event, needed by both the bot-first
                // and NPC-first race probes to observe attack-execution timing precisely rather than
                // inferring it from the wire's own enemy_swing_observed (a different observation
                // frame, +1 drain-tick per the standing project convention). Strip after U.4.
                if (character.isNpc()) {
                    System.out.println("FLINCH_FIDELITY_GROUND_TRUTH NPC_ATTACK_EXECUTED char=" + character.getIndex()
                            + " combatAttackHasBeforeReset=" + character.getTimers().has(TimerKey.COMBAT_ATTACK));
                    System.out.flush();
                }
                for (PendingHit hit : hits) {
                    CombatFactory.addPendingHit(hit);
                }
                method.finished(character, target);

                // Reset attack timer
                if (!graniteMaulSpecial) {
                    int speed = method.attackSpeed(character);
                    character.getTimers().register(TimerKey.COMBAT_ATTACK, speed);
                }

                // GATE SIGN-OFF (P.1 CANCEL-AT-ATTACK, docs/PROJECT_STATE.md): an NPC attack
                // execution while a tail is live CANCELS it -- does not re-arm to 8 (that composition
                // was rejected: tail(8) overlapping the fresh COMBAT_ATTACK stamp above would make
                // flinchability return too early). The fresh COMBAT_ATTACK stamp a few lines above
                // (existing, untouched machinery) already keeps the NPC IN COMBAT through the
                // ordinary cooldown; the tail re-arms later, ONLY via resolveBareExpiry(), at that
                // cooldown's own next bare expiry -- producing attack_speed+8 sequentially, never an
                // overlapping immediate-8 countdown. cancel() on an absent key is a safe no-op
                // (TimerRepository.java), so this is correct whether or not a tail was actually live.
                if (character.isNpc()) {
                    // TEMP GROUND-TRUTH INSTRUMENTATION (GATE SIGN-OFF pass) -- logs ONLY the
                    // interesting case (a tail was actually live to cancel), matching this project's
                    // existing convention (RESET_WHILE_LIVE/RESET_POST_CLEAR). Strip surgically,
                    // NOT via `git checkout --`, recompile, `git diff` after.
                    boolean gtTailWasLive = character.getTimers().has(TimerKey.FLINCH_IN_COMBAT);
                    character.getTimers().cancel(TimerKey.FLINCH_IN_COMBAT);
                    if (gtTailWasLive) {
                        System.out.println("FLINCH_FIDELITY_GROUND_TRUTH TAIL_CANCELED_ON_ATTACK char=" + character.getIndex()
                                + " combatAttackHas=" + character.getTimers().has(TimerKey.COMBAT_ATTACK));
                        System.out.flush();
                    }
                }

                instant = false;
                if (character.isSpecialActivated()) {
                    character.setSpecialActivated(false);
                    if (character.isPlayer()) {
                        Player p = character.getAsPlayer();
                        CombatSpecial.updateBar(p);
                    }
                }
            }
            case ALREADY_UNDER_ATTACK -> {
                if (character.isPlayer()) {
                    character.getAsPlayer().getPacketSender().sendMessage("You are already under attack!");
                }
                character.getCombat().reset();
            }
            case CANT_ATTACK_IN_AREA -> {
                character.getCombat().reset();
            }
            case COMBAT_METHOD_NOT_ALLOWED -> {
            }
            case LEVEL_DIFFERENCE_TOO_GREAT -> {
                character.getAsPlayer().getPacketSender().sendMessage("Your level difference is too great.");
                character.getAsPlayer().getPacketSender().sendMessage("You need to move deeper into the Wilderness.");
                character.getCombat().reset();
            }
            case NOT_ENOUGH_SPECIAL_ENERGY -> {
                Player p = character.getAsPlayer();
                p.getPacketSender().sendMessage("You do not have enough special attack energy left!");
                p.setSpecialActivated(false);
                CombatSpecial.updateBar(character.getAsPlayer());
                p.getCombat().reset();
            }
            case STUNNED -> {
                Player p = character.getAsPlayer();
                p.getPacketSender().sendMessage("You're currently stunned and cannot attack.");
                p.getCombat().reset();
            }
            case DUEL_NOT_STARTED_YET -> {
                Player p = character.getAsPlayer();
                p.getPacketSender().sendMessage("The duel has not started yet!");
                p.getCombat().reset();
            }
            case DUEL_WRONG_OPPONENT -> {
                Player p = character.getAsPlayer();
                p.getPacketSender().sendMessage("This is not your opponent!");
                p.getCombat().reset();
            }
            case DUEL_MELEE_DISABLED -> {
                Player p = character.getAsPlayer();
                StatementDialogue.send(p, "Melee has been disabled in this duel!");
                p.getCombat().reset();
            }
            case DUEL_RANGED_DISABLED -> {
                Player p = character.getAsPlayer();
                StatementDialogue.send(p, "Ranged has been disabled in this duel!");
                p.getCombat().reset();
            }
            case DUEL_MAGIC_DISABLED -> {
                Player p = character.getAsPlayer();
                StatementDialogue.send(p, "Magic has been disabled in this duel!");
                p.getCombat().reset();
            }
            case TARGET_IS_IMMUNE -> {
                if (character.isPlayer()) {
                    ((Player) character).getPacketSender().sendMessage("This npc is currently immune to attacks.");
                }
                character.getCombat().reset();
            }
            case CASTLE_WARS_FRIENDLY_FIRE -> {
                Player player = character.getAsPlayer();
                if (player != null) {
                    String teamName = Objects.requireNonNull(CastleWars.Team.getTeamForPlayer(player)).name().toLowerCase(Locale.ROOT);
                    player.getPacketSender().sendMessage(teamName + " wants you to kill your enemies!");
                }
                character.getCombat().reset();
            }
            case INVALID_TARGET -> {
                character.getCombat().reset();
            }
        }

    }

    /**
     * Resets combat for the {@link Mobile}.
     */
    public void reset() {
        // TEMP GROUND-TRUTH INSTRUMENTATION (GATE SIGN-OFF pass) --
        // logs ONLY the interesting case (flinch state was actually live at entry) so a stray
        // reset silently wiping it would be visible.
        boolean gtHadLiveFlinchState = character.getTimers().has(TimerKey.COMBAT_ATTACK)
                || character.getTimers().has(TimerKey.FLINCH_IN_COMBAT);
        if (gtHadLiveFlinchState) {
            System.out.println("FLINCH_FIDELITY_GROUND_TRUTH RESET_WHILE_LIVE char=" + character.getIndex()
                    + " combatAttackHas=" + character.getTimers().has(TimerKey.COMBAT_ATTACK)
                    + " inCombatTimerHas=" + character.getTimers().has(TimerKey.FLINCH_IN_COMBAT));
            System.out.flush();
        }
        setTarget(null);
        character.setCombatFollowing(null);
        character.setMobileInteraction(null);
        // FLINCH FIDELITY COMPLETION pass (Amendment 3, GATE SIGN-OFF S.2 restatement): reset must
        // not leak flinch-window state across episode boundaries. The NPC-CHURN ROOT FIX precedent
        // (MinimalEnvironmentBot.java) reuses the SAME NPC/Combat instance across a same-arena reset
        // (target.getCombat().reset() is called there directly) -- without this, a residual tail or
        // marker-bookkeeping value from the PREVIOUS episode would silently gate or misdetect a
        // bare expiry in the NEXT episode. cancel(), not "let it expire" -- an in-flight tail at
        // reset time must not survive into the fresh episode. An arena-switch reset replaces the NPC
        // object outright (fresh Combat, all three pieces already false/absent by construction) --
        // this call is a no-op there, harmless either way. Three pieces now, not two:
        character.getTimers().cancel(TimerKey.FLINCH_IN_COMBAT);
        pendingBareExpiry = false;
        combatAttackActiveLastCheck = false;
        // GATE SIGN-OFF R-A (S.6's own secondary finding, docs/PROJECT_STATE.md): under the
        // generalized design a leftover mid-cooldown COMBAT_ATTACK surviving an episode reset could
        // later expire naturally in the NEW episode and arm a tail attributable to the PREVIOUS
        // episode's combat -- the arena OPPONENT specifically (target.getCombat().reset(), the
        // NPC-CHURN ROOT FIX reuse path), whose stale cooldown would otherwise NOOP the next
        // episode's opening hit (Hunk 3's own IN COMBAT read). NPC-scoped, matching every other
        // flinch-specific gate in this file -- the bot's own leftover COMBAT_ATTACK is a different,
        // non-flinch concern (real OSRS doesn't reset attack cooldown on retarget either) and is
        // deliberately left untouched here.
        if (character.isNpc()) {
            character.getTimers().cancel(TimerKey.COMBAT_ATTACK);
        }
        // TEMP GROUND-TRUTH INSTRUMENTATION (GATE SIGN-OFF pass) -- a
        // genuine POST-clear readback (not a reprint of the pre-clear RESET_WHILE_LIVE state
        // above), gated the same way, so residue is checked directly at the exact moment Amendment
        // 3's own clearing code just ran, rather than inferred from whatever hit happens to land
        // next (which can legitimately be a NOOP for reasons having nothing to do with residue --
        // the NPC's own fresh in-episode swing, or a leftover COMBAT_ATTACK cooldown this method
        // deliberately does NOT clear -- see S.6's own secondary finding, docs/PROJECT_STATE.md).
        if (gtHadLiveFlinchState) {
            System.out.println("FLINCH_FIDELITY_GROUND_TRUTH RESET_POST_CLEAR char=" + character.getIndex()
                    + " combatAttackHas=" + character.getTimers().has(TimerKey.COMBAT_ATTACK)
                    + " inCombatTimerHas=" + character.getTimers().has(TimerKey.FLINCH_IN_COMBAT));
            System.out.flush();
        }
    }

    /**
     * Adds damage to the damage map, as long as the argued amount of damage is
     * above 0 and the argued entity is a player.
     *
     * @param entity the entity to add damage for.
     * @param amount the amount of damage to add for the argued entity.
     */
    public void addDamage(Mobile entity, int amount) {

        if (amount <= 0 || entity.isNpc()) {
            return;
        }

        Player player = (Player) entity;
        if (damageMap.containsKey(player)) {
            damageMap.get(player).incrementDamage(amount);
            return;
        }

        damageMap.put(player, new HitDamageCache(amount));
    }

    /**
     * Performs a search on the <code>damageMap</code> to find which {@link Player}
     * dealt the most damage on this controller.
     *
     * @param clearMap <code>true</code> if the map should be discarded once the killer
     *                 is found, <code>false</code> if no data in the map should be
     *                 modified.
     * @return the player who killed this entity, or <code>null</code> if an npc or
     * something else killed this entity.
     */
    public Optional<Player> getKiller(boolean clearMap) {

        // Return null if no players killed this entity.
        if (damageMap.size() == 0) {
            return Optional.empty();
        }

        // The damage and killer placeholders.
        int damage = 0;
        Optional<Player> killer = Optional.empty();

        for (Entry<Player, HitDamageCache> entry : damageMap.entrySet()) {

            // Check if this entry is valid.
            if (entry == null) {
                continue;
            }

            // Check if the cached time is valid.
            long timeout = entry.getValue().getStopwatch().elapsed();
            if (timeout > CombatConstants.DAMAGE_CACHE_TIMEOUT) {
                continue;
            }

            // Check if the key for this entry has logged out.
            Player player = entry.getKey();
            if (!player.isRegistered()) {
                continue;
            }

            // If their damage is above the placeholder value, they become the
            // new 'placeholder'.
            if (entry.getValue().getDamage() > damage) {
                damage = entry.getValue().getDamage();
                killer = Optional.of(entry.getKey());
            }
        }

        // Clear the damage map if needed.
        if (clearMap)
            damageMap.clear();

        // Return the killer placeholder.
        return killer;
    }

    public boolean damageMapContains(Player player) {
        HitDamageCache damageCache = damageMap.get(player);
        if (damageCache == null) {
            return false;
        }
        return damageCache.getStopwatch().elapsed() < CombatConstants.DAMAGE_CACHE_TIMEOUT;
    }

    /**
     * Getters and setters
     **/

    public Mobile getCharacter() {
        return character;
    }

    public Mobile getTarget() {
        return target;
    }

    public void setTarget(Mobile target) {
        if (this.target != null && target == null && this.method != null) {
            // Target has changed to null, this means combat has ended. Call the relevant hook inside the combat method.
            this.method.onCombatEnded(this.character, this.attacker);
        }

        this.target = target;
    }

    public HitQueue getHitQueue() {
        return hitQueue;
    }

    public Mobile getAttacker() {
        return attacker;
    }

    public void setUnderAttack(Mobile attacker) {
        this.attacker = attacker;
        this.lastAttack.reset();
        this.ticksSinceLastAttack = 0;
    }

    public CombatSpell getCastSpell() {
        return castSpell;
    }

    public void setCastSpell(CombatSpell castSpell) {
        this.castSpell = castSpell;
    }

    public CombatSpell getAutocastSpell() {
        return autoCastSpell;
    }

    public void setAutocastSpell(CombatSpell autoCastSpell) {
        this.autoCastSpell = autoCastSpell;
    }

    public CombatSpell getSelectedSpell() {
        CombatSpell spell = getCastSpell();
        if (spell != null) {
            return spell;
        }
        return getAutocastSpell();
    }

    public CombatSpell getPreviousCast() {
        return previousCast;
    }

    public void setPreviousCast(CombatSpell previousCast) {
        this.previousCast = previousCast;
    }

    public RangedWeapon getRangedWeapon() {
        return rangedWeapon;
    }

    public void setRangedWeapon(RangedWeapon rangedWeapon) {
        this.rangedWeapon = rangedWeapon;
    }

    public Ammunition getAmmunition() {
        return rangeAmmoData;
    }

    public void setAmmunition(Ammunition rangeAmmoData) {
        this.rangeAmmoData = rangeAmmoData;
    }

    public SecondsTimer getPoisonImmunityTimer() {
        return poisonImmunityTimer;
    }

    public SecondsTimer getFireImmunityTimer() {
        return fireImmunityTimer;
    }

    public SecondsTimer getTeleBlockTimer() {
        return teleblockTimer;
    }

    public SecondsTimer getPrayerBlockTimer() {
        return prayerBlockTimer;
    }

    public Stopwatch getLastAttack() {
        return lastAttack;
    }
}
