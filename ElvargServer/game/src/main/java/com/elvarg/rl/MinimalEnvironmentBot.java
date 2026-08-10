package com.elvarg.rl;

import com.elvarg.game.World;
import com.elvarg.game.collision.RegionManager;
import com.elvarg.game.content.combat.FightType;
import com.elvarg.game.definition.PlayerBotDefinition;
import com.elvarg.game.entity.impl.npc.NPC;
import com.elvarg.game.entity.impl.npc.NpcAggression;
import com.elvarg.game.entity.impl.playerbot.PlayerBot;
import com.elvarg.game.entity.impl.playerbot.interaction.CombatInteraction;
import com.elvarg.game.entity.impl.playerbot.interaction.MovementInteraction;
import com.elvarg.game.event.EventDispatcher;
import com.elvarg.game.event.events.PlayerPacketsFlushedEvent;
import com.elvarg.game.event.events.PlayerPacketsProcessedEvent;
import com.elvarg.game.model.Location;
import com.elvarg.game.model.Skill;
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
	 * The controlled arena's known tiles (PROJECT_STATE.md section 8.1). Deliberately duplicated
	 * here rather than shared with Server.java's/withMeleeLoadout()'s own literals of the same
	 * coordinates - this is one protocol slice (the reset verb), not a location-constant refactor.
	 */
	private static final Location ARENA_BOT_LOCATION = new Location(3089, 3466);
	private static final Location ARENA_NPC_LOCATION = new Location(3090, 3466);

	/**
	 * Index of "attack" within agent/actions.py's COMBAT_ACTIONS list on the Python side:
	 * {@code ["nothing", "attack", "eat_food", "drink_prayer_pot", "drink_combat_pot",
	 * "drink_defence_pot", "toggle_run"]} - "attack" is COMBAT_ACTIONS[1]. Hardcoded here rather
	 * than read from a shared source (no cross-language import path exists) - if that list's
	 * ordering ever changes, this drifts silently. Every other combat-head value currently
	 * collapses to suppress (see the step branch below).
	 */
	private static final int COMBAT_ACTION_ATTACK_INDEX = 1;

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
	 * No-op interactions, ported from the naton1-reference's NoOpCombatInteraction /
	 * NoOpMovementInteraction (com.github.naton1.rl.util). Without these, PlayerBot's inherited
	 * default AI (PlayerBot.process() -> combatInteraction.process(), and
	 * Player.process() -> ((PlayerBot) this).getMovementInteraction().process()) auto-retaliates
	 * against attackers and wanders autonomously, entirely independent of our RL action.
	 */
	private final CombatInteraction noOpCombatInteraction;
	private final MovementInteraction noOpMovementInteraction;

	public MinimalEnvironmentBot(PlayerBotDefinition definition) {
		// Replace the incoming definition's FighterPreset with our own bespoke melee loadout
		// (MinimalMeleeFighterPreset) - username and spawn location are preserved from
		// `definition` (PLAYER_BOTS[0]), only the gear/stats/combat-actions change. This is the
		// same fix for PlayerBot's inherited auto-retaliate as before (stock PlayerBot.process()
		// calls the private field `combatInteraction.process()` directly, not the overridable
		// getCombatInteraction() getter, so CombatInteraction.process()'s attack-execution loop
		// must be neutered via an empty FighterPreset.getCombatActions() array - which
		// MinimalMeleeFighterPreset already returns), just wired through our own preset instead
		// of wrapping ObbyMauler's.
		super(withMeleeLoadout(definition));
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

	private static PlayerBotDefinition withMeleeLoadout(PlayerBotDefinition definition) {
		// Override the spawn location instead of preserving definition.getSpawnLocation()
		// (PLAYER_BOTS[0]'s (3085, 3528), inside Wilderness level 2 - confirmed via
		// WildernessArea's Boundary(2940, 3392, 3525, 3968)). (3089, 3466) is verified
		// non-wilderness, non-multi, walkable (live RegionManager.blocked() check), and >55
		// tiles from the nearest default NPC spawn. Entirely within com.elvarg.rl - GameConstants
		// (PLAYER_BOTS[0]'s own constant, shared with the stock login-triggered bot-spawn loop)
		// is left untouched.
		return new PlayerBotDefinition(definition.getUsername(), new Location(3089, 3466),
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

		// NPC-AGGRESSION FIX (PROJECT_STATE.md section 13's NPC AGGRESSION pass) - source-audited
		// root cause, not a data flag: Hobgoblin 3049's npc_defs.json already has
		// "aggressive": true and "combatFollowDistance": 7 (both already OSRS-faithful, confirmed
		// against the Wiki - no data fix needed here). The ACTUAL blocker is
		// NpcAggression.runAggression()'s tolerance gate: `npcDefinition.buildsAggressionTolerance()
		// && player.getAggressionTolerance().finished()` - and SecondsTimer.finished() returns TRUE
		// for a never-started timer (its `seconds` field defaults to 0, so secondsRemaining() is
		// immediately 0). The ONLY call site that ever starts this timer,
		// RegionChangePacketListener.java:24, fires from a CLIENT-SENT region-change packet - which
		// this headless bot, having no real client, never sends. Net effect: the tolerance timer
		// sat permanently in its "already elapsed" default state, so the tolerance gate fired on
		// EVERY tick from login onward, silently blocking aggression regardless of the (already
		// correct) aggressive/combatFollowDistance data. Calling ONLY the one relevant line here
		// (not the whole packet listener, which also does client-rendering-only work like
		// deleteRegionalSpawns()/onRegionChange() hooks that don't apply to a bot with no client)
		// mirrors exactly what a real client's first region load does - the real 10-minute
		// (NpcAggression.NPC_TOLERANCE_SECONDS) tolerance window still applies faithfully afterward,
		// it is simply now correctly INITIALIZED instead of defaulting to "already tolerant".
		// Started ONCE here (onLogin(), not performReset()) - restarting it every episode would be
		// the unfaithful choice (a real player teleporting back to the same tile doesn't get a
		// fresh tolerance timer; the Wiki's own reset condition is leaving the tolerance region
		// entirely, which this arena's fixed single tile never does).
		this.getAggressionTolerance().start(NpcAggression.NPC_TOLERANCE_SECONDS);
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

		if ("reset".equals(action)) {
			// Runs entirely on the tick thread, same as the attack below - see performReset()'s
			// own doc for the full reset design (PROJECT_STATE.md section 13).
			resetErrorPayload = performReset();
		} else if ("step".equals(action)) {
			// MOVE HEAD (index 0) applied FIRST, before the combat decode below - this ordering IS
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
				final int combatActionIndex = parseCombatActionIndex(step.message());
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
					// open question 3) - every other combat-head value currently collapses here,
					// including the not-yet-wired consumable actions (eat_food/drink_*/
					// toggle_run pending their own slice), not just "nothing". Prayer head values
					// are ignored entirely (masked-inert, section 13's ACTION-SPACE CONTRACT
					// DECISION).
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
				logger.info("[MinimalEnv] step received, no attack applied (no target)");
			}
		} else {
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
	private String performReset() {
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

			this.getCombat().reset();
			this.getCombat().getHitQueue().clear();
			// Same leak-prevention rationale as HitQueue.clear() above, now that the move head can
			// queue steps: a leftover queued waypoint from just before this reset (should not
			// normally happen under the one-action-per-tick blocking protocol, but cheap to guard)
			// would otherwise survive the teleport below and walk the freshly-reset bot off its
			// arena tile on the very next tick.
			this.getMovementQueue().reset();
			this.moveTo(ARENA_BOT_LOCATION);
			this.setHitpoints(this.getSkillManager().getMaxLevel(Skill.HITPOINTS));
			// PROJECT_STATE.md section 13's flagged non-stationarity fix: run energy was previously
			// left untouched by reset (only HP/position/combat state were restored), so it drained
			// monotonically across an episode's running use and never recovered at episode boundaries
			// - a hidden moving target for a policy whose combat head actively reaches for run state
			// (toggle_run was 43.8% of the frozen policy's choices in the Stage-1 eval). Same
			// full-restore value (100) as the existing admin-restore precedent (Player.java's
			// setRunEnergy(100) call in its own full-restore path) - not a new/guessed constant.
			this.setRunEnergy(100);

			if (target.getHitpoints() <= 0 || target.isDying()) {
				// Dead or mid-death-task: NPCDeathTask.stop() removes the old object from the
				// world regardless of any HP we set on it (World.getRemoveNPCQueue()), so don't
				// try to revive it - spawn fresh, the same two-call pattern Server.java used at
				// boot, and REPOINT target. Dropping this repoint reproduces the exact
				// stale-dead-reference bug this branch exists to avoid.
				final NPC freshNpc = NPC.create(target.getId(), ARENA_NPC_LOCATION);
				World.getAddNPCQueue().add(freshNpc);
				this.target = freshNpc;
				logger.info("[MinimalEnv] reset: NPC was dead/dying, spawned fresh instance and repointed target");
			} else {
				target.getCombat().reset();
				target.getCombat().getHitQueue().clear();
				target.moveTo(ARENA_NPC_LOCATION);
				target.setHitpoints(target.getCurrentDefinition().getHitpoints());
				logger.info("[MinimalEnv] reset: NPC healed and repositioned in place");
			}

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
	 * enemy_in_my_attack_range, enemy_can_reach_me, dx/dy signs) and enemy_attack_imminent_* are
	 * deliberately NOT wired here - still zero-filled Python-side, per the section 13 scoping for
	 * this experiment.
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

		return "{\"hp_fraction\":" + hpFraction
				+ ",\"enemy_hp_fraction\":" + enemyHpFraction
				+ ",\"enemy_attack_style_melee\":" + enemyAttackStyleMelee
				+ ",\"enemy_max_hit_normalized\":" + enemyMaxHitNormalized
				+ ",\"enemy_attack_speed\":" + enemyAttackSpeed
				+ ",\"bot_x\":" + this.getLocation().getX()
				+ ",\"bot_y\":" + this.getLocation().getY()
				+ ",\"run_energy\":" + this.getRunEnergy()
				+ "}";
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

		if (this.isRunning()) {
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
