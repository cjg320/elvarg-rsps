package com.elvarg.game.model.container.shop;

import com.elvarg.game.definition.ItemDefinition;
import com.elvarg.game.definition.ShopDefinition;
import com.elvarg.game.entity.impl.player.Player;
import com.elvarg.game.model.Item;
import com.elvarg.game.task.Task;
import com.elvarg.game.task.TaskManager;
import com.elvarg.util.ItemIdentifiers;

/**
 * Real-game (OSRS) shop rules for shops loaded from shops_osrs.json (source: OSRS Wiki "Shop" page and each shop's StoreTableHead;
 * tools/build_content_data.py). Pricing: a unit costs floor(value * (sellMultiplier - delta * (stock - startStock)) / 1000); the shop pays
 * floor(value * (buyMultiplier - delta * (stock - startStock)) / 1000) but never less than 10% of value (wiki worked example: a steel
 * axe, value 200, at Bob's pays 120 at stock 3 and 116 at stock 4; costs 200 at stock 3 and 196 at stock 4). A specialty shop only buys
 * items from its starting stock; a general store buys any sellable item. Each unit is priced at the stock level it is traded at.
 * Stock of a starting item moves one unit back toward its start every `restock` ticks; surplus items bought by a general store that
 * are not in its starting stock decay by one every EXTRA_DECAY_TICKS (period not given on the wiki; labelled approximation).
 */
public final class FaithfulShop {

    private static final int EXTRA_DECAY_TICKS = 100;

    private FaithfulShop() {
    }

    /**
     * Shopkeeper NPC id -> shop id. Elvarg's own NPC ids are used (found by name in npc_defs.json; Elvarg's numbering differs from the wiki's
     * modern ids): Shop keeper/assistant 506/507 Lumbridge General Store, 508/509 Varrock Swordshop, Horvik 535, Bob 505, Lowe 536.
     */
    public static int shopForNpc(int npcId) {
        switch (npcId) {
            case 506:
            case 507:
                return 20;
            case 508:
            case 509:
                return 21;
            case 535:
                return 22;
            case 505:
                return 23;
            case 536:
                return 24;
            default:
                return -1;
        }
    }

    /** Right-click options a human sees on this NPC (wiki infobox: "Talk-to, Trade"); empty for other NPCs. */
    public static String npcOptions(int npcId) {
        return shopForNpc(npcId) >= 0 ? "Talk-to,Trade" : "";
    }

    public static boolean is(Shop shop) {
        return shop != null && shop.faithfulDef != null;
    }

    private static Item slotItem(Shop shop, int itemId) {
        for (Item it : shop.getCurrentStock()) {
            if (it != null && it.getId() == itemId) {
                return it;
            }
        }
        return null;
    }

    public static int stock(Shop shop, int itemId) {
        Item it = slotItem(shop, itemId);
        return it == null ? 0 : it.getAmount();
    }

    private static int start(Shop shop, int itemId) {
        return shop.getAmount(itemId, true);
    }

    private static boolean stocked(Shop shop, int itemId) {
        for (Item it : shop.getOriginalStock()) {
            if (it.getId() == itemId) {
                return true;
            }
        }
        return false;
    }

    /** Coins the player pays for one unit at the current stock level. */
    public static int buyPrice(Shop shop, int itemId) {
        ShopDefinition d = shop.faithfulDef;
        long value = ItemDefinition.forId(itemId).getValue();
        long mult = d.getSellMultiplier() - (long) d.getDelta() * (stock(shop, itemId) - start(shop, itemId));
        // Wiki "General store": buying from a general store starts at 127% with 1 in stock and bottoms out at 30% of value (34 in stock).
        // No floor is documented for specialty shops (only reachable by a huge overstock), so none is applied there.
        if (d.isGeneral()) {
            mult = Math.max(300, mult);
        }
        return (int) Math.max(1, value * mult / 1000);
    }

    /** Coins the shop pays for one unit at the current stock level (minimum 10% of value). */
    public static int sellPrice(Shop shop, int itemId) {
        ShopDefinition d = shop.faithfulDef;
        long value = ItemDefinition.forId(itemId).getValue();
        long mult = d.getBuyMultiplier() - (long) d.getDelta() * (stock(shop, itemId) - start(shop, itemId));
        return (int) Math.max(value / 10, value * mult / 1000);
    }

    /** True if this shop accepts the item from a player (wiki: specialty shops buy only what they stock; a general store buys any sellable item). */
    public static boolean buys(Shop shop, int itemId) {
        ItemDefinition def = ItemDefinition.forId(itemId);
        if (def == null || def.isNoted() || !def.isSellable() || itemId == ItemIdentifiers.COINS) {
            return false;
        }
        return shop.faithfulDef.isGeneral() || stocked(shop, itemId);
    }

