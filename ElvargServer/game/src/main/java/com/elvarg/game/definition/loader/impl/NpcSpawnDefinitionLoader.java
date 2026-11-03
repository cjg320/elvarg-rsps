package com.elvarg.game.definition.loader.impl;

import java.io.FileReader;

import com.elvarg.game.GameConstants;
import com.elvarg.game.World;
import com.elvarg.game.definition.NpcSpawnDefinition;
import com.elvarg.game.definition.loader.DefinitionLoader;
import com.elvarg.game.entity.impl.npc.NPC;
import com.google.gson.Gson;

public class NpcSpawnDefinitionLoader extends DefinitionLoader {

    @Override
    public void load() throws Throwable {
        FileReader reader = new FileReader(file());
        NpcSpawnDefinition[] defs = new Gson().fromJson(reader, NpcSpawnDefinition[].class);
        for (NpcSpawnDefinition def : defs) {
            NPC npc = NPC.create(def.getId(), def.getPosition());
            npc.getMovementCoordinator().setRadius(def.getRadius());
            npc.setFace(def.getFacing());
            World.getAddNPCQueue().add(npc);
        }
        reader.close();
        loadOsrsSpawns(defs);
    }

    /**
     * Real-game NPC population (owner decision 2026-11-02): env NPC_SPAWN_SET=osrs also loads
     * npc_spawns_osrs.json (generated from the OSRS Wiki by osrsproject/tools/import_wiki_spawns.py). Spawns that
     * duplicate a stock spawn (same id and tile) or sit on a blocked/unloaded tile are skipped; NPC_SPAWN_MAX_COMBAT
     * optionally drops monsters above a combat level (performance). Unset = stock behaviour.
     */
    private void loadOsrsSpawns(NpcSpawnDefinition[] stock) throws Throwable {
        String set = System.getenv("NPC_SPAWN_SET");
        if (set == null || !set.contains("osrs")) {
            return;
        }
        int maxCombat = System.getenv("NPC_SPAWN_MAX_COMBAT") == null ? Integer.MAX_VALUE
                : Integer.parseInt(System.getenv("NPC_SPAWN_MAX_COMBAT"));
        java.util.Set<String> have = new java.util.HashSet<>();
        for (NpcSpawnDefinition d : stock) {
            have.add(d.getId() + ":" + d.getPosition().getX() + ":" + d.getPosition().getY() + ":" + d.getPosition().getZ());
        }
        int added = 0, dup = 0, blocked = 0, tooStrong = 0, noDef = 0;
        try (FileReader r = new FileReader(GameConstants.DEFINITIONS_DIRECTORY + "npc_spawns_osrs.json")) {
            NpcSpawnDefinition[] defs = new Gson().fromJson(r, NpcSpawnDefinition[].class);
            for (NpcSpawnDefinition def : defs) {
                String key = def.getId() + ":" + def.getPosition().getX() + ":" + def.getPosition().getY() + ":" + def.getPosition().getZ();
                if (!have.add(key)) {
                    dup++;
                    continue;
                }
                com.elvarg.game.definition.NpcDefinition nd = com.elvarg.game.definition.NpcDefinition.forId(def.getId());
                if (nd == null) {
                    noDef++;
                    continue;
                }
                if (nd.getCombatLevel() > maxCombat) {
                    tooStrong++;
                    continue;
                }
                com.elvarg.game.model.Location at = def.getPosition();
                if (!com.elvarg.game.collision.RegionManager.getRegion(at.getX(), at.getY()).isPresent()
                        || com.elvarg.game.collision.RegionManager.blocked(at.getX(), at.getY(), at.getZ())) {
                    blocked++;
                    continue;
                }
                NPC npc = NPC.create(def.getId(), def.getPosition());
                npc.getMovementCoordinator().setRadius(def.getRadius());
                npc.setFace(def.getFacing());
                World.getAddNPCQueue().add(npc);
                added++;
            }
        }
        System.out.println("[Spawns] OSRS spawn set: added " + added + ", duplicate " + dup + ", blocked/unloaded " + blocked
                + ", above combat cap " + tooStrong + ", no definition " + noDef);
    }

    @Override
    public String file() {
        return GameConstants.DEFINITIONS_DIRECTORY + "npc_spawns.json";
    }

}
