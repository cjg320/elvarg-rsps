package com.elvarg.net.packet.impl;

import java.util.Optional;

import com.elvarg.game.content.sound.Sound;
import com.elvarg.game.content.sound.SoundManager;
import com.elvarg.game.collision.RegionManager;
import com.elvarg.game.definition.ItemDefinition;
import com.elvarg.game.definition.ObjectDefinition;
import com.elvarg.game.entity.impl.object.GameObject;
import com.elvarg.game.entity.impl.object.MapObjects;
import com.elvarg.game.model.areas.impl.PrivateArea;
import com.elvarg.game.entity.impl.grounditem.ItemOnGround;
import com.elvarg.game.entity.impl.grounditem.ItemOnGroundManager;
import com.elvarg.game.entity.impl.grounditem.ItemOnGroundManager.OperationType;
import com.elvarg.game.entity.impl.player.Player;
import com.elvarg.game.model.Location;
import com.elvarg.game.model.rights.PlayerRights;
import com.elvarg.game.task.impl.WalkToTask;
import com.elvarg.net.packet.Packet;
import com.elvarg.net.packet.PacketExecutor;

/**
 * This packet listener is used to pick up ground items that exist in the world.
 *
 * @author relex lawl
 */

public class PickupItemPacketListener implements PacketExecutor {

	@Override
	public void execute(final Player player, Packet packet) {
		final int y = packet.readLEShort();
		final int itemId = packet.readShort();
		final int x = packet.readLEShort();
		final Location position = new Location(x, y, player.getLocation().getZ());

		if (player.getRights() == PlayerRights.DEVELOPER) {
			player.getPacketSender()
					.sendMessage("Pick up item: " + itemId + ". " + position.toString());
		}

		Optional<ItemOnGround> item = ItemOnGroundManager.getGroundItem(Optional.of(player.getUsername()), itemId, position, player.getPrivateArea());
		if (!item.isPresent()) {
			return;
		}

		if (player.busy() || !player.getLastItemPickup().elapsed(300)) {
			// If player is busy or last item was picked up less than 0.3 seconds ago
			return;
		}

		// Items lying on a table/counter tile: real OSRS lets you take them from an adjacent tile (Codex-approved,
		// DECISIONS M2-table-pickup). Floor items keep the stock behaviour: stand on the tile.
		if (isTableTile(position, player.getPrivateArea())) {
			Location stand = nearestStandTile(player, position);
			if (stand == null) {
				player.getPacketSender().sendMessage("I can't reach that!");
				return;
			}
			ItemOnGround standTarget = new ItemOnGround(ItemOnGround.State.SEEN_BY_EVERYONE, Optional.empty(), stand,
					item.get().getItem(), false, 0, null);
			WalkToTask.submit(player, standTarget, () -> takeItem(player, itemId, position));
			return;
		}

		WalkToTask.submit(player, item.get(), () -> takeItem(player, itemId, position));
	}

	/** True when a table or counter scenery object sits on the tile (the only blocked-tile case we support). */
	private static boolean isTableTile(Location tile, PrivateArea area) {
		if (!RegionManager.blocked(tile, area)) {
			return false;
		}
		java.util.ArrayList<GameObject> objs = MapObjects.mapObjects.get(MapObjects.getHash(tile.getX(), tile.getY(), tile.getZ()));
		if (objs == null) {
			return false;
		}
		for (GameObject o : objs) {
			ObjectDefinition d = ObjectDefinition.forId(o.getId());
			if (d != null && d.name != null && (d.name.toLowerCase().contains("table") || d.name.toLowerCase().contains("counter"))) {
				return true;
			}
		}
		return false;
	}

	/** Cardinal neighbour of the item tile that a player can stand on with no wall edge between it and the item. */
	private static boolean isStandTile(Location item, Location p, PrivateArea area) {
		if (p.getZ() != item.getZ()) {
			return false;
		}
		int dx = item.getX() - p.getX(), dy = item.getY() - p.getY();
		if (Math.abs(dx) + Math.abs(dy) != 1 || RegionManager.blocked(p, area)) {
			return false;
		}
		// Wall bits live on the item tile: 0x80 west edge, 0x8 east edge, 0x20 south edge, 0x2 north edge.
		int mask = dx == 1 ? 0x80 : dx == -1 ? 0x8 : dy == 1 ? 0x20 : 0x2;
		return (RegionManager.getClipping(item.getX(), item.getY(), item.getZ(), area) & mask) == 0;
	}

	private static Location nearestStandTile(Player player, Location item) {
		Location best = null;
		int bestD = Integer.MAX_VALUE;
		int[][] dirs = { { 1, 0 }, { -1, 0 }, { 0, 1 }, { 0, -1 } };
		for (int[] d : dirs) {
			Location c = new Location(item.getX() + d[0], item.getY() + d[1], item.getZ());
			if (isStandTile(item, c, player.getPrivateArea())) {
				int dist = player.getLocation().getDistance(c);
				if (dist < bestD) {
					bestD = dist;
					best = c;
				}
			}
		}
		return best;
	}

	private static boolean canReach(Player player, Location item) {
		Location p = player.getLocation();
		if (p.equals(item)) {
			return true;
		}
		return isTableTile(item, player.getPrivateArea()) && isStandTile(item, p, player.getPrivateArea());
	}

	private void takeItem(Player player, int itemId, Location position) {
		int x = position.getX(), y = position.getY();

		if (!canReach(player, position)) {
			player.getMovementQueue().reset();
			return;
		}

		// Check if we can hold it..
		if (!(player.getInventory().getFreeSlots() > 0 || (player.getInventory().getFreeSlots() == 0
				&& ItemDefinition.forId(itemId).isStackable() && player.getInventory().contains(itemId)))) {
			player.getInventory().full();
			return;
		}

		Optional<ItemOnGround> item = ItemOnGroundManager.getGroundItem(Optional.of(player.getUsername()), itemId, position, player.getPrivateArea());
		boolean deregister = true;
		if (item.isPresent()) {
			if (player.getInventory().getAmount(item.get().getItem().getId())
					+ item.get().getItem().getAmount() > Integer.MAX_VALUE
					|| player.getInventory().getAmount(item.get().getItem().getId())
					+ item.get().getItem().getAmount() <= 0) {
				int playerCanHold = Integer.MAX_VALUE
						- player.getInventory().getAmount(item.get().getItem().getId());
				if (playerCanHold <= 0) {
					player.getPacketSender().sendMessage("You cannot hold more of that item.");
					return;
				} else {
					int currentAmount = item.get().getItem().getAmount();
					item.get().setOldAmount(currentAmount);
					item.get().getItem().decrementAmountBy(playerCanHold);
					ItemOnGroundManager.perform(item.get(), OperationType.ALTER);
					deregister = false;
				}
			}
			if (deregister) {
				ItemOnGroundManager.deregister(item.get());
			}
			player.getInventory().add(item.get().getItem());
			SoundManager.sendSound(player, Sound.PICK_UP_ITEM);
			player.getLastItemPickup().reset();
		}
	}
}
