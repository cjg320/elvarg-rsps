package com.elvarg.game.content;

import com.elvarg.game.GameConstants;
import com.elvarg.game.entity.impl.object.FenceGates;
import com.elvarg.game.entity.impl.object.GameObject;
import com.elvarg.game.entity.impl.object.MapObjects;
import com.elvarg.game.entity.impl.player.Player;
import com.elvarg.game.model.Location;
import com.elvarg.game.model.Skill;
import com.elvarg.game.model.dialogues.builders.DynamicDialogueBuilder;
import com.elvarg.game.model.dialogues.entries.impl.OptionDialogue;
import com.elvarg.game.model.teleportation.TeleportHandler;
import com.elvarg.game.model.teleportation.TeleportType;
import com.elvarg.game.task.Task;
import com.elvarg.game.task.TaskManager;
import com.elvarg.util.ItemIdentifiers;
import com.elvarg.util.Misc;
import com.google.gson.Gson;

import java.io.FileReader;

/**
 * Stronghold of Security, first level (Vault of War). The map objects, ladders, portal and chest are already in Elvarg's cache; this class
 * adds their behaviour (OSRS Wiki: Stronghold of Security, Gate of War, Gift of Peace, Portal (Stronghold of Security); raw wikitext read
 * 2026-11-05, tools/build_content_data.py). Object ids: 20790 entrance ladder (Barbarian Village 3081,3420), 20784 ladders up, 19206/19207
 * Gate of War leaves (open 19209/19210 - assumption, checked live by the option "Close"), 20786 portal, 20656 Gift of Peace chest.
 * <p>
 * DOCUMENTED CHOICES (reviewed in DECISIONS M2-shops-sos): the wiki states no landing tiles (we land beside the ladder/portal), no
 * wrong-answer rule (a wrong answer leaves the gate closed and shows the wiki's response), no prompt probability (every second door of a pair
 * asks while the Gift of Peace is unclaimed), and the 2FA requirement of the real chest has no meaning here (every account qualifies).
 */
public final class Stronghold {

    /** questProgress key holding the level-1 Gift of Peace claim (1 = claimed); persisted with the player save. */
    public static final int GIFT_OF_PEACE_L1 = 9001;
    public static final int GIFT_COINS_L1 = 2000;

    private static final int ENTRANCE = 20790, LADDER_UP = 20784, PORTAL = 20786, CHEST = 20656;
    private static final int L_CLOSED = 19206, R_CLOSED = 19207, L_OPEN = 19209, R_OPEN = 19210;
    private static final Location SURFACE = new Location(3081, 3421, 0), LEVEL1_START = new Location(1859, 5243, 0),
            CHEST_ROOM = new Location(1907, 5224, 0);

    private static final int AUTO_CLOSE_TICKS = 15;

    private static Question[] questions;

    private Stronghold() {
    }

    private static final class Answer {
        String text, response;
        boolean correct;
    }

    private static final class Question {
        String question;
        Answer[] answers;
    }

    private static synchronized Question[] questions() {
        if (questions == null) {
            try (FileReader r = new FileReader(GameConstants.DEFINITIONS_DIRECTORY + "stronghold_questions.json")) {
                questions = new Gson().fromJson(r, Question[].class);
            } catch (Exception e) {
                questions = new Question[0];
            }
        }
        return questions;
    }

    public static boolean giftClaimed(Player p) {
        return p.getQuestProgress().getOrDefault(GIFT_OF_PEACE_L1, 0) == 1;
    }

