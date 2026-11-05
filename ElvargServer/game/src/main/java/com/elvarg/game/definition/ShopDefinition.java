package com.elvarg.game.definition;

import com.elvarg.game.model.Item;
import com.elvarg.game.model.container.shop.currency.ShopCurrencies;

import java.util.Map;

/**
 * Represents a definition for a shop.
 *
 * @author Professor Oak
 */
public class ShopDefinition {

    private int id;
    private String name = "";
    private Item[] originalStock;
    private ShopCurrencies currency;
    /** Faithful OSRS shop (data/definitions/shops_osrs.json): multipliers are per-mille (1000 = 100%). */
    private boolean faithful;
    private boolean general;
    private int sellMultiplier = 1000;
    private int buyMultiplier = 600;
    private int delta = 20;
    /** item id (string) -> restock period in game ticks */
    private Map<String, Integer> restock;

    public int getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public Item[] getOriginalStock() {
        return originalStock;
    }

    public ShopCurrencies getCurrency() {
        return currency;
    }

    public boolean isFaithful() {
        return faithful;
    }

    public boolean isGeneral() {
        return general;
    }

    public int getSellMultiplier() {
        return sellMultiplier;
    }

    public int getBuyMultiplier() {
        return buyMultiplier;
    }

    public int getDelta() {
        return delta;
    }

    public Map<String, Integer> getRestock() {
        return restock;
    }
}
