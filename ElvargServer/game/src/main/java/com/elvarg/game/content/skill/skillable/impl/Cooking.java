package com.elvarg.game.content.skill.skillable.impl;

import com.elvarg.game.definition.ItemDefinition;
import com.elvarg.game.entity.impl.object.GameObject;
import com.elvarg.game.entity.impl.object.ObjectManager;
import com.elvarg.game.entity.impl.player.Player;
import com.elvarg.game.model.*;
import com.elvarg.util.Misc;
import com.elvarg.util.ObjectIdentifiers;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Represents the Cooking skill.
 *
 * @author Professor Oak
 */
public class Cooking extends ItemCreationSkillable {

    /**
     * The animation the player will perform
     */
    protected static final Animation ANIMATION = new Animation(896);

    /**
     * The object we're cooking on.
     */
    private final GameObject object;

    /**
     * The {@link Cookable} we're going to cook.
     */
    private final Cookable cookable;

    public Cooking(GameObject object, Cookable cookable, int amount) {
        super(Arrays.asList(new RequiredItem(new Item(cookable.getRawItem()), true)),
                new Item(cookable.getCookedItem()), amount, Optional.of(new AnimationLoop(ANIMATION, 4)),
                cookable.getLevelReq(), cookable.getXp(), Skill.COOKING);
        this.object = object;
        this.cookable = cookable;
    }

    /**
     * Probability of cooking (not burning) food, from the OSRS formula (OSRS Wiki "Skilling success chart"; Fire or Range
     * columns): P = min(1, ((low * (99 - L) + high * (L - 1)) / 98 + 1) / 256). Replaces Elvarg's linear burn model.
     */
    public static double successChance(int cookingLevel, int low, int high) {
        double p = ((low * (99.0 - cookingLevel) + high * (cookingLevel - 1.0)) / 98.0 + 1.0) / 256.0;
        return Math.min(1.0, p);
    }

    public static boolean success(Player player, Cookable cookable) {
        return Misc.getRandomDouble() < successChance(player.getSkillManager().getCurrentLevel(Skill.COOKING), cookable.getLow(), cookable.getHigh());
    }

    // Override finishedCycle because we don't always add the cooked item.
    // Sometimes we burn food.
    @Override
    public void finishedCycle(Player player) {
        // Decrement amount left to cook..
        decrementAmount();

        // Delete raw food..
        player.getInventory().delete(cookable.getRawItem(), 1);

        // Add burnt or cooked item..
        if (success(player, cookable)) {
            player.getInventory().add(cookable.getCookedItem(), 1);
            player.getPacketSender()
                    .sendMessage("You cook the " + ItemDefinition.forId(cookable.getRawItem()).getName() + ".");
            player.getSkillManager().addExperience(Skill.COOKING, cookable.getXp());
        } else {
            player.getInventory().add(cookable.getBurntItem(), 1);
            player.getPacketSender()
                    .sendMessage("You burn the " + ItemDefinition.forId(cookable.getRawItem()).getName() + ".");
        }
    }

    @Override
    public boolean hasRequirements(Player player) {
        // If we're using a fire, make sure to check it's still there.
        if (object.getId() == ObjectIdentifiers.FIRE_5
                && !ObjectManager.exists(ObjectIdentifiers.FIRE_5, object.getLocation())) {
            return false;
        }

        return super.hasRequirements(player);
    }

    /**
     * Data for the cooking skill.
     */
    public static enum Cookable {
        // (raw, cooked, burnt, level, xp, stopBurn, low, high, name). low/high/level/xp from the OSRS Wiki item pages (Fire or Range).
        SHRIMP(317, 315, 7954, 1, 30, 33, 128, 512, "shrimp"),
        CHICKEN(2138, 2140, 2144, 1, 30, 34, 128, 512, "chicken"),
        ANCHOVIES(321, 319, 323, 1, 30, 34, 128, 512, "anchovies"),
        TROUT(335, 333, 343, 15, 70, 50, 88, 432, "trout"),
        COD(341, 339, 343, 18, 75, 54, 83, 422, "cod"),
        SALMON(331, 329, 343, 25, 90, 58, 68, 392, "salmon"),
        TUNA(359, 361, 367, 30, 100, 58, 58, 372, "tuna"),
        LOBSTER(377, 379, 381, 40, 120, 74, 38, 332, "lobster"),
        BASS(363, 365, 367, 43, 130, 75, 33, 312, "bass"),
        SWORDFISH(371, 373, 375, 45, 140, 86, 18, 292, "swordfish"),
        MONKFISH(7944, 7946, 7948, 62, 150, 91, 11, 275, "monkfish"),
        SHARK(383, 385, 387, 80, 210, 94, 1, 202, "shark"),
        SEA_TURTLE(395, 397, 399, 82, 212, 105, 1, 202, "sea turtle"),
        MANTA_RAY(389, 391, 393, 91, 217, 99, 1, 202, "manta ray"),;

        private static final Map<Integer, Cookable> cookables = new HashMap<Integer, Cookable>();

        static {
            for (Cookable c : Cookable.values()) {
                cookables.put(c.getRawItem(), c);
                cookables.put(c.getCookedItem(), c);
            }
        }

        int rawItem, cookedItem, burntItem, levelReq, xp, stopBurn, low, high;
        String name;

        Cookable(int rawItem, int cookedItem, int burntItem, int levelReq, int xp, int stopBurn, int low, int high, String name) {
            this.rawItem = rawItem;
            this.cookedItem = cookedItem;
            this.burntItem = burntItem;
            this.levelReq = levelReq;
            this.xp = xp;
            this.stopBurn = stopBurn;
            this.low = low;
            this.high = high;
            this.name = name;
        }

        public static Optional<Cookable> getForItem(int item) {
        	return Optional.ofNullable(cookables.get(item));
        }

        public int getRawItem() {
            return rawItem;
        }

        public int getCookedItem() {
            return cookedItem;
        }

        public int getBurntItem() {
            return burntItem;
        }

        public int getLevelReq() {
            return levelReq;
        }

        public int getXp() {
            return xp;
        }

        public int getLow() {
            return low;
        }

        public int getHigh() {
            return high;
        }

        public int getStopBurn() {
            return stopBurn;
        }

        public String getName() {
            return name;
        }
    }
}
