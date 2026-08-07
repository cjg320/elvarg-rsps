package com.elvarg.rl;

import com.elvarg.game.entity.impl.playerbot.PlayerBot;
import com.elvarg.game.entity.impl.playerbot.interaction.MovementInteraction;

/**
 * Ported from the naton1-reference's NoOpMovementInteraction (com.github.naton1.rl.util). Strips
 * PlayerBot's inherited default autonomous movement - Player.process() calls
 * getMovementInteraction().process() every tick for any PlayerBot, which would otherwise drive
 * unrelated wandering and could move the bot off the tile we deliberately placed it on.
 */
public class NoOpMovementInteraction extends MovementInteraction {

	public NoOpMovementInteraction(PlayerBot playerBot) {
		super(playerBot);
	}

	@Override
	public void process() {
	}
}
