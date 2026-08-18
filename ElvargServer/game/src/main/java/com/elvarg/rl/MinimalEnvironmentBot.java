package com.elvarg.rl;

import com.elvarg.game.World;
import com.elvarg.game.collision.RegionManager;
import com.elvarg.game.content.combat.CombatFactory;
import com.elvarg.game.content.combat.FightType;
import com.elvarg.game.content.combat.WeaponInterfaces;
import com.elvarg.game.content.combat.formula.DamageFormulas;
import com.elvarg.game.definition.PlayerBotDefinition;
import com.elvarg.game.entity.impl.npc.NPC;
import com.elvarg.game.entity.impl.npc.NpcAggression;
import com.elvarg.game.entity.impl.playerbot.PlayerBot;
import com.elvarg.game.entity.impl.playerbot.interaction.CombatInteraction;
import com.elvarg.game.entity.impl.playerbot.interaction.MovementInteraction;
import com.elvarg.game.event.EventDispatcher;
import com.elvarg.game.event.events.PlayerPacketsFlushedEvent;
import com.elvarg.game.event.events.PlayerPacketsProcessedEvent;
import com.elvarg.game.model.Item;
import com.elvarg.game.model.Location;
import com.elvarg.game.model.Skill;
import com.elvarg.game.model.container.impl.Equipment;
import com.elvarg.game.model.equipment.BonusManager;
import com.elvarg.game.task.TaskManager;
import com.elvarg.util.ItemIdentifiers;
import com.elvarg.util.timers.TimerKey;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.LinkedList;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Logger;

/**
 * Minimal RL round-trip proof-of-concept bot.
 * <p>
 * Stage 3: blocking-tick integration. Once a session is active, the game-tick thread parks
 * (bounded wait, no config gate - blocking is the only mode) on PlayerPacketsProcessedEvent
 * until a step message arrives via {@link #queueMessage}, which is called from the Netty I/O
 * thread. That thread never blocks and never touches game state - it just hands off the message
 * and calls notifyAll() to wake the tick thread.
 * <p>
 * The step's response is NOT resolved when the action is applied - it is deferred via a queued
 * Runnable, drained on PlayerPacketsFlushedEvent once the tick has fully resolved. The response
 * payload (the enemy_hp_fraction observation field) is built INSIDE that Runnable, at flush-drain
 * time, so the value returned to the client is only ever read after the tick genuinely advanced -
 * the same by-construction guarantee (PROJECT_STATE.md section 8.2) that made the old placeholder
 * counter's flush-bound timing safe now makes reading target.getHitpoints() here safe too.
 */
public class MinimalEnvironmentBot extends PlayerBot {

	private static final Logger logger = Logger.getLogger(MinimalEnvironmentBot.class.getSimpleName());

	/** Total time the tick thread will wait for a step before expiring the session and letting the tick proceed freely. */
	private static final long SESSION_WAIT_BUDGET_MS = 10_000;

	/**
	 * The active arena's spawn tiles/obstacle config (ARENA 01 -- CHOREOGRAPHY + ARENA DEFINITION
	 * pass, PROJECT_STATE.md section 13). Replaces the former hardcoded ARENA_BOT_LOCATION/
	 * ARENA_NPC_LOCATION constants (PROJECT_STATE.md section 8.1) -- see {@link ArenaDefinition}'s
	 * own doc for the ARENA_ID selection mechanism and both definitions' full field values.
	 * <p>
	 * THREAD 2a RESPACE (docs/PROJECT_STATE.md "THREAD 2a DESIGN" record): {@code final} DROPPED --
	 * mirrors {@link #target}'s own existing {@code volatile} mutability. Rebound exactly once per
	 * trainer-controlled arena switch, and only as the LAST step of that sequence (see
	 * {@code performReset()}'s own switch-sequence comment for the ordering invariant this
	 * load-bearingly depends on).
	 */
	private volatile ArenaDefinition arena;

	/**
	 * Index of "attack" within agent/actions.py's COMBAT_ACTIONS list on the Python side:
	 * {@code ["nothing", "attack", "eat_food", "drink_prayer_pot", "drink_combat_pot",
	 * "drink_defence_pot", "toggle_run"]} - "attack" is COMBAT_ACTIONS[1]. Hardcoded here rather
	 * than read from a shared source (no cross-language import path exists) - if that list's
	 * ordering ever changes, this drifts silently. Every combat-head value other than attack and
	 * toggle_run (see {@link #COMBAT_ACTION_TOGGLE_RUN_INDEX}) still collapses to suppress (see
	 * the step branch below).
	 */
	private static final int COMBAT_ACTION_ATTACK_INDEX = 1;

	/**
	 * Index of "toggle_run" within the same COMBAT_ACTIONS list - index 6, the last entry.
	 * RUN/WALK ACTION INCREMENT pass (PROJECT_STATE.md section 13): matches
	 * {@code sim/combat_env.py}'s own already-established semantics (a persistent
	 * {@code is_running} toggle living inside the combat-action head, not a separate head -
	 * {@code sim} is the spec here, per docs/PHASE2_DESIGN.md's re-expression rule, and it
	 * already treats {@code toggle_run} as always-legal in {@code agent/actions.py}'s
	 * {@code compute_action_masks()}). Wired UNCONDITIONALLY server-side (this class has no
	 * concept of the Python-side {@code enable_run_control} compat flag - that flag's whole job
	 * is to keep a compat-mode caller's action mask illegal for this index, and
	 * {@code ElvargSocketEnv} additionally coerces a stray request defensively - see that
	 * module's own doc).
	 */
	private static final int COMBAT_ACTION_TOGGLE_RUN_INDEX = 6;

	/**
	 * (dx, dy) deltas for agent/actions.py's MOVE_ACTIONS
	 * {@code ["idle", "N", "S", "E", "W", "NE", "NW", "SE", "SW"]}, index-for-index. Elvarg's own
	 * {@link com.elvarg.game.model.Direction} enum uses +y = north (NORTH(1, 0, 1), i.e. x=0,y=1)
	 * -- confirmed from source, not assumed -- which happens to be IDENTICAL to sim/grid.py's own
	 * DIRECTIONS dict (N=(0,1), S=(0,-1), E=(1,0), W=(-1,0), NE=(1,1), NW=(-1,1), SE=(1,-1),
	 * SW=(-1,-1)); no axis-flip needed between the two encodings. Index 0 (idle) is never looked
	 * up -- applyMoveAction() returns before indexing for that case.
	 */
	private static final int[][] MOVE_DELTAS = {
			{0, 0},   // idle (unused)
			{0, 1},   // N
			{0, -1},  // S
			{1, 0},   // E
			{-1, 0},  // W
			{1, 1},   // NE
			{-1, 1},  // NW
			{1, -1},  // SE
			{-1, -1}, // SW
	};

	/**
	 * Normalization ceilings for the two static enemy-attribute fields below, matching
	 * agent/observation.py's MAX_MODELED_MAX_HIT (60) and MAX_ATTACK_SPEED_TICKS (10) exactly -
	 * hardcoded here the same way COMBAT_ACTION_ATTACK_INDEX above is (no cross-language shared
	 * constant mechanism exists), so a change to either Python constant would silently
	 * desynchronize this pair.
	 */
	private static final double MAX_MODELED_MAX_HIT = 60.0;
	private static final double MAX_ATTACK_SPEED_TICKS = 10.0;

	/**
	 * GEOMETRY-FIELD WIRING PASS: matches agent/observation.py's MAX_OBSERVABLE_DISTANCE (20)
	 * exactly, same hardcoded-pair convention as the ceilings above -- no cross-language shared
	 * constant mechanism exists, so a change to the Python constant would silently desynchronize
	 * this one.
	 */
	private static final double MAX_OBSERVABLE_DISTANCE = 20.0;

	/**
	 * GEOMETRY-FIELD WIRING PASS: the queued protocol_version deferred-queue item, taken with that
	 * pass. Started at 2 -- 1 is reserved to mean "the implicit pre-versioning format" every payload
	 * before that pass used (no version field existed at all). ARENA 01 -- CHOREOGRAPHY + ARENA
	 * DEFINITION pass bumped this to 3: the payload gained arena_id. RUN/WALK ACTION INCREMENT pass
	 * (PROJECT_STATE.md section 13) bumps this to 4: no NEW payload key is added this time (the
	 * always-emitted, monitoring-only run_energy field already existed) - what changes is BEHAVIOR,
	 * not shape: combat_action_index==6 (toggle_run) goes from a pure no-op (collapsed into the
	 * generic suppress branch, same as every other unimplemented value) to a real, stateful,
	 * episode-crossing side effect. A stale client built against the pre-v4 contract could
	 * unknowingly rely on that action being harmless-if-sent; the version bump forces it to
	 * confront the change instead of silently inheriting new behavior. Bump this and the Python
	 * side's EXPECTED_PROTOCOL_VERSION together, deliberately, whenever the wire CONTRACT changes -
	 * that has always meant more than byte-format, per this precedent.
	 * <p>
	 * THREAD 2a RESPACE (docs/PROJECT_STATE.md "THREAD 2a DESIGN" record): bumped 4 -> 5 -- the
	 * FIELD_ORDER respace (29 -> 155 fields, agent/observation.py) is a wire-format fork by this
	 * same discipline's own standard, forcing any stale v4 client to fail loud on the mismatch
	 * rather than silently receive a payload shaped for a space it never trained against.
	 */
	private static final int PROTOCOL_VERSION = 5;

	/** Single-bot static reference for this minimal proof - no multi-client login/routing yet. */
	private static volatile MinimalEnvironmentBot instance;

	private volatile long flushCounter = 0;

	/** Flips true on the first step message received; flips false again if the safety-net wait expires. */
	private volatile boolean sessionActive = false;

	private volatile PendingStep pendingStep;

	/** The single target NPC for this minimal proof - wired in from Server.java after both are spawned. */
	private volatile NPC target;

	/** Only ever touched from the single game-tick thread (queued in the processed handler, drained in the flushed handler). */
	private final Queue<Runnable> onFlushTasks = new LinkedList<>();

