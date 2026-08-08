package com.elvarg.rl;

import com.elvarg.game.World;
import com.elvarg.game.content.combat.FightType;
import com.elvarg.game.definition.PlayerBotDefinition;
import com.elvarg.game.entity.impl.npc.NPC;
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
			// The fixed action for this slice: attack the target. This is where the hit's damage is
			// synchronously ROLLED (accuracy + damage roll happen inside PendingHit's constructor,
			// called from Combat.attack() -> performNewAttack()) and QUEUED onto the target NPC's own
			// HitQueue - but NOT yet applied to its hitpoints. That only happens later this same tick,
			// when the target's own NPC.process() runs (a separate, later loop in World.process()).
			if (target != null) {
				this.getCombat().attack(target);
				// Read back what got queued for the target, right after attack() and before its own
				// NPC.process() has run this tick: 0 if attack() didn't roll a new hit this tick
				// (still on cooldown), otherwise the just-rolled damage sitting in its HitQueue.
				final int rolledDamage = target.getCombat().getHitQueue().getAccumulatedDamage();
				final int injectNpcHp = target.getHitpoints();
				logger.info("[MinimalEnv] INJECT read (right after attack applied, pre-resolution): npc_hp="
						+ injectNpcHp + " rolled_damage=" + rolledDamage);
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
			this.moveTo(ARENA_BOT_LOCATION);
			this.setHitpoints(this.getSkillManager().getMaxLevel(Skill.HITPOINTS));

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
		return "{\"hp_fraction\":" + hpFraction + ",\"enemy_hp_fraction\":" + enemyHpFraction + "}";
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
			// queued during this tick's onPacketsProcessed should already be applied to its HP.
			// This is the direct counterpart to the INJECT read logged in onPacketsProcessed -
			// comparing the two per-tick is what makes deferred-resolution observable.
			final int npcHp = target.getHitpoints();
			final int playerHp = this.getHitpoints();
			final int distance = this.getLocation().getDistance(target.getLocation());
			logger.info("[MinimalEnv] FLUSH read (post-resolution) #" + flushCounter + ": npc_hp=" + npcHp
					+ " player_hp=" + playerHp + " distance=" + distance);
		}
		while (!onFlushTasks.isEmpty()) {
			onFlushTasks.poll().run();
		}
	}

	private record PendingStep(String message, CompletableFuture<String> future) {
	}
}