    /** Returns true if the click was a Stronghold object (handled, possibly refused). */
    public static boolean handle(Player player, GameObject o) {
        int id = o.getId();
        int x = o.getLocation().getX(), y = o.getLocation().getY();
        if (o.getPrivateArea() != null || player.getPrivateArea() != null) {
            return false;
        }
        switch (id) {
            case ENTRANCE:
                if (x == 3081 && y == 3420) {
                    TeleportHandler.teleport(player, LEVEL1_START.clone(), TeleportType.LADDER_DOWN, false);
                    return true;
                }
                return false;
            case LADDER_UP:
                if ((x == 1859 && y == 5244) || (x == 1913 && y == 5226)) {
                    TeleportHandler.teleport(player, SURFACE.clone(), TeleportType.LADDER_UP, false);
                    return true;
                }
                return false;
            case PORTAL:
                if (x == 1863 && y == 5238) {
                    if (player.getSkillManager().getCombatLevel() >= 26 || giftClaimed(player)) {
                        player.getPacketSender().sendMessage("You enter the portal to be whisked through to the treasure room.");
                        TeleportHandler.teleport(player, CHEST_ROOM.clone(), TeleportType.LEVER, false);
                    } else {
                        player.getPacketSender().sendMessage("You need a combat level of 26 or to have claimed the Gift of Peace to use this portal.");
                    }
                    return true;
                }
                return false;
            case CHEST:
                if (x == 1907 && y == 5222) {
                    openChest(player);
                    return true;
                }
                return false;
            case L_CLOSED:
            case R_CLOSED:
            case L_OPEN:
            case R_OPEN:
                return gate(player, o);
            default:
                return false;
        }
    }

    private static void openChest(Player player) {
        if (giftClaimed(player)) {
            player.getPacketSender().sendMessage("You have already claimed this reward.");
            return;
        }
        if (player.getInventory().getFreeSlots() == 0 && !player.getInventory().contains(ItemIdentifiers.COINS)) {
            player.getInventory().full();
            return;
        }
        player.getInventory().add(ItemIdentifiers.COINS, GIFT_COINS_L1);
        player.getQuestProgress().put(GIFT_OF_PEACE_L1, 1);
        player.getSkillManager().setCurrentLevel(Skill.HITPOINTS, player.getSkillManager().getMaxLevel(Skill.HITPOINTS), true);
        player.getSkillManager().setCurrentLevel(Skill.PRAYER, player.getSkillManager().getMaxLevel(Skill.PRAYER), true);
        player.getPacketSender().sendMessage("You open the chest and find the Gift of Peace: " + GIFT_COINS_L1 + " coins.");
    }

    // ---- Gates of War -------------------------------------------------------------------------------------------------------------------

    /** Clockwise rotation by f quarter turns of (dx,dy), as in FenceGates: one turn maps west (-1,0) to north (0,1). */
    private static int[] rot(int f, int dx, int dy) {
        for (int i = 0; i < (f & 3); i++) {
            int t = dx;
            dx = dy;
            dy = -t;
        }
        return new int[] { dx, dy };
    }

    private static Location at(Location l, int dx, int dy) {
        return new Location(l.getX() + dx, l.getY() + dy, l.getZ());
    }

    private static GameObject find(int id, Location loc, int face) {
        GameObject o = MapObjects.get(id, loc, null);
        return o != null && o.getType() == 0 && o.getFace() == face && o.getPrivateArea() == null ? o : null;
    }

    /**
     * Gate geometry (INFERRED, as for the fence gates: only collision matters). The first leaf (19206) and second leaf (19207) of a closed gate
     * with face f sit one tile apart along rot(f, 0, 1); opening turns the first leaf to face f-1 and the second to f+1 on the same tiles, so the
     * leaves lie against the corridor walls and the crossing line is free. Closing is the inverse.
     */
    private static boolean gate(Player player, GameObject clicked) {
        boolean closed = clicked.getId() == L_CLOSED || clicked.getId() == R_CLOSED;
        boolean left = clicked.getId() == L_CLOSED || clicked.getId() == L_OPEN;
        int f = closed ? clicked.getFace() : (clicked.getFace() + (left ? 1 : 3)) & 3;
        int[] along = rot(f, 0, 1);
        Location lLoc = left ? clicked.getLocation() : at(clicked.getLocation(), -along[0], -along[1]);
        Location rLoc = left ? at(clicked.getLocation(), along[0], along[1]) : clicked.getLocation();
        int lf = (f + 3) & 3, rf = (f + 1) & 3;
        GameObject l = closed ? find(L_CLOSED, lLoc, f) : find(L_OPEN, lLoc, lf);
        GameObject r = closed ? find(R_CLOSED, rLoc, f) : find(R_OPEN, rLoc, rf);
        if (l == null || r == null) {
            return true;
        }
        if (closed) {
            if (!giftClaimed(player) && inSafeSpace(player, lLoc, f)) {
                ask(player, l, r, f, lLoc, rLoc, lf, rf);
                return true;
            }
            openPair(l, r, f, lLoc, rLoc, lf, rf);
        } else {
            FenceGates.replace(l, r, new GameObject(L_CLOSED, lLoc, 0, f, null), new GameObject(R_CLOSED, rLoc, 0, f, null));
        }
        return true;
    }