	/**
	 * H2-INSTRUMENT (PROJECT_STATE.md section 13's H2 - FIRST VALID TEST pass), monitoring-only,
	 * same convention as bot_x/bot_y/run_energy - NOT part of the observation contract
	 * (agent/observation.py's FIELD_ORDER). Snapshotted in onPacketsProcessed() at combat-decode
	 * time (see that method's own comment for the exact tick-timing reasoning), read back in
	 * buildObservationPayload() at flush-drain time the SAME tick.
	 */
	private volatile boolean lastStepAttackOffCooldown;
	private volatile boolean lastStepChoseAttack;

	/**
	 * THREAD 2a RESPACE (docs/PROJECT_STATE.md "THREAD 2a DESIGN" record): the flinch sense's raw
	 * counter, {@code ticks_since_bot_landed_hit} on the wire -- Python applies
	 * {@code clip01(t / 10.0)} (agent/elvarg_socket_env.py's own {@code _SENSE_SATURATION_TICKS}).
	 * {@code SENSE_SATURATION_TICKS} is a NEW constant here, not a reuse of
	 * {@code Combat.UNRECIPROCATED_ATTACK_SKIP_TICKS} -- that field is {@code private} to
	 * {@code Combat} (a different package, a semantically different give-up-skip concept) and
	 * making it accessible would be a SECOND stock-file touch beyond the one hunk this pass already
	 * scoped and signed off (CombatFactory.java, Part 3.1). Same VALUE (10) by deliberate design-
	 * record intent, not the same symbol.
	 */
	private static final int SENSE_SATURATION_TICKS = 10;

	/**
	 * Same H2-INSTRUMENT-style monitoring-field pattern as {@link #lastStepAttackOffCooldown} above
	 * -- initializes SATURATED ({@link #SENSE_SATURATION_TICKS}), never 0: a fresh episode reading
	 * 0 would falsely signal "just landed a hit" it never threw. Consumed/incremented once per tick
	 * in {@code onPacketsProcessed()}, re-saturated on every reset -- see that method's own comment
	 * for the exact ordering (the intra-tick increment-after-reset race this design avoids).
	 */
	private volatile int ticksSinceBotLandedHit = SENSE_SATURATION_TICKS;

	/**
	 * Pending-reset FLAG, set by {@link #notifyLandedHit()} (called from
	 * {@code CombatFactory.executeHit()}'s THREAD 2a stock hook, Part 3.1), consumed once per tick
	 * in {@code onPacketsProcessed()}. A flag, not a direct zero of {@link #ticksSinceBotLandedHit}
	 * -- order-independent regardless of exactly when in the tick the stock hook fires relative to
	 * this bot's own per-tick processing (NPCs process before players, so the bot's own landed hit
	 * resolves via the NPC's next {@code Combat.process()} drain, a different point in the tick than
	 * this bot's own {@code onPacketsProcessed()} -- see the design record for the full trace of why
	 * a direct reset would race).
	 */
	private volatile boolean landedHitPendingReset = false;

	/**
	 * No-op interactions, ported from the naton1-reference's NoOpCombatInteraction /
	 * NoOpMovementInteraction (com.github.naton1.rl.util). Without these, PlayerBot's inherited
	 * default AI (PlayerBot.process() -> combatInteraction.process(), and
	 * Player.process() -> ((PlayerBot) this).getMovementInteraction().process()) auto-retaliates
	 * against attackers and wanders autonomously, entirely independent of our RL action.
	 */
	private final CombatInteraction noOpCombatInteraction;
	private final MovementInteraction noOpMovementInteraction;

	public MinimalEnvironmentBot(PlayerBotDefinition definition, ArenaDefinition arena) {
		// Replace the incoming definition's FighterPreset with our own bespoke melee loadout
		// (MinimalMeleeFighterPreset) - username and spawn location are preserved from
		// `definition` (PLAYER_BOTS[0]), only the gear/stats/combat-actions change. This is the
		// same fix for PlayerBot's inherited auto-retaliate as before (stock PlayerBot.process()
		// calls the private field `combatInteraction.process()` directly, not the overridable
		// getCombatInteraction() getter, so CombatInteraction.process()'s attack-execution loop
		// must be neutered via an empty FighterPreset.getCombatActions() array - which
		// MinimalMeleeFighterPreset already returns), just wired through our own preset instead
		// of wrapping ObbyMauler's.
		super(withMeleeLoadout(definition, arena));
		this.arena = arena;
		instance = this;
		this.noOpCombatInteraction = new NoOpCombatInteraction(this);
		this.noOpMovementInteraction = new NoOpMovementInteraction(this);
		// Kept as defense-in-depth for the OTHER call sites that DO go through the getter
		// (CombatFactory.executeHit()'s takenDamage(), PlayerDeathTask's handleDeath/handleDying,
		// BountyHunter's targetAssigned) - and getMovementInteraction() IS reached through the
		// getter (Player.java calls ((PlayerBot) this).getMovementInteraction().process()), so
		// that override was already effective on its own.
		//
		// Also ported from AgentPlayerBot's constructor (naton1-reference): a SEPARATE,
		// independent mechanism from CombatInteraction entirely - Player.autoRetaliate (default
		// true), which CombatFactory.handleRetaliation() (called from executeHit()) uses to
		// schedule a counter-attack task whenever this bot takes a hit. Belt-and-suspenders with
		// the empty-combat-actions fix above, matching the reference's own layered approach.
		setAutoRetaliate(false);
		EventDispatcher.getGlobal().add(PlayerPacketsProcessedEvent.class, this::onPacketsProcessed);
		EventDispatcher.getGlobal().add(PlayerPacketsFlushedEvent.class, this::onPacketsFlushed);
		logger.info("[MinimalEnv] bot constructed: " + definition.getUsername());
	}

	private static PlayerBotDefinition withMeleeLoadout(PlayerBotDefinition definition, ArenaDefinition arena) {
		// Override the spawn location instead of preserving definition.getSpawnLocation()
		// (PLAYER_BOTS[0]'s (3085, 3528), inside Wilderness level 2 - confirmed via
		// WildernessArea's Boundary(2940, 3392, 3525, 3968)) with the selected arena's own bot
		// spawn (ARENA 01 -- CHOREOGRAPHY + ARENA DEFINITION pass) - (3089, 3466) for both
		// ARENA_00/ARENA_01 today, verified non-wilderness, non-multi, walkable (live
		// RegionManager.blocked() check), and >55 tiles from the nearest default NPC spawn.
		// Entirely within com.elvarg.rl - GameConstants (PLAYER_BOTS[0]'s own constant, shared
		// with the stock login-triggered bot-spawn loop) is left untouched.
		return new PlayerBotDefinition(definition.getUsername(), arena.botSpawn,
				new MinimalMeleeFighterPreset());
	}

	@Override
	public void onLogin() {
		super.onLogin();
		// super.onLogin() -> Presetables.load() -> resetAttributes() -> WeaponInterfaces.assign()
		// picks the FIRST FightStyle.AGGRESSIVE option in the weapon's FightType array by default
		// (WeaponInterfaces.java) - for a scimitar that's SCIMITAR_SLASH (+3 strength), not
		// SCIMITAR_CHOP (+3 attack). MinimalMeleeFighterPreset's stat target assumes Accurate
		// stance (matching the Python sim's "+3 attack/+0 strength"), so force it explicitly
		// here rather than relying on the default.
		setFightType(FightType.SCIMITAR_CHOP);

		// NPC-AGGRESSION FIX, root cause (PROJECT_STATE.md section 13's NPC AGGRESSION pass):
		// Hobgoblin 3049's npc_defs.json already has "aggressive": true and
		// "combatFollowDistance": 7 (both already OSRS-faithful) - the actual blocker was
		// NpcAggression.runAggression()'s tolerance gate reading a never-started SecondsTimer as
		// permanently "already elapsed." See performReset() for where the timer is now started -
		// TOLERANCE-CLOCK FIX pass moved ownership there (single site, restarted every episode),
		// reversing this method's original "start once at login" choice. Not started here anymore.
	}

	@Override
	public CombatInteraction getCombatInteraction() {
		return this.noOpCombatInteraction;
	}

	@Override
	public MovementInteraction getMovementInteraction() {
		return this.noOpMovementInteraction;
	}

	public static MinimalEnvironmentBot getInstance() {
		return instance;
	}

	/**
	 * THREAD 2a RESPACE: called from {@code CombatFactory.executeHit()}'s stock hook (Part 3.1)
	 * when {@code attacker == getInstance()} lands a hit. Sets the pending flag only -- see
	 * {@link #landedHitPendingReset}'s own doc for why this isn't a direct counter zero.
	 */
	public void notifyLandedHit() {
		this.landedHitPendingReset = true;
	}

	/**
	 * STEP 2 (tick-level cross-validation, PROJECT_STATE.md section 13's BONUS-STATE AUDIT
	 * follow-up): the shared join key other packages (the combat-roll diagnostic prints) use to tag
	 * a roll with "which flush/observation row it belongs to," so the Python side can align a
	 * diagnostic log line to an exact CSV row WITHOUT relying on a separate, independently
	 * incrementing counter -- the v2 flushCounter/CSV-timestep 2.02x mismatch (SOURCE-READ +
	 * EXISTING-DATA FORENSICS pass, Part 3d) is a known trap this getter exists to avoid recreating.
	 */
	public long getFlushCounter() {
		return flushCounter;
	}

	public void setTarget(NPC target) {
		this.target = target;
	}

	/**
	 * Called from the Netty I/O thread. Never blocks, never touches game state - just hands the
	 * message off and wakes the tick thread if it's parked waiting.
	 */
	public synchronized void queueMessage(String rawLine, CompletableFuture<String> responseFuture) {
		if (this.pendingStep != null) {
			responseFuture.completeExceptionally(new IllegalStateException("Message already queued"));
			return;
		}
		this.pendingStep = new PendingStep(rawLine, responseFuture);
		this.sessionActive = true;
		logger.info("[MinimalEnv] step queued, waking tick thread if parked");
		notifyAll();
	}

