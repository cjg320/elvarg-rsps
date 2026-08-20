package com.elvarg.util.timers;

import com.elvarg.util.Misc;

public enum TimerKey {
	FOOD,
	KARAMBWAN,
	POTION,
	COMBAT_ATTACK,
	FREEZE,
	FREEZE_IMMUNITY,
	STUN,
	STUN_IMMUNITY,
	ATTACK_IMMUNITY,
	CASTLEWARS_TAKE_ITEM,
	STEPPING_OUT,
	BOT_WAIT_FOR_PLAYERS(Misc.getTicks(180 /* 3 minutes */)),
	STAT_CHANGE,
	// FLINCH FIDELITY COMPLETION pass (docs/PROJECT_STATE.md): the Wiki's 8-tick "in-combat" timer
	// (Flinching page) -- see Combat.java's own FLINCH_IN_COMBAT_TICKS for the tick count, registered
	// explicitly per-use like COMBAT_ATTACK, not fixed here.
	FLINCH_IN_COMBAT;

	private int ticks;

	TimerKey() {
	}

	TimerKey(int ticks) {
		this.ticks = ticks;
	}

	public int getTicks() {
		return this.ticks;
	}
}