    /**
     * Opens a gate and schedules it to close again: the cache gives the open leaves (19209/19210) no options, so there is nothing to click to
     * close them and the real doors swing shut by themselves. Delay AUTO_CLOSE_TICKS is an approximation (the wiki gives none).
     */
    private static void openPair(GameObject l, GameObject r, int f, Location lLoc, Location rLoc, int lf, int rf) {
        FenceGates.replace(l, r, new GameObject(L_OPEN, lLoc, 0, lf, null), new GameObject(R_OPEN, rLoc, 0, rf, null));
        TaskManager.submit(new Task(AUTO_CLOSE_TICKS) {
            @Override
            protected void execute() {
                stop();
                GameObject ol = find(L_OPEN, lLoc, lf), or = find(R_OPEN, rLoc, rf);
                if (ol != null && or != null) {
                    FenceGates.replace(ol, or, new GameObject(L_CLOSED, lLoc, 0, f, null), new GameObject(R_CLOSED, rLoc, 0, f, null));
                }
            }
        });
    }

    /** Edge coordinates: a face-f wall blocks the edge half a tile from the object's tile toward f (0 west, 1 north, 2 east, 3 south). */
    private static double edge(Location l, int f) {
        return f == 0 ? l.getX() - 0.5 : f == 2 ? l.getX() + 0.5 : f == 1 ? l.getY() + 0.5 : l.getY() - 0.5;
    }

    private static double coord(Location l, int f) {
        return (f & 1) == 0 ? l.getX() : l.getY();
    }

    /**
     * Wiki: between each set of doors is an empty space; the second door of a pair (entered from that space) asks the question, while passing from
     * the monster room is immediate. The partner gate is the same-face first leaf three tiles away on the same line; the player is "in the space"
     * when the partner's edge lies on the player's side of the clicked gate's edge.
     */
    private static boolean inSafeSpace(Player player, Location lLoc, int f) {
        double mine = edge(lLoc, f);
        for (int d : new int[] { -3, 3 }) {
            int dx = (f & 1) == 0 ? d : 0, dy = (f & 1) == 0 ? 0 : d;
            Location pl = at(lLoc, dx, dy);
            GameObject p = find(L_CLOSED, pl, f);
            if (p == null) {
                p = find(L_OPEN, pl, (f + 3) & 3);
            }
            if (p == null) {
                continue;
            }
            double partner = edge(pl, f);
            double me = coord(player.getLocation(), f);
            return (me - mine) * (partner - mine) > 0;
        }
        return false;
    }

    private static void ask(Player player, GameObject l, GameObject r, int f, Location lLoc, Location rLoc, int lf, int rf) {
        Question[] qs = questions();
        if (qs.length == 0) {
            openPair(l, r, f, lLoc, rLoc, lf, rf);
            return;
        }
        final Question q = qs[Misc.getRandom(qs.length - 1)];
        String[] opts = new String[q.answers.length];
        for (int i = 0; i < opts.length; i++) {
            opts[i] = q.answers[i].text;
        }
        player.getDialogueManager().start(new DynamicDialogueBuilder() {
            @Override
            public void build(Player p) {
                add(new OptionDialogue(0, q.question, (option) -> {
                    p.getPacketSender().sendInterfaceRemoval();
                    int i = option.ordinal();
                    if (i >= q.answers.length) {
                        return;
                    }
                    p.getPacketSender().sendMessage(q.answers[i].response);
                    GameObject cl = find(L_CLOSED, lLoc, f), cr = find(R_CLOSED, rLoc, f);
                    if (q.answers[i].correct && cl != null && cr != null) {
                        openPair(cl, cr, f, lLoc, rLoc, lf, rf);
                    }
                }, opts));
            }
        });
    }
}