	private void onPacketsProcessed(PlayerPacketsProcessedEvent event) {
		if (event.getPlayer() != this) {
			return;
		}
		if (!sessionActive) {
			// No client has connected yet (or the session expired) - free tick, no blocking.
			return;
		}

		final PendingStep step = waitForStep();
		if (step == null) {
			// Bounded wait expired with nothing received; session already flipped inactive and
			// logged inside waitForStep().
			return;
		}

		final String action = parseAction(step.message());
		String resetErrorPayload = null;

		// THREAD 2a RESPACE: consume/increment ONCE PER TICK, unconditionally, BEFORE the
		// reset/step branch dispatch below - runs regardless of which branch fires this tick (the
		// reset branch immediately below unconditionally re-saturates afterward, overriding
		// whatever this computed - see landedHitPendingReset's own doc for why the flag-consume
		// shape, not a direct reset from the stock hook, is what makes this order-independent).
		if (landedHitPendingReset) {
			ticksSinceBotLandedHit = 0;
			landedHitPendingReset = false;
		} else {
			ticksSinceBotLandedHit = Math.min(ticksSinceBotLandedHit + 1, SENSE_SATURATION_TICKS);
		}

		if ("reset".equals(action)) {
			// Runs entirely on the tick thread, same as the attack below - see performReset()'s
			// own doc for the full reset design (PROJECT_STATE.md section 13). THREAD 2a RESPACE:
			// now takes the raw message so it can parse its own optional arena_id -- same
			// independent-re-parse style every other parse method here already uses.
			resetErrorPayload = performReset(step.message());
			// H2-INSTRUMENT: no combat decode happens on a reset tick - clear the snapshot fields so
			// a reset-boundary observation never leaks the PRIOR episode's last-step values.
			lastStepAttackOffCooldown = false;
			lastStepChoseAttack = false;
			// THREAD 2a RESPACE: episode boundary re-saturates, unconditionally overriding whatever
			// the uniform consume/increment above just computed - a fresh episode must never read a
			// low/zero value it didn't earn (landedHitPendingReset cleared too, so a pending flag
			// from the tick just before a reset can't leak a stale "just landed" read into the next
			// episode either).
			ticksSinceBotLandedHit = SENSE_SATURATION_TICKS;
			landedHitPendingReset = false;
		} else if ("step".equals(action)) {
			// COMBAT ACTION INDEX parsed EARLY, before movement - RUN/WALK ACTION INCREMENT pass
			// (PROJECT_STATE.md section 13). Needed so toggle_run (if requested) can flip
			// isRunning BEFORE applyMoveAction() reads it for its own 2-tile-run queueing decision
			// below - matching sim/combat_env.py's own within-tick order exactly (its step 3,
			// "Player consumable: only toggle_run is modeled", runs before step 4, "Player
			// movement" - see that module's own docstring). Parsed once here and reused below
			// (was previously parsed later, inside the target!=null block, when toggle_run was
			// still inert and this ordering didn't matter).
			final int combatActionIndex = parseCombatActionIndex(step.message());
			if (combatActionIndex == COMBAT_ACTION_TOGGLE_RUN_INDEX) {
				this.setRunning(!this.isRunning());
				logger.info("[MinimalEnv] step received, toggled running to " + this.isRunning());
			}

			// MOVE HEAD (index 0) applied next, before the combat decode below - this ordering IS
			// the same-tick move+attack semantics decision (PROJECT_STATE.md section 13's move-head
			// pass). See applyMoveAction()'s own doc for the full reasoning; short version: idle is
			// side-effect-free, and a real direction hard-cancels combat (target/combatFollowing) via
			// the same walkToReset() path a real click-to-walk packet uses
			// (MovementPacketListener.execute(), PROJECT_STATE.md section 15 Group A's injection-path
			// leaning, now locked in). Calling this BEFORE the combat decode means an "attack" chosen
			// the SAME tick re-sets the target AFTER any move-triggered cancel (see the attack branch
			// below) - movement still owns the feet, but doesn't blanket-veto a same-tick attack.
			applyMoveAction(parseMoveActionIndex(step.message()));

			if (target != null) {
				// H2-INSTRUMENT (PROJECT_STATE.md section 13's H2 - FIRST VALID TEST pass): snapshot
				// the attack-cooldown state HERE, before combat resolves later this same tick at
				// Player.java:404. getTimers().process() (the per-tick decrement) already ran at the
				// very TOP of Player.process(), before this PlayerPacketsProcessedEvent dispatch - so
				// this read reflects "was a fresh attack rollable this tick," unconflated by a fresh
				// COMBAT_ATTACK registration that would only happen LATER in this same tick if the
				// combat head chooses attack and it resolves. Reading this same value at flush-drain
				// time (after resolution) would incorrectly show "on cooldown" even for an attack that
				// was off-cooldown at decision time, since a landed attack registers its own fresh
				// cooldown before flush. Monitoring-only (see buildObservationPayload()) - not part of
				// the observation contract (agent/observation.py's FIELD_ORDER), same convention as
				// bot_x/bot_y/run_energy.
				lastStepAttackOffCooldown = !this.getTimers().has(TimerKey.COMBAT_ATTACK);
				lastStepChoseAttack = (combatActionIndex == COMBAT_ACTION_ATTACK_INDEX);
				if (combatActionIndex == COMBAT_ACTION_ATTACK_INDEX) {
					// DEFERRED RESOLUTION, not an immediate attack() call - this is the other half of
					// the same-tick move+attack semantics decision (PROJECT_STATE.md section 13).
					// Player.process()'s call order (Player.java) is: dispatch
					// PlayerPacketsProcessedEvent (this code, :397) -> getMovementQueue().process()
					// (:401, applies THIS tick's movement, including anything applyMoveAction() just
					// queued above) -> getCombat().process() (:404, which calls
					// performNewAttack(false) whenever target != null and off cooldown - the SIXTH
					// path, PROJECT_STATE.md section 8.1). Setting only the target here (not calling
					// attack()/performNewAttack() synchronously) means the actual range check happens
					// at :404, AFTER :401's movement has already been applied - i.e. against the
					// POST-movement position, matching docs/PHASE2_DESIGN.md's within-tick order
					// ("Player attack resolves against the POST-movement position") as a natural
					// consequence of Elvarg's OWN existing call order, not by restructuring it.
					// performNewAttack() unconditionally sets combatFollowing/mobileInteraction itself
					// (Combat.java:96-99) whenever target != null, so nothing is lost by not calling
					// attack()'s own wrapper. Verified this does NOT touch the k=1 outgoing-lag fact
					// (PROJECT_STATE.md section 8.2): World.process() runs the ENTIRE player-combat
					// barrier (which is Player.process(), including both :397 and :404) as one
					// GameSyncTask, strictly after the separate NPC-combat barrier has already fully
					// completed for this tick (World.java) - deferring from :397 to :404 stays inside
					// that same barrier, so it cannot change which GLOBAL barrier the outgoing hit
					// gets queued into.
					//
					// The former "INJECT read right after attack() applied, pre-resolution" diagnostic
					// log that used to live here is gone: there is nothing to read yet at this point in
					// the tick under deferred resolution (the hit isn't rolled until :404). The
					// FLUSH read logged in onPacketsFlushed() below remains the authoritative
					// post-resolution observation point (PROJECT_STATE.md section 8.2's by-construction
					// guarantee is unaffected by this change).
					this.getCombat().setTarget(target);
					logger.info("[MinimalEnv] step received, attack target set (resolves post-movement in "
							+ "getCombat().process())");
				} else {
					// SUPPRESS: attack-only scope (Model A, PROJECT_STATE.md section 15 Group A
					// open question 3) - every other combat-head value still collapses here,
					// including toggle_run (RUN/WALK ACTION INCREMENT pass: its own side effect
					// already fired ABOVE, before movement - it still isn't "attack", so this tick
					// still suppresses combat, exactly matching sim/combat_env.py's own mutual
					// exclusivity, where combat_action is a single categorical choice per tick) and
					// the still-not-yet-wired consumable actions (eat_food/drink_*), not just
					// "nothing". Prayer head values are ignored entirely (masked-inert, section 13's
					// ACTION-SPACE CONTRACT DECISION).
					//
					// Combat.reset() here is NOT optional cleanup - it's the only thing that
					// actually suppresses the attack. `target` stays set across ticks once first
					// attacked, and Combat.process() (Player.java:404) re-attacks autonomously off
					// that persisted target every off-cooldown tick regardless of what we inject
					// (PROJECT_STATE.md section 8.1's SIXTH attack-driving path) - simply not
					// calling attack() this tick would NOT stop that autonomous call from firing
					// later this same tick. Clearing target here does: Combat.performNewAttack()
					// no-ops at target==null (Combat.java:88). Redundant-but-harmless if
					// applyMoveAction() above already cleared it via walkToReset() this same tick.
					//
					// TRIPWIRE - this depends on dispatch ORDER, not just presence: this code runs
					// from the PlayerPacketsProcessedEvent dispatch (Player.java:397), which
					// precedes getCombat().process() (Player.java:404) within the SAME
					// Player.process() call, so our reset() lands before that tick's autonomous
					// attempt ever runs. If this dispatch point ever moves to fire AFTER
					// getCombat().process(), BOTH the attack and suppress paths break silently:
					// the deferred attack-target-set above would resolve a tick late, and reset()
					// would clear the target only after the autonomous call already fired, so
					// suppression would stop suppressing.
					this.getCombat().reset();
					logger.info("[MinimalEnv] step received, action suppressed (combat_action_index="
							+ combatActionIndex + ")");
				}
			} else {
				lastStepAttackOffCooldown = false;
				lastStepChoseAttack = false;
				logger.info("[MinimalEnv] step received, no attack applied (no target)");
			}
		} else {
			lastStepAttackOffCooldown = false;
			lastStepChoseAttack = false;
			logger.info("[MinimalEnv] action received (valid=false), nothing applied");
		}

		// Defer resolution to flush-drain time - do NOT complete the future here.
		final String finalAction = action;
		final String finalResetErrorPayload = resetErrorPayload;
		onFlushTasks.add(() -> {
			final String payload;
			if (finalResetErrorPayload != null) {
				// Already a fully-formed error payload (may carry "retryable" - see
				// performReset()) - use as-is, don't re-wrap.
				payload = finalResetErrorPayload;
			} else if ("step".equals(finalAction) || "reset".equals(finalAction)) {
				payload = buildObservationPayload();
			} else {
				payload = "{\"error\":\"expected a step or reset action\"}";
			}
			logger.info("[MinimalEnv] resolving action future at flush-drain: " + payload);
			step.future().complete(payload);
		});
	}

