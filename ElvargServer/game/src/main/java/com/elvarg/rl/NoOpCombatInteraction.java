package com.elvarg.rl;

import com.elvarg.game.entity.impl.Mobile;
import com.elvarg.game.entity.impl.player.Player;
import com.elvarg.game.entity.impl.playerbot.PlayerBot;
import com.elvarg.game.entity.impl.playerbot.interaction.CombatInteraction;

import java.util.Optional;

/**
 * Ported from the naton1-reference's NoOpCombatInteraction (com.github.naton1.rl.util). Strips
 * PlayerBot's inherited default combat AI - without this, PlayerBot.process() calls
 * combatInteraction.process() every tick, which auto-retaliates against whoever is attacking the
 * bot (getCombat().getAttacker()), fighting an aggressive NPC entirely independent of any RL
 * action. Every method is a no-op so the bot only ever acts on our own explicit attack() calls.
 */
public class NoOpCombatInteraction extends CombatInteraction {

	public NoOpCombatInteraction(PlayerBot playerBot) {
		super(playerBot);
	}

	@Override
	public void process() {
	}

	@Override
	public void takenDamage(int damage, Mobile attacker) {
	}

	@Override
	public void handleDying(Optional<Player> killer) {
	}

	@Override
	public void handleDeath(Optional<Player> killer) {
	}

	@Override
	public void targetAssigned(Player target) {
	}

	@Override
	public void reset() {
	}
}
