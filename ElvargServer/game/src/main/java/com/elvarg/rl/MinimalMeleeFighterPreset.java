package com.elvarg.rl;

import com.elvarg.game.content.presets.Presetable;
import com.elvarg.game.entity.impl.playerbot.fightstyle.CombatAction;
import com.elvarg.game.entity.impl.playerbot.fightstyle.FighterPreset;
import com.elvarg.game.model.Item;
import com.elvarg.game.model.MagicSpellbook;

import static com.elvarg.util.ItemIdentifiers.MITHRIL_SCIMITAR;

/**
 * Bespoke melee-only loadout for MinimalEnvironmentBot - authored here, not inherited from a
 * PvP preset (ObbyMauler/DDSPure), so every stat is ours to control and edit.
 * <p>
 * Tuned to match the Python sim's combat target (sim-final-v1,
 * agent/combat_gym_env.py's _DEFAULT_PLAYER_KWARGS): base levels attack=32/strength=32/
 * defence=27/hitpoints=32, Accurate stance (+3 attack/+0 strength - MinimalEnvironmentBot
 * forces FightType.SCIMITAR_CHOP in its onLogin() override, since WeaponInterfaces.assign()
 * would otherwise default a scimitar to the Aggressive option), and melee equipment bonuses
 * attack_bonus=20/strength_bonus=20/defence_bonus=0.
 * <p>
 * Mithril scimitar (id 1329) worn alone, bare otherwise (no other equipped armor - keeps
 * defence bonuses at their real minimum instead of summing several pieces toward a target), is
 * the closest real Elvarg item match - checked directly against
 * data/definitions/items.json, not assumed from OSRS lore (Elvarg's own item data isn't
 * reliably curated - see the Phase C fidelity audit, section 9): bonuses =
 * [stab 5, slash 21, crush -2, magic 0, range 0, def_stab 0, def_slash 1, def_crush 0,
 * def_magic 0, def_range 0, strength 20, ranged_str 0, magic_str 0, prayer 0]. Slash attack
 * bonus is 21 vs the sim's 20 (off by one), strength bonus is an exact 20 match, defence
 * bonuses are 0/1/0 vs the sim's flat 0 - the closest achievable without fabricating item data.
 * Also confirmed NOT present in RangedData's ranged-weapon map, so
 * CombatFactory.getMethod() falls through to MELEE_COMBAT (melee PendingHits carry delay=0,
 * unlike the ranged delay that produced the one-tick lag investigated earlier).
 */
public class MinimalMeleeFighterPreset implements FighterPreset {

	private static final Presetable PRESETABLE = new Presetable("Minimal Melee",
			new Item[0],
			new Item[]{new Item(MITHRIL_SCIMITAR)},
			/* atk, def, str, hp, range, pray, mage */
			new int[]{32, 27, 32, 32, 1, 1, 1},
			MagicSpellbook.NORMAL,
			true
	);

	@Override
	public Presetable getItemPreset() {
		return PRESETABLE;
	}

	@Override
	public CombatAction[] getCombatActions() {
		return new CombatAction[0];
	}
}