    public static void priceCheck(Player player, Shop shop, int itemId, boolean shopItem) {
        ItemDefinition def = ItemDefinition.forId(itemId);
        if (shopItem) {
            player.getPacketSender().sendMessage(def.getName() + ": currently costs " + buyPrice(shop, itemId) + " coins.");
        } else if (!buys(shop, itemId)) {
            player.getPacketSender().sendMessage("You can't sell this item to this shop.");
        } else {
            player.getPacketSender().sendMessage(def.getName() + ": shop will buy for " + sellPrice(shop, itemId) + " coins.");
        }
    }

    public static void buy(Player player, Shop shop, int slot, int itemId, int amount) {
        if (slot >= shop.getCurrentStock().length || shop.getCurrentStock()[slot] == null || shop.getCurrentStock()[slot].getId() != itemId) {
            return;
        }
        ItemDefinition def = ItemDefinition.forId(itemId);
        boolean bought = false;
        for (int i = 0; i < amount; i++) {
            if (stock(shop, itemId) <= 0) {
                player.getPacketSender().sendMessage("The shop has run out of stock.");
                break;
            }
            if (player.getInventory().isFull() && !(def.isStackable() && player.getInventory().contains(itemId))) {
                player.getInventory().full();
                break;
            }
            int price = buyPrice(shop, itemId);
            if (shop.getCurrency().getAmountForPlayer(player) < price) {
                player.getPacketSender().sendMessage("You don't have enough coins.");
                break;
            }
            shop.getCurrency().decrementForPlayer(player, price);
            slotItem(shop, itemId).setAmount(stock(shop, itemId) - 1);
            player.getInventory().add(itemId, 1);
            bought = true;
        }
        if (bought) {
            changed(shop);
        }
    }

    public static void sell(Player player, Shop shop, int slot, int itemId, int amount) {
        if (slot >= player.getInventory().capacity() || player.getInventory().getItems()[slot].getId() != itemId) {
            return;
        }
        if (!buys(shop, itemId)) {
            player.getPacketSender().sendMessage("You can't sell this item to this shop.");
            return;
        }
        boolean sold = false;
        for (int i = 0; i < amount && player.getInventory().contains(itemId); i++) {
            if (slotItem(shop, itemId) == null && shop.isFull()) {
                player.getPacketSender().sendMessage("The shop is currently full.");
                break;
            }
            int price = sellPrice(shop, itemId);
            player.getInventory().delete(itemId, 1);
            shop.getCurrency().incrementForPlayer(player, price);
            if (slotItem(shop, itemId) == null) {
                shop.addItem(itemId, 1);
            } else {
                slotItem(shop, itemId).setAmount(stock(shop, itemId) + 1);
            }
            sold = true;
        }
        if (sold) {
            changed(shop);
        }
    }

    private static void changed(Shop shop) {
        ShopManager.refresh(shop);
        if (!shop.isRestocking()) {
            shop.setRestocking(true);
            TaskManager.submit(new RestockTask(shop));
        }
    }

    /** One tick per execution: each item whose stock differs from its start counts up and moves one unit toward the start every period. */
    private static final class RestockTask extends Task {
        private final Shop shop;

        RestockTask(Shop shop) {
            super(1);
            this.shop = shop;
        }

        @Override
        protected void execute() {
            boolean any = false, moved = false;
            for (int i = 0; i < shop.getCurrentStock().length; i++) {
                Item it = shop.getCurrentStock()[i];
                if (it == null) {
                    continue;
                }
                int id = it.getId(), st = start(shop, id);
                if (it.getAmount() == st) {
                    shop.restockCounters.remove(id);
                    continue;
                }
                any = true;
                Integer period = shop.faithfulDef.getRestock() == null ? null : shop.faithfulDef.getRestock().get(String.valueOf(id));
                int c = shop.restockCounters.merge(id, 1, Integer::sum);
                if (c >= (period == null ? EXTRA_DECAY_TICKS : period)) {
                    shop.restockCounters.remove(id);
                    int next = it.getAmount() + (it.getAmount() < st ? 1 : -1);
                    if (next <= 0 && st == 0) {
                        shop.getCurrentStock()[i] = null;
                    } else {
                        it.setAmount(next);
                    }
                    moved = true;
                }
            }
            if (moved) {
                ShopManager.refresh(shop);
            }
            if (!any) {
                stop();
            }
        }

        @Override
        public void stop() {
            super.stop();
            shop.setRestocking(false);
        }
    }
}