	/**
	 * Reset verb: restores the bot and target NPC to full HP at their known arena tiles, clears
	 * combat state on both via the real cancel path (Combat.reset() - PROJECT_STATE.md section 15
	 * Group A), and clears any pending hits in both HitQueues so a hit rolled just before the
	 * reset cannot silently land afterward. Runs entirely on the tick thread, called from
	 * onPacketsProcessed() before this tick's own Combat.process()/HitQueue.process() drain (see
	 * Player.java: PlayerPacketsProcessedEvent dispatches before getCombat().process() within the
	 * same Player.process() call) - so clearing here is early enough to catch a hit the NPC-combat
	 * barrier already queued this same tick, before it would otherwise drain later in this same
	 * tick's Player.process() continuation.
	 * <p>
	 * Returns null on success (caller then builds the normal observation payload via
	 * buildObservationPayload(), same as a step response - payload shape unchanged). Returns a
	 * fully-formed JSON error payload string on failure - never throws (PROJECT_STATE.md section
	 * 8.1: an uncaught throw here would be caught by World.java's per-player GameSyncTask and call
	 * requestLogout() on this bot).
	 */
	private String performReset(String message) {
		try {
			if (target == null) {
				// Let buildObservationPayload()'s own null-check produce the error - same
				// convention step already relies on when target is unset.
				return null;
			}

			if (this.isDying()) {
				// Player.setHitpoints() silently no-ops while isDying (PlayerDeathTask's ~3-tick
				// death sequence, ticks 2->0) - forcing a heal past the engine's own death state
				// machine risks inconsistent state, so don't try. Honest transient error instead:
				// "retryable" is LOAD-BEARING - it's how the Python side distinguishes this from a
				// hard error (target==null, bad NPC data). Do not drop it.
				logger.warning("[MinimalEnv] reset requested while bot is dying - transient, retryable");
				return "{\"error\":\"bot is currently dying, retry reset\",\"retryable\":true}";
			}

			// THREAD 2a RESPACE (docs/PROJECT_STATE.md "THREAD 2a DESIGN" record): trainer-
			// controlled arena switch. Lands here -- after both dying-state guards (the bot's own
			// above; the NPC's own mid-death check is duplicated below, specifically for the
			// switch, since the existing one further down this method exists for a DIFFERENT
			// reason and fires too late) -- and before every arena-dependent site below
			// (this.moveTo(arena.botSpawn), target.moveTo(arena.npcSpawn), the NPC-INSTANCE-LOSS
			// backstop's own arena.npcSpawn read).
			String requestedArenaId = parseArenaId(message);
			if (requestedArenaId != null) {
				ArenaDefinition requestedArena;
				try {
					requestedArena = ArenaDefinition.byId(requestedArenaId);
				} catch (IllegalArgumentException e) {
					// FAIL LOUD: never a silent ARENA_00 fallback -- silently training on the wrong
					// arena is a corrupted experiment, worse than a crash. Same resetErrorPayload
					// channel as the dying-state errors, but NOT retryable -- this is a permanent
					// request error (a typo'd/unknown id), not a transient race, so the Python
					// side's own reset() raises immediately rather than retrying it away.
					logger.warning("[MinimalEnv] reset requested an unknown arena_id=" + requestedArenaId
							+ ": " + e.getMessage());
					return "{\"error\":\"unknown arena_id: " + requestedArenaId + "\"}";
				}
				if (requestedArena != this.arena) {
					// MID-DEATH: the switch needs the NPC's own isDying() check EARLY, before
					// despawning it -- unlike a same-arena reset (which reuses target, per the
					// NPC-CHURN ROOT FIX precedent), a switch replaces the NPC outright, so the
					// existing later check (which exists to protect that reuse from racing its own
					// async death task) doesn't cover this path. Same existing message/contract,
					// deliberately duplicated, not a new failure mode.
					if (target.isDying()) {
						logger.warning("[MinimalEnv] reset requested an arena switch while NPC is "
								+ "dying - transient, retryable");
						return "{\"error\":\"npc is currently dying, retry reset\",\"retryable\":true}";
					}
					// ORDERING INVARIANT, load-bearing: capture old -> deregister old (explicit
					// arg, NOT this.arena -- correctness comes from the call sequence, not field
					// state) -> despawn old NPC -> register new (explicit arg) -> create+setTarget
					// new NPC -> rebind this.arena LAST. Rebinding early would make
					// deregisterObstacles tear down the NEW arena's not-yet-registered obstacles
					// and leak the old ones.
					ArenaDefinition oldArena = this.arena;
					ArenaDefinition newArena = requestedArena;
					ArenaDefinition.deregisterObstacles(oldArena);
					// Ghost-sweep precedent (see the backstop further below): unconditional queued
					// removal, not a reuse -- a switch replaces the NPC outright, unlike the
					// NPC-CHURN ROOT FIX's own "hold one instance across the run" convention for a
					// same-arena reset.
					World.getRemoveNPCQueue().add(this.target);
					ArenaDefinition.registerObstacles(newArena);
					NPC newTarget = NPC.create(newArena.npcId, newArena.npcSpawn);
					World.getAddNPCQueue().add(newTarget);
					setTarget(newTarget);
					// Same NPC.create() radius gap Server.java's own boot sequence already works
					// around (NPC.create() bypasses NpcSpawnDefinitionLoader, so
					// NPCMovementCoordinator.radius stays at Java's default 0 -- a degenerate leash
					// -- unless set explicitly): mirrored here, not skipped.
					newTarget.getMovementCoordinator().setRadius(newArena.npcCoordinatorRadius);
					this.arena = newArena;
					logger.info("[MinimalEnv] reset: arena switched " + oldArena.id + " -> " + newArena.id);
				}
				// requestedArena == this.arena: NO-OP by design -- zero churn, falls through to the
				// existing reset logic below exactly as if arena_id had been absent.
			}

			this.getCombat().reset();
			this.getCombat().getHitQueue().clear();
			// Same leak-prevention rationale as HitQueue.clear() above, now that the move head can
			// queue steps: a leftover queued waypoint from just before this reset (should not
			// normally happen under the one-action-per-tick blocking protocol, but cheap to guard)
			// would otherwise survive the teleport below and walk the freshly-reset bot off its
			// arena tile on the very next tick.
			this.getMovementQueue().reset();
			this.moveTo(arena.botSpawn);
			this.setHitpoints(this.getSkillManager().getMaxLevel(Skill.HITPOINTS));

			// EQUIPMENT-LOSS FIX (Step 2's named design consequence): re-assert the authored melee
			// loadout every episode, unconditionally -- not just after a death. Root cause (Step 2,
			// PROJECT_STATE.md section 13): PlayerDeathTask wipes equipment on death (this arena
			// tile has no Area, so loseItems defaults true and never gets set false) and its own
			// resetAttributes() call re-caches BonusManager off the now-empty equipment and
			// re-resolves WeaponInterfaces to UNARMED -- and this method never undid either, so a
			// bot that died once fought the rest of the run unarmed (attRoll 2560/maxHit 4 instead
			// of 3655/5), silently, with no signal anywhere in the existing observation contract.
			// SURGICAL re-assert chosen over a full Presetables.load() replay: load() dumps
			// non-spawnable current items to the bank before re-adding them from the preset
			// (Presetables.java's own valuable-item handling), which would needlessly cycle the
			// scimitar through the bank every single episode and risks a hard failure ("you don't
			// have X in inventory/equipment/bank") if a later load ever finds fewer copies than the
			// preset expects -- entirely avoidable by not going through that path at all. This block
			// covers EXACTLY what resetAttributes() perturbs on death that this method didn't already
			// cover elsewhere: equipment (blank per the wipe -> re-set here), the weapon interface and
			// bonus cache (re-derived from equipment via WeaponInterfaces.assign()/BonusManager.update()
			// -- both must run in that order, matching resetAttributes()'s own call order), and fight
			// type (onLogin()'s one-time SCIMITAR_CHOP force only ever ran once, at initial login).
			// Everything else resetAttributes() touches on death (special%, vengeance, poison/fire/
			// teleblock immunity, freeze, prayer-block, wilderness level, recoil, skull, skill levels
			// reset to max, run energy, movement-block cleared) is either already explicitly restored
			// elsewhere in this method (HP just above, run energy below) or structurally inert for
			// this non-wilderness, no-poison, no-prayer, no-special, non-PK arena -- checked against
			// resetAttributes()'s full body, not assumed. Ground items dropped at death
			// (ItemOnGroundManager, PlayerDeathTask) are NOT a fix target: this bot is never the
			// killer-of-record for its own equipment loss (Combat.addDamage() only credits PLAYER
			// attackers, never NPCs, so getKiller() is always empty and the dropped scimitar
			// registers to the bot itself) and ItemOnGroundManager's own state-update cycle
			// (STATE_UPDATE_DELAY=50 ticks per state, a few states to full removal) auto-expires each
			// drop well within one 500-tick episode -- confirmed from source, not assumed;
			// no accumulation across a long run, no fix needed.
			this.getEquipment().setItem(Equipment.WEAPON_SLOT, new Item(ItemIdentifiers.MITHRIL_SCIMITAR));
			WeaponInterfaces.assign(this);
			BonusManager.update(this);
			setFightType(FightType.SCIMITAR_CHOP);

			// PROJECT_STATE.md section 13's flagged non-stationarity fix: run energy was previously
			// left untouched by reset (only HP/position/combat state were restored), so it drained
			// monotonically across an episode's running use and never recovered at episode boundaries
			// - a hidden moving target for a policy whose combat head actively reaches for run state
			// (toggle_run was 43.8% of the frozen policy's choices in the Stage-1 eval). Same
			// full-restore value (100) as the existing admin-restore precedent (Player.java's
			// setRunEnergy(100) call in its own full-restore path) - not a new/guessed constant.
			this.setRunEnergy(100);

			// RUN/WALK ACTION INCREMENT pass (PROJECT_STATE.md section 13): same non-stationarity
			// class as run energy just above, newly relevant now that toggle_run actually flips
			// isRunning (previously inert, so this was never reachable state). Without an explicit
			// reset, isRunning would carry over from whatever the PREVIOUS episode last toggled it
			// to. true, not sim/entities.Player's own is_running=False dataclass default (a stated
			// choice, not an oversight - see this pass's own doc: matching Elvarg's pre-existing
			// Player-construction default costs nothing extra to wire, preserves every episode's
			// historical always-run starting condition with zero behavior change for compat-mode
			// callers, and real OSRS players overwhelmingly leave the orb toggled on rather than
			// off by convention - sim's False is a dataclass-field default, not a deliberate
			// simulated-lore choice this pass is obligated to mirror).
			this.setRunning(true);

			// TOLERANCE-CLOCK FIX (PROJECT_STATE.md section 13, follow-up to the NPC AGGRESSION
			// pass): moved from onLogin() (start-once) to here (restart-every-episode), reversing
			// that pass's own "restarting every episode would be unfaithful" call. That argument
			// evaluated faithfulness in the WALL-CLOCK frame (NpcAggression.NPC_TOLERANCE_SECONDS
			// is Guava-Stopwatch real-seconds, per SecondsTimer.java), but under TICK_RATE=1 the
			// frame the agent actually experiences is TICKS, not wall-clock - and within any single
			// ~500-tick episode, the real OSRS tolerance window (10 real minutes at stock 600ms
			// ticks = 1000 ticks) never lapses regardless of machine speed. A start-once-at-login
			// design instead let the timer's WALL-CLOCK 600s lapse mid-TRAINING-RUN (confirmed live:
			// the elvarg_aggression run's tolerance lapsed at ~step 55,273, ~55% of the way through
			// a 100k-step run, entirely a function of this machine's steps/sec) - nonstationary
			// across episodes and machine-dependent, which is a worse-faithfulness failure than the
			// one being avoided. Restarting here instead reproduces the faithful WITHIN-episode
			// behavior (tolerance never lapses inside one episode's ~500 ticks) while making the
			// environment stationary across episodes, exactly like every other piece of
			// episode-scoped state this method already restores (HP, position, combat state,
			// run energy above). Tick-counting the window instead (1000 ticks) was considered and
			// rejected: at ~500 ticks/episode that is only ~2 aggressive episodes before permanent
			// passivity for the rest of the run - faithful to a player loitering for 10 straight
			// real minutes, not to what a training episode represents (a fresh encounter).
			this.getAggressionTolerance().start(NpcAggression.NPC_TOLERANCE_SECONDS);

			// NPC-RESPAWN BUG FIX (ENGAGEMENT-LEVER SELECTION pass root cause, PROJECT_STATE.md
			// section 13): the bot's OWN "who is attacking me" reference is never cleared by
			// anything above (Combat.reset() only touches target/combatFollowing/mobileInteraction,
			// never attacker -- confirmed from source, same finding as the BONUS-STATE AUDIT pass's
			// read of the same method). A stale reference here is exactly what let a ghost NPC (see
			// below) block every subsequent attack via CombatFactory.canAttack()'s ALREADY_UNDER_ATTACK
			// case (attacker.getCombat().getAttacker() != target) -- cleared unconditionally, every
			// reset, regardless of branch, so this can never happen again even if a ghost somehow
			// still slips past the prevention below.
			this.getCombat().setUnderAttack(null);

			// NPC-CHURN ROOT FIX (PROJECT_STATE.md section 13): HOLDS ONE TARGET INSTANCE across the
			// ENTIRE run instead of create/destroy-cycling a new NPC object every time it dies -- the
			// predecessor NPC-RESPAWN and NPC-INSTANCE-LOSS fixes both treated symptoms of this same
			// churn (a ghost coexisting with our own fresh object; our own fresh object later vanishing
			// to a queued removal colliding with a reused world-slot index) without removing the churn
			// itself. Source-confirmed this pass, not assumed: `NPCDeathTask.stop()` only DEREGISTERS
			// the dying NPC (`World.getRemoveNPCQueue()` -> `MobileList.remove()`, which flips
			// `registered=false` and frees its slot) -- it never destroys the Java object.
			// `NPC.onAdd()`/`onRemove()` are BOTH true no-ops (verified from source, not assumed), and
			// `MobileList.add(e)` only requires `!e.isRegistered()` to succeed -- so the SAME object can
			// be re-registered after being deregistered, with a freshly assigned slot, indistinguishable
			// from a brand-new one. Reusing `target` this way means our OWN code never creates a second
			// object competing for a world slot again -- the precondition the index-collision hazard
			// needed (repeated create/destroy churn from OUR OWN reset logic) is gone, not just
			// contained.
			//
			// The NPC's own death sequence (NPCDeathTask, 2 of its own ticks: blocks movement/plays the
			// death animation, THEN deregisters + conditionally schedules a stock respawn) can still be
			// mid-flight when this reset() call arrives -- unlike the old create-a-new-object design,
			// which simply abandoned the dying object and never needed to care, REUSING it means we
			// must not touch HP/position/registration while its own death task might still be about to
			// deregister it out from under us (or resurrect a spurious stock respawn for an NPC we
			// already revived). Same retryable-error contract already used for the BOT's own isDying()
			// check above, and served by the SAME existing client-side retry loop
			// (ElvargSocketEnv.reset(), MAX_RESET_RETRY_CYCLES) with no Python-side change needed.
			// Deliberately checks ONLY isDying(), not HP<=0: isDying() is false both when the NPC never
			// died AND once its death sequence has genuinely finished (NPCDeathTask.stop() clears it) --
			// HP<=0 alone would never resolve (nothing else in this method reset HP yet), which is
			// exactly the infinite-retry bug an earlier draft of this fix caught before landing it.
			if (target.isDying()) {
				logger.warning("[MinimalEnv] reset requested while NPC is dying - transient, retryable");
				return "{\"error\":\"npc is currently dying, retry reset\",\"retryable\":true}";
			}

			// Cancel any pending stock respawn for THIS EXACT object. Retried against the SAME
			// persistent reference on every reset for the rest of the run (unlike the old design, where
			// each episode's `target` was a brand-new object a missed cancellation could never be
			// retried against) -- a "too early" miss (NPCDeathTask.stop() hasn't submitted the respawn
			// task yet) self-heals on THIS SAME object's next death, not never.
			TaskManager.cancelTasks(target);
			target.getCombat().reset();
			target.getCombat().getHitQueue().clear();
			target.getCombat().setUnderAttack(null);
			// MOVEMENT-BLOCK FIX (PROJECT_STATE.md section 13, PURSUIT-RACE FIX pass): NPCDeathTask.java:58
			// sets MovementQueue.blockMovement=true when the NPC's death animation starts and NOTHING ever
			// clears it for a reused NPC instance (only Player.java:731/TeleportHandler.java:93 clear it,
			// player-only) -- discovered live diagnosing why the pursuit-race fix's own combatFollowing
			// linkage held correctly (confirmed via direct telemetry) yet the NPC still never moved after
			// a death-triggered reset: getMobility() reads INVALID whenever isMovementBlocked() is true,
			// which silently no-ops MovementQueue.process() regardless of combatFollowing. A fresh
			// (never-died) NPC never carried this stale flag, which is why this was invisible until a
			// pass specifically walked the bot away AFTER forcing deaths first.
			target.getMovementQueue().setBlockMovement(false);
			target.moveTo(arena.npcSpawn);
			target.setHitpoints(target.getCurrentDefinition().getHitpoints());
			// TRUE when this call just enqueued a re-registration -- World.process()'s NPC add/remove
			// queue draining runs EARLY in that method (before player processing, where this code runs),
			// so an add() queued HERE cannot possibly have drained by the time ANY check later in this
			// SAME call reads World.getNpcs() again -- confirmed the hard way, not assumed: an earlier
			// draft of this fix ran the instance-loss backstop's presence scan unconditionally right
			// after this block, which therefore ALWAYS read `target` as "missing" on every single
			// re-registration (it hadn't drained yet, not because it was ever actually lost) and fell
			// back to creating a brand-new replacement object EVERY reset -- silently reintroducing the
			// exact create/destroy churn this whole fix exists to eliminate. Caught via the Part 2
			// verification run itself (persistent npc_instance_count=2 for a fight's entire duration,
			// not the brief single-tick blip a genuine race would produce), not assumed correct from
			// the code reading right the first time.
			boolean justReregistered = !target.isRegistered();
			if (justReregistered) {
				// Died since the last reset and its death sequence has fully resolved (isDying() check
				// above already passed) -- re-register the SAME instance, never a new one.
				World.getAddNPCQueue().add(target);
				logger.info("[MinimalEnv] reset: NPC had died, re-registered the SAME instance (no new object created)");
			} else {
				logger.info("[MinimalEnv] reset: NPC healed and repositioned in place");
			}

			// GHOST-NPC SWEEP (backstop, kept): the ONLY way a stray same-id NPC can still appear is an
			// uncancelled STOCK respawn (a cancellation miss on the timing race described above) --
			// never our own churn anymore, since we no longer create competing instances. Cheap to keep,
			// catches that one remaining case.
			for (NPC other : World.getNpcs()) {
				if (other != null && other != target && other.getId() == target.getId()) {
					World.getRemoveNPCQueue().add(other);
					logger.warning("[MinimalEnv] reset: removed a stray/ghost NPC id=" + other.getId()
							+ " (uncancelled stock respawn -- see PROJECT_STATE.md section 13)");
				}
			}

			// NPC-INSTANCE-LOSS backstop (kept explicitly as a cheap, rare-case fallback, NOT the
			// primary mechanism anymore -- the root fix above is expected to make this fire ~never,
			// verified this pass). Skipped entirely when `justReregistered` -- a same-call add() cannot
			// have drained yet (see that block's own comment), so checking here would always be a false
			// positive, not a genuine check. Only meaningful when `target` was ALREADY registered coming
			// into this reset (nothing queued this call), in which case "should already be present but
			// isn't" is a genuine anomaly worth recovering from: `target.isRegistered()` itself cannot
			// be trusted for that check (MobileList.remove(e) sets `e`'s OWN registered flag false, not
			// the flag of whatever object it actually nulled out of the slot array, so a wrongly-evicted
			// `target` can still read isRegistered()==true) -- a REFERENCE scan is the only reliable one.
			// If genuinely absent, the object's bookkeeping is in an inconsistent state a simple re-add
			// can't cleanly resolve -- fall back to a fresh replacement, same as this fix's predecessor.
			if (!justReregistered) {
				boolean targetPresent = false;
				for (NPC n : World.getNpcs()) {
					if (n == this.target) {
						targetPresent = true;
						break;
					}
				}
				if (!targetPresent) {
					logger.warning("[MinimalEnv] reset: target missing from World.getNpcs() despite the churn-root "
							+ "fix (NPC-INSTANCE-LOSS backstop firing -- see PROJECT_STATE.md section 13) -- recovering");
					TaskManager.cancelTasks(this.target);
					final NPC recovered = NPC.create(this.target.getId(), arena.npcSpawn);
					World.getAddNPCQueue().add(recovered);
					this.target = recovered;
				}
			}

			// PURSUIT-RACE FIX (PROJECT_STATE.md section 13): deterministic episode-start engagement,
			// replacing dependence on NpcAggression's own per-tick localNpcs-population race (root-caused
			// last pass -- player.getLocalNpcs() reads empty on the exact tick NpcAggression.process()
			// runs right after reset, missing the tight aggressionDistance() window if the bot has already
			// started moving away by the next tick).
			//
			// NOT simply npc.getCombat().attack(player) (NpcAggression.java:101's own call) -- tried that
			// first, live-diagnosed it failing specifically on a just-died-and-reregistered NPC:
			// Combat.attack() -> performNewAttack() sets combatFollowing unconditionally, but then its own
			// CombatFactory.canAttack() -> validTarget() check requires attacker.isRegistered(), which is
			// still false this same tick (World.getAddNPCQueue().add(target) two lines above cannot have
			// drained yet -- World.process()'s NPC-queue drain runs EARLY, before the player-processing
			// pass this method runs inside of, per the NPC-CHURN ROOT FIX comment above). canAttack()
			// returning INVALID_TARGET then hits performNewAttack()'s own INVALID_TARGET case, which calls
			// character.getCombat().reset() -- wiping the combatFollowing/target that were just set, in
			// the SAME call. Confirmed live: npc_target/combatFollowing were null immediately after
			// attack() specifically when justReregistered was true, non-null when it was false.
			//
			// Fix: set exactly the three fields performNewAttack() sets BEFORE its registration-gated
			// validation (target, combatFollowing, mobileInteraction) directly, bypassing that gate --
			// safe here because this is a fully controlled single-matchup arena (no PvP, no wilderness,
			// no duels for validTarget()/canAttack() to legitimately reject). The actual first attack ROLL
			// still happens through the normal, fully-validated path: target's own next Combat.process()
			// tick calls performNewAttack() again, by which point registration has genuinely drained and
			// canAttack() returns CAN_ATTACK for real -- this fix only removes the race from
			// ENGAGEMENT/PURSUIT starting, not from combat validation itself.
			// LOCKSTEP NOTE (ARENA VALIDATOR pass, PROJECT_STATE.md section 13): these three lines are a
			// deliberate REPLICA of Combat.performNewAttack()'s own pre-validation field sets, not an
			// independent design -- if a future stock change to that block ever adds, removes, or reorders
			// what it sets before its canReach()/canAttack() gate, this replica drifts silently out of sync
			// with it (the same replica-drift class this project has been burned by before -- see the
			// GEOMETRY-FIELD WIRING PASS's own isTargetInMeleeRange() retirement). Check
			// Combat.performNewAttack() first whenever touching this block.
			target.getCombat().setTarget(this);
			target.setCombatFollowing(this);
			target.setMobileInteraction(this);

			return null;
		} catch (Exception e) {
			logger.severe("[MinimalEnv] reset failed unexpectedly: " + e);
			return "{\"error\":\"reset failed: " + e.getMessage() + "\"}";
		}
	}

