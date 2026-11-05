package com.elvarg.game.definition.loader.impl;

import com.elvarg.game.GameConstants;
import com.elvarg.game.definition.ItemDefinition;
import com.elvarg.game.definition.loader.DefinitionLoader;
import com.google.gson.Gson;

import java.io.FileReader;

public class ItemDefinitionLoader extends DefinitionLoader {

    @Override
    public void load() throws Throwable {
    	ItemDefinition.definitions.clear();
        FileReader reader = new FileReader(file());
        ItemDefinition[] defs = new Gson().fromJson(reader, ItemDefinition[].class);
        for (ItemDefinition def : defs) {
            ItemDefinition.definitions.put(def.getId(), def);
        }
        reader.close();
        // Real-game item values (OSRS Wiki) for the items the faithful shops and early economy use; Elvarg's table differs.
        java.io.File vf = new java.io.File(GameConstants.DEFINITIONS_DIRECTORY + "item_values_osrs.json");
        if (vf.exists()) {
            try (FileReader r = new FileReader(vf)) {
                com.google.gson.JsonObject o = new Gson().fromJson(r, com.google.gson.JsonObject.class);
                for (java.util.Map.Entry<String, com.google.gson.JsonElement> e : o.entrySet()) {
                    ItemDefinition d = ItemDefinition.definitions.get(Integer.parseInt(e.getKey()));
                    if (d != null) {
                        d.applyOsrsValue(e.getValue().getAsInt());
                    }
                }
            }
        }
    }

    @Override
    public String file() {
        return GameConstants.DEFINITIONS_DIRECTORY + "items.json";
    }
}
