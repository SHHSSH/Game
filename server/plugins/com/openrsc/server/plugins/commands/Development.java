package com.openrsc.server.plugins.commands;

import com.openrsc.server.constants.NpcDrops;
import com.openrsc.server.content.DropTable;
import com.openrsc.server.database.GameDatabaseException;
import com.openrsc.server.model.Point;
import com.openrsc.server.model.container.Item;
import com.openrsc.server.model.entity.GameObject;
import com.openrsc.server.model.entity.npc.Npc;
import com.openrsc.server.model.entity.player.Player;
import com.openrsc.server.model.world.region.TileValue;
import com.openrsc.server.net.rsc.ActionSender;
import com.openrsc.server.plugins.triggers.CommandTrigger;
import com.openrsc.server.util.rsc.DataConversions;
import gnu.trove.impl.sync.TSynchronizedShortByteMap;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;

import static com.openrsc.server.plugins.Functions.*;

public final class Development implements CommandTrigger {
	private static final Logger LOGGER = LogManager.getLogger(Development.class);

	public static String messagePrefix = null;
	public static String badSyntaxPrefix = null;

	public boolean blockCommand(Player player, String cmd, String[] args) {
		return player.isDev();
	}

	/**
	 * Template for ::dev commands
	 * Development usable commands in general
	 */
	@Override
	public void onCommand(Player player, String cmd, String[] args) {
		if(messagePrefix == null) {
			messagePrefix = config().MESSAGE_PREFIX;
		}
		if(badSyntaxPrefix == null) {
			badSyntaxPrefix = config().BAD_SYNTAX_PREFIX;
		}

		if (cmd.equalsIgnoreCase("radiusnpc") || cmd.equalsIgnoreCase("createnpc") || cmd.equalsIgnoreCase("cnpc")|| cmd.equalsIgnoreCase("cpc")) {
			if (args.length < 2 || args.length == 3) {
				player.message(badSyntaxPrefix + cmd.toUpperCase() + " [id] [radius] (x) (y)");
				return;
			}

			int id = -1;
			try {
				id = Integer.parseInt(args[0]);
			}
			catch(NumberFormatException ex) {
				player.message(badSyntaxPrefix + cmd.toUpperCase() + " [id] [radius] (x) (y)");
				return;
			}

			int radius = -1;
			try {
				radius = Integer.parseInt(args[1]);
			} catch (NumberFormatException ex) {
				player.message(badSyntaxPrefix + cmd.toUpperCase() + " [id] [radius] (x) (y)");
				return;
			}

			int x = -1;
			int y = -1;
			if(args.length >= 4) {
				try {
					x = Integer.parseInt(args[2]);
					y = Integer.parseInt(args[3]);
				} catch (NumberFormatException ex) {
					player.message(badSyntaxPrefix + cmd.toUpperCase() + " [id] [radius] (x) (y)");
					return;
				}
			}
			else {
				x = player.getX();
				y = player.getY();
			}

			if(!player.getWorld().withinWorld(x, y))
			{
				player.message(messagePrefix + "Invalid coordinates");
				return;
			}

			Point npcLoc = new Point(x,y);
			final Npc n = new Npc(player.getWorld(), id, x, y, x - radius, x + radius, y - radius, y + radius);

			if (player.getWorld().getServer().getEntityHandler().getNpcDef(id) == null) {
				player.message(messagePrefix + "Invalid npc id");
				return;
			}

			try {
				player.getWorld().getServer().getDatabase().addNpcSpawn(n.getLoc());
			} catch (final GameDatabaseException ex) {
				LOGGER.catching(ex);
				player.message("Database Error! " + ex.getMessage());
				return;
			}

			player.getWorld().registerNpc(n);
			n.setShouldRespawn(true);
			player.message(messagePrefix + "Added NPC to database: " + n.getDef().getName() + " at " + npcLoc + " with radius " + radius);
		}
		else if (cmd.equalsIgnoreCase("rpc") || cmd.equalsIgnoreCase("rnpc") || cmd.equalsIgnoreCase("removenpc")){
			if (args.length < 1) {
				player.message(badSyntaxPrefix + cmd.toUpperCase() + " [npc_instance_id]");
				return;
			}

			int id = -1;
			try {
				id = Integer.parseInt(args[0]);
			}
			catch(NumberFormatException ex) {
				player.message(badSyntaxPrefix + cmd.toUpperCase() + " [npc_instance_id]");
				return;
			}

			Npc npc = player.getWorld().getNpc(id);

			if(npc == null) {
				player.message(messagePrefix + "Invalid npc instance id");
				return;
			}

			try {
				player.getWorld().getServer().getDatabase().removeNpcSpawn(npc.getLoc());
			} catch (final GameDatabaseException ex) {
				LOGGER.catching(ex);
				player.message("Database Error! " + ex.getMessage());
				return;
			}

			player.message(messagePrefix + "Removed NPC from database: " + npc.getDef().getName() + " with instance ID " + id);
			player.getWorld().unregisterNpc(npc);
		}
		else if (cmd.equalsIgnoreCase("removeobject") || cmd.equalsIgnoreCase("robject")) {
			if(args.length == 1) {
				player.message(badSyntaxPrefix + cmd.toUpperCase() + " (x) (y)");
				return;
			}

			int x = -1;
			if(args.length >= 1) {
				try {
					x = Integer.parseInt(args[0]);
				} catch (NumberFormatException ex) {
					player.message(badSyntaxPrefix + cmd.toUpperCase() + " (x) (y)");
					return;
				}
			} else {
				x = player.getX();
			}

			int y = -1;
			if(args.length >=2) {
				try {
					y = Integer.parseInt(args[1]);
				} catch (NumberFormatException ex) {
					player.message(badSyntaxPrefix + cmd.toUpperCase() + " (x) (y)");
					return;
				}
			} else {
				y = player.getY();
			}

			if(!player.getWorld().withinWorld(x, y))
			{
				player.message(messagePrefix + "Invalid coordinates");
				return;
			}

			final Point objectLocation = Point.location(x, y);
			final GameObject object = player.getViewArea().getGameObject(objectLocation);

			if(object == null)
			{
				player.message(messagePrefix + "There is no object at coordinates " + objectLocation);
				return;
			}

			try {
				player.getWorld().getServer().getDatabase().removeObjectSpawn(object.getLoc());
			} catch (final GameDatabaseException ex) {
				LOGGER.catching(ex);
				player.message("Database Error! " + ex.getMessage());
				return;
			}

			player.message(messagePrefix + "Removed object from database: " + object.getGameObjectDef().getName() + " with instance ID " + object.getID());
			player.getWorld().unregisterGameObject(object);
		}
		else if (cmd.equalsIgnoreCase("createobject") || cmd.equalsIgnoreCase("cobject") || cmd.equalsIgnoreCase("addobject") || cmd.equalsIgnoreCase("aobject")) {
			if (args.length < 1 || args.length == 2) {
				player.message(badSyntaxPrefix + cmd.toUpperCase() + " [id] (x) (y)");
				return;
			}

			int id = -1;
			try {
				id = Integer.parseInt(args[0]);
			}
			catch(NumberFormatException ex) {
				player.message(badSyntaxPrefix + cmd.toUpperCase() + " [id] (x) (y)");
				return;
			}

			int x = -1;
			int y = -1;
			if(args.length >= 3) {
				try {
					x = Integer.parseInt(args[1]);
					y = Integer.parseInt(args[2]);
				} catch (NumberFormatException ex) {
					player.message(badSyntaxPrefix + cmd.toUpperCase() + " [id] (x) (y)");
					return;
				}
			}
			else {
				x = player.getX();
				y = player.getY();
			}

			if(!player.getWorld().withinWorld(x, y))
			{
				player.message(messagePrefix + "Invalid coordinates");
				return;
			}

			Point objectLoc = Point.location(x, y);
			final GameObject object = player.getViewArea().getGameObject(objectLoc);

			if (object != null && object.getType() != 1) {
				player.message("There is already an object in that spot: " + object.getGameObjectDef().getName());
				return;
			}

			if (player.getWorld().getServer().getEntityHandler().getGameObjectDef(id) == null) {
				player.message(messagePrefix + "Invalid object id");
				return;
			}

			final GameObject newObject = new GameObject(player.getWorld(), Point.location(x, y), id, 0, 0);

			try {
				player.getWorld().getServer().getDatabase().addObjectSpawn(newObject.getLoc());
			} catch (final GameDatabaseException ex) {
				LOGGER.catching(ex);
				player.message("Database Error! " + ex.getMessage());
				return;
			}

			player.getWorld().registerGameObject(newObject);
			player.message(messagePrefix + "Added object to database: " + newObject.getGameObjectDef().getName() + " with instance ID " + newObject.getID() + " at " + newObject.getLocation());
		}
		else if (cmd.equalsIgnoreCase("rotateobject")) {
			if(args.length == 1) {
				player.message(badSyntaxPrefix + cmd.toUpperCase() + " (x) (y) (direction)");
				return;
			}

			int x = -1;
			if(args.length >= 1) {
				try {
					x = Integer.parseInt(args[0]);
				} catch (NumberFormatException ex) {
					player.message(badSyntaxPrefix + cmd.toUpperCase() + " (x) (y) (direction)");
					return;
				}
			} else {
				x = player.getX();
			}

			int y = -1;
			if(args.length >= 2) {
				try {
					y = Integer.parseInt(args[1]);
				} catch (NumberFormatException ex) {
					player.message(badSyntaxPrefix + cmd.toUpperCase() + " (x) (y) (direction)");
					return;
				}
			} else {
				y = player.getY();
			}


			if(!player.getWorld().withinWorld(x, y))
			{
				player.message(messagePrefix + "Invalid coordinates");
				return;
			}

			final Point objectLocation = Point.location(x, y);
			final GameObject object = player.getViewArea().getGameObject(objectLocation);

			if(object == null)
			{
				player.message(messagePrefix + "There is no object at coordinates " + objectLocation);
				return;
			}

			int direction = -1;
			if(args.length >= 3) {
				try {
					direction = Integer.parseInt(args[2]);
				} catch (NumberFormatException ex) {
					player.message(badSyntaxPrefix + cmd.toUpperCase() + " (x) (y) (direction)");
					return;
				}
			} else {
				direction = object.getDirection() + 1;
			}

			if (direction >= 8) {
				direction = 0;
			}
			if(direction < 0) {
				direction = 8;
			}

			try {
				player.getWorld().getServer().getDatabase().removeObjectSpawn(object.getLoc());
			} catch (final GameDatabaseException ex) {
				LOGGER.catching(ex);
				player.message("Database Error! " + ex.getMessage());
				return;
			}
			player.getWorld().unregisterGameObject(object);

			GameObject newObject = new GameObject(player.getWorld(), Point.location(x, y), object.getID(), direction, object.getType());
			player.getWorld().registerGameObject(newObject);

			try {
				player.getWorld().getServer().getDatabase().addObjectSpawn(newObject.getLoc());
			} catch (final GameDatabaseException ex) {
				LOGGER.catching(ex);
				player.message("Database Error! " + ex.getMessage());
				return;
			}

			player.message(messagePrefix + "Rotated object in database: " + newObject.getGameObjectDef().getName() + " to rotation " + newObject.getDirection() + " with instance ID " + newObject.getID() + " at " + newObject.getLocation());
		}
		else if (cmd.equalsIgnoreCase("tile")) {
			TileValue tv = player.getWorld().getTile(player.getLocation());
			player.message(messagePrefix + "traversal: " + tv.traversalMask + ", vertVal:" + (tv.verticalWallVal & 0xff) + ", horiz: "
				+ (tv.horizontalWallVal & 0xff) + ", diagVal: " + (tv.diagWallVal & 0xff) + ", projectile: " + tv.projectileAllowed);
		}
		else if (cmd.equalsIgnoreCase("debugregion")) {
			boolean debugPlayers ;
			if(args.length >= 1) {
				try {
					debugPlayers = DataConversions.parseBoolean(args[0]);
				} catch (NumberFormatException e) {
					player.message(badSyntaxPrefix + cmd.toUpperCase() + " (debug_players) (debug_npcs) (debug_items) (debug_objects)");
					return;
				}
			} else {
				debugPlayers = true;
			}

			boolean debugNpcs ;
			if(args.length >= 2) {
				try {
					debugNpcs = DataConversions.parseBoolean(args[1]);
				} catch (NumberFormatException e) {
					player.message(badSyntaxPrefix + cmd.toUpperCase() + " (debug_players) (debug_npcs) (debug_items) (debug_objects)");
					return;
				}
			} else {
				debugNpcs = true;
			}

			boolean debugItems ;
			if(args.length >= 3) {
				try {
					debugItems = DataConversions.parseBoolean(args[2]);
				} catch (NumberFormatException e) {
					player.message(badSyntaxPrefix + cmd.toUpperCase() + " (debug_players) (debug_npcs) (debug_items) (debug_objects)");
					return;
				}
			} else {
				debugItems = true;
			}

			boolean debugObjects ;
			if(args.length >= 1) {
				try {
					debugObjects = DataConversions.parseBoolean(args[3]);
				} catch (NumberFormatException e) {
					player.message(badSyntaxPrefix + cmd.toUpperCase() + " (debug_players) (debug_npcs) (debug_items) (debug_objects)");
					return;
				}
			} else {
				debugObjects = true;
			}

			ActionSender.sendBox(player, player.getRegion().toString(debugPlayers, debugNpcs, debugItems, debugObjects)
				.replaceAll("\n", "%"), true);
		}
		else if (cmd.equalsIgnoreCase("coords")) {
			Player targetPlayer = args.length > 0 ?
				player.getWorld().getPlayer(DataConversions.usernameToHash(args[0])) :
				player;

			if(targetPlayer != null)
				player.message(messagePrefix + targetPlayer.getStaffName() + " is at: " + targetPlayer.getLocation());
			else
				player.message(messagePrefix + "Invalid name or player is not online");
		}
		else if (cmd.equalsIgnoreCase("serverstats")) {
			ActionSender.sendBox(player, player.getWorld().getServer().getGameEventHandler().buildProfilingDebugInformation(true),true);
		}
		else if (cmd.equalsIgnoreCase("debugdroptables")) {
			new NpcDrops(player.getWorld()).debugDropTables();
		}
		else if (cmd.equalsIgnoreCase("droptest")) {
			if (args.length < 1) {
				mes("::droptest [npc_id]  or  ::droptest [npc_id] [count]");
				return;
			}
			int npcId = Integer.parseInt(args[0]);
			int count = 1;
			if (args.length > 1) {
				count = Integer.parseInt(args[1]);
			}
			NpcDrops npcDrops = new NpcDrops(player.getWorld());
			DropTable dropTable = npcDrops.getDropTable(npcId);
			HashMap<Integer, Integer> droppedAmount = new HashMap<>();
			HashMap<Integer, Integer> droppedCount = new HashMap<>();
			for (int i = 0; i < count; i++) {
				Item item = dropTable.rollItem(false, player);
				if (item == null) item = new Item(-1, 0);
				droppedAmount.put(item.getCatalogId(), droppedAmount.getOrDefault(item.getCatalogId(), 0) + item.getAmount());
				droppedCount.put(item.getCatalogId(), droppedCount.getOrDefault(item.getCatalogId(), 0) + 1);
			}
			System.out.println("Dropped counts:");
			droppedCount.entrySet().forEach(entry-> {
				String key = "NOTHING";
				Item i = new Item(entry.getKey());
				if (i.getCatalogId() > -1) {
					key = i.getDef(player.getWorld()).getName();
				}
				System.out.println(key + ": " + entry.getValue());
			});
		}
	}
}