	/**
	 * Observation-payload swap: enemy_hp_fraction (outgoing/NPC HP) and hp_fraction (the bot's
	 * own HP), each matching agent/observation.py's contract exactly -
	 * {@code _clip01(current / max)}, i.e. {@code max(0.0, min(1.0, current / max))}. Called
	 * from inside the flush-drain Runnable queued above, so both target.getHitpoints() and
	 * this.getHitpoints() here are read at flush-drain. Post-flip (elvarg-rsps commit
	 * 2e5d5c2c, NPC-combat now runs before player-combat), the two directions have DIFFERENT
	 * timing at this same flush read: the bot's own outgoing hit lands with a +1-tick lag
	 * (rolled on tick N, not reflected here until tick N+1 - live-confirmed, 11/11 landed hits
	 * in a full kill staircase), while incoming (NPC-&gt;player) damage is same-tick (rolled on
	 * tick N, already applied by this same tick's flush - live-confirmed, 7/7 landed hits).
	 * Both are CORRECT and OSRS-faithful, not bugs - do not "fix" either to match the other.
	 * The by-construction guarantee that no half-applied mid-tick state is ever visible at
	 * flush is order-independent and still holds (PROJECT_STATE.md section 8.2).
	 * <p>
	 * MINIMAL TRANSFER EXPERIMENT (PROJECT_STATE.md section 13): also emits three STATIC
	 * enemy-attribute fields, constant for the whole fight (read from the NPC definition, not
	 * per-tick combat state), encoded the SAME way agent/observation.py's encode_observation()
	 * does: enemy_attack_style_melee is hardcoded 1.0 (matching observation.py's own hardcoded
	 * one-hot - every NPC modeled so far, including this Hobgoblin, is melee; observation.py has
	 * its own TODO to replace this once ranged/magic NPCs are added, unchanged by this commit);
	 * enemy_max_hit_normalized and enemy_attack_speed are {@code clip01(value / ceiling)} reads
	 * of the NPC definition's maxHit/attackSpeed against the same ceilings as
	 * MAX_MODELED_MAX_HIT/MAX_ATTACK_SPEED_TICKS above. Geometry fields (enemy_distance,
	 * enemy_dx_sign, enemy_dy_sign, enemy_in_my_attack_range, enemy_can_reach_me) are WIRED as of
	 * the GEOMETRY-FIELD WIRING PASS (PROJECT_STATE.md section 13) -- see that pass's extracted
	 * spec (agent/observation.py's encode_observation()) and CombatFactory.isMeleeReachable() for
	 * the shared reach predicate both reach fields and target_in_melee_range now use. Python-side
	 * wiring is opt-in via ElvargSocketEnv's wire_geometry constructor flag (default False,
	 * preserving model_final's trained zero-filled distribution byte-for-byte). enemy_attack_imminent_*
	 * remains deliberately NOT wired -- out of scope for this pass, still zero-filled Python-side.
	 * <p>
	 * MONITORING-ONLY TELEMETRY (PROJECT_STATE.md section 13's retrain-shakedown pass): also emits
	 * {@code bot_x}/{@code bot_y} (absolute position) and {@code run_energy} (0-100). These are
	 * DELIBERATELY NOT part of the observation contract - {@code agent/observation.py}'s
	 * {@code FIELD_ORDER} does not list them, and {@code ElvargSocketEnv._payload_to_observation()}
	 * only ever reads its own whitelisted key set, so these extra keys are inert to the policy; they
	 * exist purely so the Python-side training harness can log two retrain-monitoring watch items
	 * (move-head-commanded vs actual displacement, and run-energy-over-episode, confirming the reset
	 * fix above holds under training) without reconstructing them from indirect signals. Same
	 * provenance standard as reward inputs (section 11's "two provenance standards" note) - training-
	 * time scaffolding, exempt from the deployment-obtainable observation firewall, never fed to the
	 * policy.
	 */
	private String buildObservationPayload() {
		if (target == null) {
			// Do NOT throw here. This runs in the flush-drain Runnable on the single game-tick
			// thread, dispatched from inside World.java's per-player GameSyncTask around
			// PlayerPacketsFlushedEvent - that task's own try/catch would catch an uncaught throw
			// and call player.requestLogout() on THIS bot (World.java:192-208), kicking the RL
			// session out of the game. Either way the pending step future is never completed, so
			// the Python side would only ever see a bare timeout. Complete the future with an
			// explicit error payload instead (same convention as the "expected a step action"
			// path), so the failure is loud where it can be acted on and the bot stays in the
			// world. See PROJECT_STATE.md section 8.1 for the full investigation.
			logger.severe("[MinimalEnv] cannot build observation payload: target is null");
			return "{\"error\":\"observation payload: target is null\"}";
		}
		final int npcMaxHp = target.getCurrentDefinition().getHitpoints();
		if (npcMaxHp <= 0) {
			// Guards an NPC with bad/missing definition data (PROJECT_STATE.md section 9: Elvarg
			// per-monster data is not reliably curated). Error payload, not throw (same tick-thread
			// reasoning as above); and NEVER silently emit enemy_hp_fraction=0.0, which is
			// indistinguishable from a dead NPC.
			logger.severe("[MinimalEnv] NPC max HP is " + npcMaxHp
					+ " - refusing to emit enemy_hp_fraction; check npc_defs.json hitpoints");
			return "{\"error\":\"observation payload: npc max hp is " + npcMaxHp + "\"}";
		}
		final int botMaxHp = this.getSkillManager().getMaxLevel(Skill.HITPOINTS);
		if (botMaxHp <= 0) {
			// Same guard convention as npcMaxHp above, applied to the bot's own HITPOINTS level
			// (the analogous "max HP" source for a player - see PlayerUpdating.java's own use of
			// getMaxLevel(Skill.HITPOINTS) for the client HP bar). Error payload, not throw; never
			// silently emit hp_fraction=0.0, which is indistinguishable from a dead bot.
			logger.severe("[MinimalEnv] bot max HP is " + botMaxHp
					+ " - refusing to emit hp_fraction; check the bot's HITPOINTS skill level");
			return "{\"error\":\"observation payload: bot max hp is " + botMaxHp + "\"}";
		}
		final int npcCurrentHp = target.getHitpoints();
		final double npcRawFraction = (double) npcCurrentHp / (double) npcMaxHp;
		final double enemyHpFraction = Math.max(0.0, Math.min(1.0, npcRawFraction));
		final int botCurrentHp = this.getHitpoints();
		final double botRawFraction = (double) botCurrentHp / (double) botMaxHp;
		final double hpFraction = Math.max(0.0, Math.min(1.0, botRawFraction));

		// Static enemy-attribute fields (constant across the fight) -- see this method's Javadoc
		// "MINIMAL TRANSFER EXPERIMENT" note above for the encoding-parity rationale.
		final double enemyAttackStyleMelee = 1.0;
		final double enemyMaxHitNormalized = clip01(
				target.getCurrentDefinition().getMaxHit() / MAX_MODELED_MAX_HIT);
		final double enemyAttackSpeed = clip01(
				target.getCurrentDefinition().getAttackSpeed() / MAX_ATTACK_SPEED_TICKS);

		// GEOMETRY-FIELD WIRING PASS (PROJECT_STATE.md section 13). Spec extracted from
		// agent/observation.py's encode_observation(), not from memory:
		//   enemy_distance      = clip01(chebyshev_distance(bot, npc) / MAX_OBSERVABLE_DISTANCE=20)
		//   enemy_dx_sign       = sign(npc.x - bot.x)   -- NPC MINUS BOT, not the other way around
		//   enemy_dy_sign       = sign(npc.y - bot.y)
		//   enemy_in_my_attack_range = is_in_melee_range(bot, npc)   -- bot is the attacker
		//   enemy_can_reach_me       = npc.in_attack_range(bot)      -- npc is the attacker
		// The sim's own is_in_melee_range() is a pure orthogonal-adjacency predicate with no wall
		// concept, so in sim these two reach fields are always equal to each other. On Elvarg they
		// are NOT assumed equal -- each is computed independently via CombatFactory.isMeleeReachable()
		// with the attacker/target arguments swapped, since the wall check and the both-moving
		// distance-2 rule are per-attacker-tile, directional facts, not guaranteed symmetric (see
		// the live audit in this same pass for whether they empirically ever diverge here).
		final int distance = this.getLocation().getDistance(target.getLocation());
		final double enemyDistance = clip01(distance / MAX_OBSERVABLE_DISTANCE);
		final int enemyDx = target.getLocation().getX() - this.getLocation().getX();
		final int enemyDy = target.getLocation().getY() - this.getLocation().getY();
		final int enemyDxSign = Integer.signum(enemyDx);
		final int enemyDySign = Integer.signum(enemyDy);
		final boolean enemyInMyAttackRange = CombatFactory.isMeleeReachable(this, target);
		final boolean enemyCanReachMe = CombatFactory.isMeleeReachable(target, this);

		return "{\"hp_fraction\":" + hpFraction
				+ ",\"enemy_hp_fraction\":" + enemyHpFraction
				+ ",\"enemy_attack_style_melee\":" + enemyAttackStyleMelee
				+ ",\"enemy_max_hit_normalized\":" + enemyMaxHitNormalized
				+ ",\"enemy_attack_speed\":" + enemyAttackSpeed
				+ ",\"enemy_distance\":" + enemyDistance
				+ ",\"enemy_dx_sign\":" + enemyDxSign
				+ ",\"enemy_dy_sign\":" + enemyDySign
				+ ",\"enemy_in_my_attack_range\":" + enemyInMyAttackRange
				+ ",\"enemy_can_reach_me\":" + enemyCanReachMe
				+ ",\"bot_x\":" + this.getLocation().getX()
				+ ",\"bot_y\":" + this.getLocation().getY()
				// TRAINER TELEMETRY (GEOMETRY-FIELD WIRING PASS): closes the MECHANICS AUDIT's live-
				// probe gap (no way to independently confirm the NPC's own position, or engineer/
				// verify wall-adjacent geometry, without this). Same provenance convention as bot_x/
				// bot_y -- payload-only, trainer-privileged, deliberately NOT in FIELD_ORDER, never
				// whitelisted into the observation.
				+ ",\"npc_x\":" + target.getLocation().getX()
				+ ",\"npc_y\":" + target.getLocation().getY()
				+ ",\"run_energy\":" + this.getRunEnergy()
				// RUN/WALK ACTION INCREMENT pass (PROJECT_STATE.md section 13): monitoring-only,
				// same provenance convention as bot_x/run_energy -- lets a caller (the validator,
				// training-side diagnostics) directly confirm toggle_run's effect instead of
				// inferring it from position deltas across ticks. Not part of the observation
				// contract; a boolean toggle state is trivially re-derivable from run_energy_fraction
				// trends anyway, so this is convenience telemetry, not a new percept.
				+ ",\"is_running\":" + this.isRunning()
				+ ",\"attack_off_cooldown\":" + lastStepAttackOffCooldown
				+ ",\"attack_chosen\":" + lastStepChoseAttack
				// GEOMETRY-FIELD WIRING PASS: was isTargetInMeleeRange(), a hand-maintained replica of
				// canReach()'s geometry that had already drifted once (missed the wall-clipping fix).
				// Now the same CombatFactory.isMeleeReachable() call enemy_in_my_attack_range above
				// uses -- one source of truth, this field and that one can never diverge again.
				+ ",\"target_in_melee_range\":" + enemyInMyAttackRange
				+ ",\"distance\":" + distance
				// PROTOCOL_VERSION (GEOMETRY-FIELD WIRING PASS -- the queued deferred-queue item,
				// taken with this pass per its own instruction, since this is the first wire-touching
				// pass since it was queued). Starts at 2, not 1 -- 1 is reserved to mean "the implicit
				// pre-versioning format" every payload before this pass used (no version field at all).
				// The Python side checks this on reset and raises loudly on a mismatch, so a future
				// wire-format fork fails loud instead of silently misparsing.
				+ ",\"protocol_version\":" + PROTOCOL_VERSION
				// ARENA 01 -- CHOREOGRAPHY + ARENA DEFINITION pass (PROJECT_STATE.md section 13):
				// trainer-telemetry label, same provenance convention as bot_x/npc_x/diag_join_key --
				// NEVER whitelisted into the observation vector (it's a label, not a percept; the
				// anti-memorization plan depends on the policy never being told which arena it's in).
				+ ",\"arena_id\":\"" + arena.id + "\""
				+ ",\"diag_join_key\":" + this.flushCounter
				// PERMANENT TRIPWIRE (EQUIPMENT-LOSS FIX pass, PROJECT_STATE.md section 13): the
				// live max melee hit, read the same way the real accuracy/damage rolls do
				// (DamageFormulas.calculateMaxMeleeHit(), not a cached/assumed constant). Step 2
				// found this exact value silently drop from 5 to 4 for the remainder of any run
				// after the bot's first death, invisible in every prior observation field -- this
				// makes any future equipment/bonus/stance perturbation visible in every per-step
				// log going forward, monitoring-only like bot_x/run_energy/distance/diag_join_key.
				+ ",\"max_melee_hit\":" + DamageFormulas.calculateMaxMeleeHit(this)
				// PERMANENT TRIPWIRE (NPC-RESPAWN BUG FIX pass, PROJECT_STATE.md section 13): live
				// count of World NPCs sharing target's id. Always expected to read 1 -- a ghost
				// slipping past performReset()'s cancel-and-sweep prevention (a residual timing race,
				// see that method's own comment) would show as 2+ here, visible in every per-step log
				// going forward, same monitoring-only convention as max_melee_hit.
				+ ",\"npc_instance_count\":" + countNpcInstances(target.getId())
				// THREAD 2a RESPACE (docs/PROJECT_STATE.md "THREAD 2a DESIGN" record): the flinch
				// sense's RAW counter -- emitted unnormalized; agent/elvarg_socket_env.py applies
				// clip01(t / 10.0) Python-side (this class's own SENSE_SATURATION_TICKS, matched by
				// value not by shared symbol -- see that field's own doc for why). Unconditional,
				// no opt-in flag, unlike wire_geometry/run_energy -- see PROTOCOL_VERSION's own
				// THREAD 2a doc for why this pass has no frozen-v4-caller surface left to protect.
				+ ",\"ticks_since_bot_landed_hit\":" + ticksSinceBotLandedHit
				+ "}";
	}

