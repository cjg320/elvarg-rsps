package com.elvarg.rl;

import com.elvarg.game.definition.PlayerBotDefinition;
import com.elvarg.game.entity.impl.playerbot.PlayerBot;
import com.elvarg.game.event.EventDispatcher;
import com.elvarg.game.event.events.PlayerPacketsFlushedEvent;
import com.elvarg.game.event.events.PlayerPacketsProcessedEvent;
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
 * payload (a flush-bound counter) is built INSIDE that Runnable, at flush-drain time, so the
 * value returned to the client is only ever read after the tick genuinely advanced.
 */
public class MinimalEnvironmentBot extends PlayerBot {

	private static final Logger logger = Logger.getLogger(MinimalEnvironmentBot.class.getSimpleName());

	/** Total time the tick thread will wait for a step before expiring the session and letting the tick proceed freely. */
	private static final long SESSION_WAIT_BUDGET_MS = 10_000;

	/** Single-bot static reference for this minimal proof - no multi-client login/routing yet. */
	private static volatile MinimalEnvironmentBot instance;

	private volatile long flushCounter = 0;

	/** Flips true on the first step message received; flips false again if the safety-net wait expires. */
	private volatile boolean sessionActive = false;

	private volatile PendingStep pendingStep;

	/** Only ever touched from the single game-tick thread (queued in the processed handler, drained in the flushed handler). */
	private final Queue<Runnable> onFlushTasks = new LinkedList<>();

	public MinimalEnvironmentBot(PlayerBotDefinition definition) {
		super(definition);
		instance = this;
		EventDispatcher.getGlobal().add(PlayerPacketsProcessedEvent.class, this::onPacketsProcessed);
		EventDispatcher.getGlobal().add(PlayerPacketsFlushedEvent.class, this::onPacketsFlushed);
		logger.info("[MinimalEnv] bot constructed: " + definition.getUsername());
	}

	public static MinimalEnvironmentBot getInstance() {
		return instance;
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

		final boolean validStep = isStepAction(step.message());
		logger.info("[MinimalEnv] step received (valid=" + validStep + "), applying no-op action");

		// Defer resolution to flush-drain time - do NOT complete the future here.
		onFlushTasks.add(() -> {
			final String payload = validStep
					? "{\"counter\":" + flushCounter + "}"
					: "{\"error\":\"expected a step action\"}";
			logger.info("[MinimalEnv] resolving step future at flush-drain, counter=" + flushCounter);
			step.future().complete(payload);
		});
	}

	private boolean isStepAction(String message) {
		try {
			final JsonObject json = JsonParser.parseString(message).getAsJsonObject();
			return json.has("action") && "step".equals(json.get("action").getAsString());
		} catch (Exception e) {
			logger.warning("[MinimalEnv] failed to parse step message: " + e);
			return false;
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
		while (!onFlushTasks.isEmpty()) {
			onFlushTasks.poll().run();
		}
	}

	private record PendingStep(String message, CompletableFuture<String> future) {
	}
}
