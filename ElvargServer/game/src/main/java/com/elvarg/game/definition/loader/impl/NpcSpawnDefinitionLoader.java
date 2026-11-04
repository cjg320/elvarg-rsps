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
        // Real-game stats (hp, max hit, attack speed) from the OSRS Wiki, where Elvarg's definitions differ.
        int statsApplied = 0;
        java.io.File ovf = new java.io.File(GameConstants.DEFINITIONS_DIRECTORY + "npc_stat_overrides_osrs.json");
        if (ovf.exists()) {
            try (FileReader r = new FileReader(ovf)) {
                com.google.gson.JsonObject o = new Gson().fromJson(r, com.google.gson.JsonObject.class);
                for (java.util.Map.Entry<String, com.google.gson.JsonElement> e : o.entrySet()) {
                    com.elvarg.game.definition.NpcDefinition nd = com.elvarg.game.definition.NpcDefinition.forId(Integer.parseInt(e.getKey()));
                    if (nd == null) {
                        continue;
                    }
                    com.google.gson.JsonObject v = e.getValue().getAsJsonObject();
                    nd.applyOsrsStats(v.has("hitpoints") ? v.get("hitpoints").getAsInt() : null,
                            v.has("maxHit") ? v.get("maxHit").getAsInt() : null,
                            v.has("attackSpeed") ? v.get("attackSpeed").getAsInt() : null);
                    statsApplied++;
                }
            }
        }
        java.util.Set<String> have = new java.util.HashSet<>();
        for (NpcSpawnDefinition d : stock) {
            have.add(d.getId() + ":" + d.getPosition().getX() + ":" + d.getPosition().getY() + ":" + d.getPosition().getZ());
        }
        int added = 0, dup = 0, blocked = 0, tooStrong = 0, noDef = 0, blockedNoRegion = 0, rejBlockedTile = 0, rejUnloaded = 0, rejUnknown = 0, rejObject = 0;
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
                if (!com.elvarg.game.collision.RegionManager.getRegion(at.getX(), at.getY()).isPresent()) {
                    blockedNoRegion++;
                    continue;
                }
                int clip = com.elvarg.game.collision.RegionManager.getClipping(at.getX(), at.getY(), at.getZ(), null);
                // Only a tile that cannot be stood on rejects the spawn (solid object/blocked/unloaded). A directional wall
                // bit (0x20) beside the tile does not make it unoccupiable.
                if ((clip & (0x200000 | 0x1000000 | 0x80000 | 0x100)) != 0) {
                    blocked++;
                    if ((clip & 0x200000) != 0) { rejBlockedTile++; }
                    if ((clip & 0x1000000) != 0) { rejUnloaded++; }
                    if ((clip & 0x80000) != 0) { rejUnknown++; }
                    if ((clip & 0x100) != 0) { rejObject++; }
                    continue;
                }
                NPC npc = NPC.create(def.getId(), def.getPosition());
                npc.getMovementCoordinator().setRadius(def.getRadius());
                npc.setFace(def.getFacing());
                World.getAddNPCQueue().add(npc);
                added++;
            }
        }
        System.out.println("[Spawns] OSRS spawn set: added " + added + ", duplicate " + dup + ", rejected tile " + blocked + " (no region " + blockedNoRegion + "; bits: blockedTile " + rejBlockedTile + ", unloaded " + rejUnloaded + ", unknown " + rejUnknown + ", object " + rejObject + "), stats overridden " + statsApplied
                + ", above combat cap " + tooStrong + ", no definition " + noDef);
        System.out.println("[Spawns] map objects skipped (cache cannot define them): " + com.elvarg.game.collision.RegionManager.SKIPPED_MAP_OBJECTS);
    }

    @Override
    public String file() {
        return GameConstants.DEFINITIONS_DIRECTORY + "npc_spawns.json";
    }

}