	/** Counts live World NPCs sharing the given id -- see the npc_instance_count tripwire's own doc. */
	private static int countNpcInstances(int npcId) {
		int count = 0;
		for (NPC other : World.getNpcs()) {
			if (other != null && other.getId() == npcId) {
				count++;
			}
		}
		return count;
	}

	/** Matches agent/observation.py's _clip01(x) exactly: max(0.0, min(1.0, x)). */
	private static double clip01(double x) {
		return Math.max(0.0, Math.min(1.0, x));
	}

	/**
	 * Parses the requested action from a raw client message. Returns "step" or "reset" for a
	 * recognized action, or null for anything else (missing/unrecognized "action" field,
	 * malformed JSON) - null routes to the existing "expected a step or reset action" error path.
	 */
	private String parseAction(String message) {
		try {
			final JsonObject json = JsonParser.parseString(message).getAsJsonObject();
			if (!json.has("action")) {
				return null;
			}
			final String action = json.get("action").getAsString();
			return ("step".equals(action) || "reset".equals(action)) ? action : null;
		} catch (Exception e) {
			logger.warning("[MinimalEnv] failed to parse action message: " + e);
			return null;
		}
	}

	/**
	 * Parses the combat-head index out of a step message's {@code body.action} array
	 * (index 1 of the [move, combat, prayer] triple - agent/actions.py's
	 * {@code get_action_space_nvec()} / {@code decode_action()} ordering). Prayer (index 2) is
	 * read by nothing yet - masked-inert (PROJECT_STATE.md section 13's ACTION-SPACE CONTRACT
	 * DECISION). Returns -1 (never a valid COMBAT_ACTIONS index, so it collapses to suppress) on
	 * any parse failure - a malformed or missing action array must never be silently treated as
	 * "attack".
	 */
	private int parseCombatActionIndex(String message) {
		try {
			final JsonObject json = JsonParser.parseString(message).getAsJsonObject();
			return json.getAsJsonObject("body").getAsJsonArray("action").get(1).getAsInt();
		} catch (Exception e) {
			logger.warning("[MinimalEnv] failed to parse combat action index, defaulting to suppress: " + e);
			return -1;
		}
	}

