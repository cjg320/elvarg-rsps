package com.elvarg.game.definition.loader.impl;

import com.elvarg.game.GameConstants;
import com.elvarg.game.definition.ShopDefinition;
import com.elvarg.game.definition.loader.DefinitionLoader;
import com.elvarg.game.model.container.shop.Shop;
import com.elvarg.game.model.container.shop.ShopManager;
import com.google.gson.Gson;

import java.io.FileReader;

public class ShopDefinitionLoader extends DefinitionLoader {

    @Override
    public void load() throws Throwable {
        FileReader reader = new FileReader(file());
        ShopDefinition[] defs = new Gson().fromJson(reader, ShopDefinition[].class);
        for (ShopDefinition def : defs) {
            if (def.getCurrency() != null) {
                // Shop JSON has "currency" property defined
                ShopManager.shops.put(def.getId(), new Shop(def.getId(), def.getName(), def.getOriginalStock(), def.getCurrency().get()));
            } else {
                ShopManager.shops.put(def.getId(), new Shop(def.getId(), def.getName(), def.getOriginalStock()));
            }

        }
        reader.close();
        // Faithful OSRS shops (stock, multipliers and restock from the OSRS Wiki; tools/build_content_data.py).
        java.io.File ff = new java.io.File(GameConstants.DEFINITIONS_DIRECTORY + "shops_osrs.json");
        if (ff.exists()) {
            try (FileReader r2 = new FileReader(ff)) {
                for (ShopDefinition def : new Gson().fromJson(r2, ShopDefinition[].class)) {
                    Shop shop = new Shop(def.getId(), def.getName(), def.getOriginalStock());
                    shop.faithfulDef = def;
                    ShopManager.shops.put(def.getId(), shop);
                }
            }
        }
    }

    @Override
    public String file() {
        return GameConstants.DEFINITIONS_DIRECTORY + "shops.json";
    }
}
