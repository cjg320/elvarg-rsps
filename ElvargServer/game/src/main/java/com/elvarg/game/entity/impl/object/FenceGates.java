package com.elvarg.game.entity.impl.object;

import com.elvarg.game.World;
import com.elvarg.game.entity.impl.player.Player;
import com.elvarg.game.model.Location;

/**
 * Double fence gates (closed ids 1558 and 1560, open ids 1559 and 1567). Opening one leaf swings both leaves outward
 * onto their own outer posts, and closing restores them.
 * <p>
 * SOURCE / LABEL: id pairing 1558->1559 and 1560->1567 follows RuneLite's ObjectID names. The geometry is INFERRED from
 * a third-party implementation (RSOSRS commit ddec621bc4cc1e4fd6c8e6484293ddbbb0564e3e, osrs_core/data/doors.json,
 * checked there against the real Lumbridge farm gate): in the frame where a gate is a wall on the west edge of a tile
 * running north (face 0), each leaf moves one tile west and turns one quarter turn: 1558 (the leaf at the south end)
 * turns anticlockwise (face-1), 1560 (the north leaf) clockwise (face+1). A gate placed with face f rotates the offset
 * clockwise by f quarter turns; the real placements confirm 1558 is always the first leaf in that rotated frame
 * (diag_objects at Lumbridge: face 2 -> 1558 north of 1560, face 0 -> 1558 south, face 1 -> 1558 west).
 * Closing is the exact inverse. Only wall-type (0) static or restored gates are handled; anything unexpected is a no-op.
 */
public final class FenceGates {

    private static final int L_CLOSED = 1558, L_OPEN = 1559, R_CLOSED = 1560, R_OPEN = 1567;

    private FenceGates() {
    }

    public static boolean isGateId(int id) {
        return id == L_CLOSED || id == L_OPEN || id == R_CLOSED || id == R_OPEN;
    }

    /** Clockwise rotation by f quarter turns of the vector (dx,dy): one turn maps west (-1,0) to north (0,1). */
    private static int[] rot(int f, int dx, int dy) {
        for (int i = 0; i < (f & 3); i++) {
            int t = dx;
            dx = dy;
            dy = -t;
        }
        return new int[] { dx, dy };
    }

    private static Location at(Location l, int[] d) {
        return new Location(l.getX() + d[0], l.getY() + d[1], l.getZ());
    }

    private static GameObject find(int id, Location loc, int type, int face) {
        GameObject o = MapObjects.get(id, loc, null);
        return o != null && o.getType() == type && o.getFace() == face && o.getPrivateArea() == null ? o : null;
    }

    /** True if some other wall-layer object (types 0-3) occupies the tile besides the ones being moved. */
    private static boolean wallOccupied(Location loc, GameObject... ignore) {
        java.util.ArrayList<GameObject> l = MapObjects.mapObjects.get(MapObjects.getHash(loc.getX(), loc.getY(), loc.getZ()));
        if (l == null) {
            return false;
        }
        for (GameObject o : l) {
            if (o.getLocation().equals(loc) && o.getType() >= 0 && o.getType() <= 3) {
                boolean ig = false;
                for (GameObject i : ignore) {
                    ig |= i == o;
                }
                if (!ig) {
                    return true;
                }
            }
        }
        return false;
    }

    /** Handles a click on a gate leaf. Returns true if the object was a gate (even if refused). */
    public static synchronized boolean toggle(Player player, GameObject clicked) {
        if (clicked == null || !isGateId(clicked.getId())) {
            return false;
        }
        if (clicked.getPrivateArea() != null || clicked.getType() != 0 || player.getPrivateArea() != null) {
            return true;
        }
        boolean closed = clicked.getId() == L_CLOSED || clicked.getId() == R_CLOSED;
        boolean left = clicked.getId() == L_CLOSED || clicked.getId() == L_OPEN;
        // Closed-state face and tile of the clicked leaf.
        int f = closed ? clicked.getFace() : (clicked.getFace() + (left ? 1 : -1)) & 3;
        int[] out = rot(f, -1, 0);
        Location clickedClosedLoc = closed ? clicked.getLocation()
                : at(clicked.getLocation(), new int[] { -out[0], -out[1] });
        // Partner: the other leaf sits one tile along the wall; 1558 is the first leaf, 1560 the one ahead in the rotated frame.
        int[] along = rot(f, 0, 1);
        Location leftClosedLoc = left ? clickedClosedLoc : at(clickedClosedLoc, new int[] { -along[0], -along[1] });
        Location rightClosedLoc = left ? at(clickedClosedLoc, along) : clickedClosedLoc;
        Location leftOpenLoc = at(leftClosedLoc, out), rightOpenLoc = at(rightClosedLoc, out);
        int lf = (f + 3) & 3, rf = (f + 1) & 3;
        if (closed) {
            GameObject lc = find(L_CLOSED, leftClosedLoc, 0, f), rc = find(R_CLOSED, rightClosedLoc, 0, f);
            if (lc == null || rc == null || wallOccupied(leftOpenLoc, lc, rc) || wallOccupied(rightOpenLoc, lc, rc)) {
                return true;
            }
            replace(lc, rc, new GameObject(L_OPEN, leftOpenLoc, 0, lf, null), new GameObject(R_OPEN, rightOpenLoc, 0, rf, null));
        } else {
            GameObject lo = find(L_OPEN, leftOpenLoc, 0, lf), ro = find(R_OPEN, rightOpenLoc, 0, rf);
            if (lo == null || ro == null || wallOccupied(leftClosedLoc, lo, ro) || wallOccupied(rightClosedLoc, lo, ro)) {
                return true;
            }
            replace(lo, ro, new GameObject(L_CLOSED, leftClosedLoc, 0, f, null), new GameObject(R_CLOSED, rightClosedLoc, 0, f, null));
        }
        return true;
    }

    /** Removes two leaves and places two others in one step (preflighted by the caller). */
    private static void replace(GameObject oldA, GameObject oldB, GameObject newA, GameObject newB) {
        for (GameObject o : new GameObject[] { oldA, oldB }) {
            World.getObjects().removeIf(x -> x.equals(o));
            ObjectManager.perform(o, ObjectManager.OperationType.DESPAWN);
            World.getRemovedObjects().add(o);
        }
        for (GameObject n : new GameObject[] { newA, newB }) {
            World.getRemovedObjects().removeIf(x -> x.equals(n));
            World.getObjects().add(n);
            ObjectManager.perform(n, ObjectManager.OperationType.SPAWN);
        }
    }
}
