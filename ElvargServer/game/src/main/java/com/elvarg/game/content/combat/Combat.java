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

    /** Trainer-privileged, deployment-honest read of {@link #attackExecutedThisTick} -- see that
     * field's own doc. Consumed by MinimalEnvironmentBot's payload builder only. */
    public boolean attackExecutedThisTick() {
        return attackExecutedThisTick;
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
            return;
        }

        // Handle attacking
        performNewAttack(false);
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
                for (PendingHit hit : hits) {
                    CombatFactory.addPendingHit(hit);
                }
                method.finished(character, target);

                // Reset attack timer
                if (!graniteMaulSpecial) {
                    int speed = method.attackSpeed(character);
                    character.getTimers().register(TimerKey.COMBAT_ATTACK, speed);
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
        setTarget(null);
        character.setCombatFollowing(null);
        character.setMobileInteraction(null);
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
