package com.elvarg.game;

import com.elvarg.game.content.clan.ClanChatManager;
import com.google.common.util.concurrent.ThreadFactoryBuilder;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * The engine which processes the game.
 *
 * @author Professor Oak
 */
public final class GameEngine implements Runnable {

    /**
     * The {@link ScheduledExecutorService} which will be used for
     * this engine.
     */
    private final ScheduledExecutorService executorService = Executors.newSingleThreadScheduledExecutor(new ThreadFactoryBuilder().setNameFormat("GameThread").build());

    /**
     * The scheduler's real invocation period, in milliseconds - overridable via the TICK_RATE
     * environment variable (mirrors naton1-reference's GameEngine.java, same pattern, same
     * default), falling back to the stock {@link GameConstants#GAME_ENGINE_PROCESSING_CYCLE_RATE}
     * (600) when unset. Deliberately a SEPARATE symbol from that constant, not a replacement for
     * it: GAME_ENGINE_PROCESSING_CYCLE_RATE remains the tick-COUNTING basis elsewhere (see
     * Misc.java / PrayerHandler.java's human-duration-to-tick-count conversions) and must stay
     * 600 for those to remain correct - only the scheduler's real-time PERIOD is overridden here,
     * never the counting constant. Safe because game mechanics are tick-counted, not wall-clock-
     * timed (PROJECT_STATE.md's PERF INVESTIGATION, Part 4).
     */
    private static final int TICK_RATE = System.getenv().containsKey("TICK_RATE")
            ? Integer.parseInt(System.getenv("TICK_RATE"))
            : GameConstants.GAME_ENGINE_PROCESSING_CYCLE_RATE;

    /**
     * Initializes this {@link GameEngine}.
     */
    public void init() {
        executorService.scheduleAtFixedRate(this, 0, TICK_RATE, TimeUnit.MILLISECONDS);
    }

    @Override
    public void run() {
        try {
            World.process();
        } catch (Throwable e) {
            e.printStackTrace();
            World.savePlayers();
            ClanChatManager.save();
        }
    }
}