	/**
	 * Parses the move-head index out of a step message's {@code body.action} array (index 0 of
	 * the [move, combat, prayer] triple). Returns 0 (idle - never a valid direction, so
	 * applyMoveAction() no-ops) on any parse failure or out-of-range value - a malformed or
	 * missing action array must never be silently treated as a real direction.
	 */
	private int parseMoveActionIndex(String message) {
		try {
			final JsonObject json = JsonParser.parseString(message).getAsJsonObject();
			final int index = json.getAsJsonObject("body").getAsJsonArray("action").get(0).getAsInt();
			return (index >= 0 && index < MOVE_DELTAS.length) ? index : 0;
		} catch (Exception e) {
			logger.warning("[MinimalEnv] failed to parse move action index, defaulting to idle: " + e);
			return 0;
		}
	}

	/**
	 * THREAD 2a RESPACE (docs/PROJECT_STATE.md "THREAD 2a DESIGN" record): parses the OPTIONAL
	 * {@code arena_id} field from a reset message -- the trainer-controlled arena switch. Same
	 * independent-re-parse style as {@link #parseAction}/{@link #parseCombatActionIndex}/
	 * {@link #parseMoveActionIndex} above -- its own method, its own {@code JsonParser.parseString}
	 * call, no shared parsed object. Returns {@code null} if the key is absent (the no-switch,
	 * byte-identical-to-pre-2a default) or on any parse failure -- never silently misinterpret a
	 * malformed request as a switch to an unintended arena. A non-null return is NOT yet validated
	 * against the known arena set -- that's {@link ArenaDefinition#byId}'s own job, fail-loud.
	 */
	private String parseArenaId(String message) {
		try {
			final JsonObject json = JsonParser.parseString(message).getAsJsonObject();
			if (!json.has("arena_id")) {
				return null;
			}
			return json.get("arena_id").getAsString();
		} catch (Exception e) {
			logger.warning("[MinimalEnv] failed to parse arena_id, treating as absent (no switch): " + e);
			return null;
		}
	}

	/**
	 * Applies the move head (PROJECT_STATE.md section 13's move-head pass). Index 0 (idle) is a
	 * true no-op - it does not touch combat or movement state at all, so an idle move never
	 * disturbs an in-progress fight. A real direction (1-8) does two things, in order:
	 * <p>
	 * 1. INJECTION PATH - routes through the same hard-cancel a real click-to-walk packet uses
	 * (MovementPacketListener.execute(): {@code movementQueue.reset()} then
	 * {@code movementQueue.walkToReset()}, which internally calls {@code Combat.reset()} -
	 * PROJECT_STATE.md section 15 Group A's Q1/leaning recommendation, now locked in). This is
	 * deliberately NOT a direct write into the queued {@code points} bypassing the cancel (section
	 * 15 Group A: that would leave the auto-chase (Q2, {@code MovementQueue.processCombatFollowing()})
	 * fighting the injected move). Called even when combat_action == attack this same tick - see
	 * onPacketsProcessed()'s combat decode, which re-sets the target AFTER this cancel runs, so an
	 * attack survives a same-tick move (matches docs/PHASE2_DESIGN.md's "movement head owns the
	 * feet, attack still resolves if in range" intent) while a completed cancel with no attack
	 * chosen this tick doesn't autonomously resume.
	 * <p>
	 * 2. QUEUES the actual step(s): one tile for a walk, TWO tiles in the same direction for a run
	 * - matching sim/combat_env.py's own semantics (movement_tiles_this_tick() -> 2 when running,
	 * applied as a single dx*2,dy*2 displacement for one directional choice) rather than Elvarg's
	 * OWN native running model (which only consumes a second queued point per tick if a
	 * multi-tile path was already queued, e.g. from a distant click) - our per-tick single-
	 * direction action has no such multi-tile path to draw from, so without this explicit
	 * second-step queue, run mode would never produce 2-tile/tick displacement under this action
	 * granularity. RECORDED AS A DESIGN CHOICE, not a rediscovery of Elvarg's own mechanic - see
	 * the doc update from this pass for the full note. Each step is walkability-checked via
	 * RegionManager.canMove() before being queued (mirroring MovementQueue.canWalk()'s own
	 * pre-check idiom, since MovementQueue.process()'s own per-tick consumption only checks NPC
	 * occupancy via canWalkTo(), not terrain) - a blocked tile is logged and simply not queued,
	 * never silently teleported through.
	 * <p>
	 * RUN/WALK ACTION INCREMENT pass (PROJECT_STATE.md section 13): the run-step queueing
	 * condition also checks {@code getRunEnergy() > 0}, not {@code isRunning()} alone - matching
	 * {@code sim.entities.Player.movement_tiles_this_tick()}'s own {@code is_running AND
	 * run_energy > 0} check exactly. Needed because Elvarg's OWN native 0-energy auto-disable
	 * ({@code MovementQueue.drainRunEnergy()}) is REACTIVE (it only flips {@code isRunning} false
	 * as a side effect of a tick that just finished draining the last point) - it does not
	 * retroactively stop THIS tick's queueing decision if {@code isRunning} was freshly toggled
	 * true (via {@code toggle_run}, above) while energy was independently already at 0 from
	 * earlier drain. Without this extra check, that one specific sequence (toggle to run while at
	 * 0 energy) would still queue a 2-tile step for a single tick, contradicting Part 1's own
	 * audited real behavior ("0 energy = the step resolves as walk regardless of the requested
	 * speed"). Elvarg's own reactive auto-disable is otherwise sufficient and untouched.
	 */
	private void applyMoveAction(int moveActionIndex) {
		if (moveActionIndex <= 0) {
			return;
		}

		this.getMovementQueue().reset();
		this.getMovementQueue().walkToReset();

		final int dx = MOVE_DELTAS[moveActionIndex][0];
		final int dy = MOVE_DELTAS[moveActionIndex][1];
		final int size = this.size();
		final Location current = this.getLocation();
		final Location step1 = current.transform(dx, dy);
		if (!RegionManager.canMove(current, step1, size, size, this.getPrivateArea())) {
			logger.info("[MinimalEnv] move blocked by terrain: " + current + " -> " + step1);
			return;
		}
		this.getMovementQueue().walkStep(dx, dy);

		if (this.isRunning() && this.getRunEnergy() > 0) {
			final Location step2 = step1.transform(dx, dy);
			if (RegionManager.canMove(step1, step2, size, size, this.getPrivateArea())) {
				this.getMovementQueue().addStep(step2);
			} else {
				logger.info("[MinimalEnv] second run-step blocked by terrain: " + step1 + " -> " + step2);
			}
		}
	}

	/** Bounded wait: parks the game-tick thread until a message arrives or the time budget elapses. */
	private synchronized PendingStep waitForStep() {
		final long deadline = System.currentTimeMillis() + SESSION_WAIT_BUDGET_MS;
		while (this.pendingStep == null) {
			final long remaining = deadline - System.currentTimeMillis();
			if (remaining <= 0) {
				sessionActive = false;
				logger.warning("[MinimalEnv] session expired: no step received within "
						+ SESSION_WAIT_BUDGET_MS + "ms budget, resuming free tick");
				return null;
			}
			try {
				wait(remaining);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				return null;
			}
		}
		final PendingStep step = this.pendingStep;
		this.pendingStep = null;
		return step;
	}

	private void onPacketsFlushed(PlayerPacketsFlushedEvent event) {
		if (event.getPlayer() != this) {
			return;
		}
		flushCounter++;
		if (flushCounter % 10 == 0) {
			// Low-noise liveness heartbeat - confirms the tick loop is progressing (not wedged
			// by session gating) without logging every single tick.
			logger.info("[MinimalEnv] heartbeat: flush counter=" + flushCounter + ", sessionActive=" + sessionActive);
		}
		if (target != null) {
			// FLUSH read (post-resolution): by this point the target NPC's own NPC.process() has
			// already run this tick (it's a separate, later loop in World.process()), so any hit
			// queued during THIS tick's player-combat barrier (which runs AFTER that NPC-combat
			// barrier, post-flip - PROJECT_STATE.md section 8.2) is NOT yet applied to its HP -
			// it drains at the target's NEXT process() call, i.e. next tick's NPC-combat barrier.
			//
			// PENDING-DAMAGE READ restores section 8.2's outgoing-hit tripwire detector, moved
			// here from a since-removed INJECT-side read. The move-head pass (PROJECT_STATE.md
			// section 13) changed the attack path from a synchronous Combat.attack() call (which
			// let onPacketsProcessed() read target.getHitQueue().getAccumulatedDamage() itself,
			// immediately after the roll) to a deferred Combat.setTarget() that resolves later,
			// autonomously, inside getCombat().process() - our own code no longer has a
			// synchronous moment right after the roll to read from. getAccumulatedDamage() sums
			// damage that has been ROLLED but not yet APPLIED to hitpoints (HitQueue.java) - since
			// a hit queued this tick can't drain until the target's NEXT process() call, reading
			// it HERE, at flush (this tick, after the target's own drain point has already
			// passed), is equivalent evidence to the old INJECT read: nonzero means "a hit
			// resolved this tick, still pending" (expected, k=1); the section 8.2 ANOMALY is an
			// outgoing hit whose HP effect appears the SAME tick it was queued, or an incoming hit
			// (already same-tick, section 8.2's Field-level confirmation) whose effect is delayed
			// to next tick - either would show as an inconsistency between this pending-damage
			// read and the npc_hp trace across consecutive FLUSH lines.
			final int npcHp = target.getHitpoints();
			final int npcPendingDamage = target.getCombat().getHitQueue().getAccumulatedDamage();
			final int playerHp = this.getHitpoints();
			final int distance = this.getLocation().getDistance(target.getLocation());
			logger.info("[MinimalEnv] FLUSH read (post-resolution) #" + flushCounter + ": npc_hp=" + npcHp
					+ " npc_pending_damage=" + npcPendingDamage + " player_hp=" + playerHp
					+ " distance=" + distance);
		}
		while (!onFlushTasks.isEmpty()) {
			onFlushTasks.poll().run();
		}
	}

	private record PendingStep(String message, CompletableFuture<String> future) {
	}
}
