package com.openrsc.server.model.world;

import com.openrsc.server.Server;
import com.openrsc.server.avatargenerator.AvatarGenerator;
import com.openrsc.server.constants.*;
import com.openrsc.server.content.DropTable;
import com.openrsc.server.content.clan.ClanManager;
import com.openrsc.server.content.market.Market;
import com.openrsc.server.content.minigame.fishingtrawler.FishingTrawler;
import com.openrsc.server.content.minigame.fishingtrawler.FishingTrawler.TrawlerBoat;
import com.openrsc.server.content.party.PartyManager;
import com.openrsc.server.database.impl.mysql.queries.logging.LoginLog;
import com.openrsc.server.database.impl.mysql.queries.player.login.PlayerOnlineFlagQuery;
import com.openrsc.server.event.SingleEvent;
import com.openrsc.server.external.GameObjectLoc;
import com.openrsc.server.external.NPCLoc;
import com.openrsc.server.io.WorldLoader;
import com.openrsc.server.model.GlobalMessage;
import com.openrsc.server.model.Point;
import com.openrsc.server.model.Shop;
import com.openrsc.server.model.entity.GameObject;
import com.openrsc.server.model.entity.GroundItem;
import com.openrsc.server.model.entity.npc.Npc;
import com.openrsc.server.model.entity.player.Player;
import com.openrsc.server.model.snapshot.Snapshot;
import com.openrsc.server.model.world.region.RegionManager;
import com.openrsc.server.model.world.region.TileValue;
import com.openrsc.server.net.rsc.ActionSender;
import com.openrsc.server.plugins.MiniGameInterface;
import com.openrsc.server.plugins.QuestInterface;
import com.openrsc.server.util.EntityList;
import com.openrsc.server.util.IPTracker;
import com.openrsc.server.util.SimpleSubscriber;
import com.openrsc.server.util.ThreadSafeIPTracker;
import com.openrsc.server.util.rsc.CollisionFlag;
import com.openrsc.server.util.rsc.MessageType;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

public final class World implements SimpleSubscriber<FishingTrawler> {

	/**
	 * The asynchronous logger.
	 */
	private static final Logger LOGGER = LogManager.getLogger();

	/**
	 * Avatar generator upon logout save to PNG.
	 */
	private final AvatarGenerator avatarGenerator;

	/**
	 * IP filtering for wilderness entry
	 */
	private final IPTracker<String> wildernessIPTracker;

	private boolean telegrabEnabled = true;

	public boolean EVENT = false;
	public int EVENT_X = -1, EVENT_Y = -1;
	public int EVENT_COMBAT_MIN, EVENT_COMBAT_MAX;
	public int membersWildStart = 48;
	public int membersWildMax = 56;
	public int godSpellsStart = 1;
	public int godSpellsMax = 5;

	private static World worldInstance;

	private final RegionManager regionManager;
	private final EntityList<Npc> npcs;
	private HashMap<String, ArrayList<Npc>> npcPositions;
	private final EntityList<Player> players;
	private final List<QuestInterface> quests;
	private final List<MiniGameInterface> minigames;
	private final List<Shop> shopData;
	private final List<Shop> shops;
	private final ConcurrentMap<TrawlerBoat, FishingTrawler> fishingTrawler;
	private final TileValue[][] tiles;
	private final PartyManager partyManager;
	private final ClanManager clanManager;
	private final Market market;
	private final WorldLoader worldLoader;

	private ConcurrentMap<Player, Boolean> playerUnderAttackMap;
	private ConcurrentMap<Npc, Boolean> npcUnderAttackMap;

	private Queue<GlobalMessage> globalMessageQueue = new LinkedList<GlobalMessage>();

	public NpcDrops npcDrops;

	/**
	 * Double ended queue to store snapshots into
	 */
	private Deque<Snapshot> snapshots;

	private final Server server;

	public World(Server server) {
		this.server = server;
		npcs = new EntityList<Npc>(4000);
		npcPositions = new HashMap<>();
		npcDrops = new NpcDrops(this);
		players = new EntityList<Player>(2000);
		quests = Collections.synchronizedList( new LinkedList<QuestInterface>() );
		minigames = Collections.synchronizedList( new LinkedList<MiniGameInterface>() );
		shopData = Collections.synchronizedList( new ArrayList<Shop>() );
		shops = Collections.synchronizedList( new ArrayList<Shop>() );
		wildernessIPTracker = new ThreadSafeIPTracker<String>();
		playerUnderAttackMap = new ConcurrentHashMap<Player, Boolean>();
		npcUnderAttackMap = new ConcurrentHashMap<Npc, Boolean>();
		fishingTrawler = new ConcurrentHashMap<TrawlerBoat, FishingTrawler>();
		snapshots = new LinkedList<Snapshot>();
		tiles = new TileValue[Constants.MAX_WIDTH][Constants.MAX_HEIGHT];
		avatarGenerator = new AvatarGenerator(this);
		worldLoader = new WorldLoader(this);
		regionManager = new RegionManager(this);
		clanManager = new ClanManager(this);
		partyManager = new PartyManager(this);
		market = getServer().getConfig().SPAWN_AUCTION_NPCS ? new Market(this) : null;
	}

	private void shutdownCheck() {
		getServer().getGameEventHandler().add(new SingleEvent(this, null, 1000, "Shutdown Check") {
			public void action() {
				int currSecond = (int) (System.currentTimeMillis() / 1000.0 - (4 * 3600));
				if (getServer().getConfig().AUTO_SERVER_RESTART) {
					if ((int) ((currSecond / 3600.0) % 24) == getServer().getConfig().RESTART_HOUR
						&& (int) ((currSecond / 60.0) % 60) >= getServer().getConfig().RESTART_MINUTE) {
						int seconds = getServer().getConfig().RESTART_DELAY;
						int minutes = seconds / 60;
						int remainder = seconds % 60;
						if (getServer().restart(seconds)) {
							for (Player player : getPlayers()) {
								ActionSender.startShutdown(player, seconds);
							}
						}
					}
				}
				if (getServer().getConfig().AUTO_SERVER_RESTART_2) {
					if ((int) ((currSecond / 3600.0) % 24) == getServer().getConfig().RESTART_HOUR_2
						&& (int) ((currSecond / 60.0) % 60) >= getServer().getConfig().RESTART_MINUTE_2) {
						int seconds = getServer().getConfig().RESTART_DELAY_2;
						int minutes = seconds / 60;
						int remainder = seconds % 60;
						if (getServer().restart(seconds)) {
							for (Player player : getWorld().getPlayers()) {
								ActionSender.startShutdown(player, seconds);
							}
						}
					}
				}
				shutdownCheck();
			}
		});
	}

	public void restartCommand() {
		getServer().getGameEventHandler().add(new SingleEvent(this, null, 1000, "Restart Command") {
			public void action() {
				int currSecond = (int) (System.currentTimeMillis() / 1000.0 - (4 * 3600));
				int seconds = 10;
				int minutes = seconds / 60;
				int remainder = seconds % 60;
				if (getServer().restart(seconds)) {
					for (Player player : getPlayers()) {
						ActionSender.startShutdown(player, seconds);
					}
				}
				restartCommand();
			}
		});
	}

	/**
	 * Returns double-ended queue for snapshots.
	 */
	public synchronized Deque<Snapshot> getSnapshots() {
		return snapshots;
	}

	/**
	 * Add entry to snapshots
	 */
	public void addEntryToSnapshots(Snapshot snapshot) {
		getSnapshots().offerFirst(snapshot);
	}

	public void addShopData(Shop... shop) {
		shopData.addAll(Arrays.asList(shop));
	}

	public void clearShopData() {
		shopData.clear();
	}

	public int countNpcs() {
		return getNpcs().size();
	}

	public int countPlayers() {
		return getPlayers().size();
	}

	public void delayedRemoveObject(final GameObject object, final int delay) {
		getServer().getGameEventHandler().add(new SingleEvent(this, null, delay, "Delayed Remove Object") {
			public void action() {
				unregisterGameObject(object);
			}
		});
	}

	/**
	 * Adds a DelayedEvent that will spawn a GameObject
	 */
	public void delayedSpawnObject(final GameObjectLoc loc, final int respawnTime, final boolean forceFullBlock) {
		getServer().getGameEventHandler().add(new SingleEvent(this, null, respawnTime, "Delayed Spawn Object") {
			public void action() {
				registerGameObject(new GameObject(getWorld(), loc));
				if (forceFullBlock) {
					getTile(loc.getX(), loc.getY()).traversalMask |= 64;
				}
			}
		});
	}

	public void delayedSpawnObject(final GameObjectLoc loc, final int respawnTime) {
		this.delayedSpawnObject(loc, respawnTime, false);
	}

	public Npc getNpc(int idx) {
		try {
			return getNpcs().get(idx);
		} catch (Exception e) {
			return null;
		}
	}

	public Npc getNpc(int id, int minX, int maxX, int minY, int maxY) {
		for (Npc npc : getNpcs()) {
			if (npc.getID() == id && npc.getX() >= minX && npc.getX() <= maxX && npc.getY() >= minY
				&& npc.getY() <= maxY) {
				return npc;
			}
		}
		return null;
	}

	public Npc getNpc(int id, int minX, int maxX, int minY, int maxY, boolean notNull) {
		for (Npc npc : getNpcs()) {
			if (npc.getID() == id && npc.getX() >= minX && npc.getX() <= maxX && npc.getY() >= minY
				&& npc.getY() <= maxY) {
				if (!npc.inCombat()) {
					return npc;
				}
			}
		}
		return null;
	}

	public Npc getNpcById(int id) {
		for (Npc npc : getNpcs()) {
			if (npc.getID() == id) {
				return npc;
			}
		}
		return null;
	}

	public Npc getNpcByUUID(String id) {
		for (Npc npc : getNpcs()) {
			if (npc.getUUID() == id) {
				return npc;
			}
		}
		return null;
	}

	/**
	 * Gets the list of npcs on the server
	 */
	public EntityList<Npc> getNpcs() {
		return npcs;
	}

	/**
	 * Gets a Player by their server index
	 */
	public Player getPlayer(int idx) {
		try {
			Player player = getPlayers().get(idx);
			return player;
		} catch (Exception e) {
			return null;
		}

	}

	/**
	 * Gets a player by their username hash
	 */
	public Player getPlayer(long usernameHash) {
		for (Player player : getPlayers()) {
			if (player.getUsernameHash() == usernameHash) {
				return player;
			}
		}
		return null;
	}

	/**
	 * Gets a player by their ID
	 */
	public Player getPlayerID(int databaseID) {
		for (Player player : getPlayers()) {
			if (player.getDatabaseID() == databaseID) {
				return player;
			}
		}
		return null;
	}

	/**
	 * Gets a player by their UUID
	 */
	public Player getPlayerUUID(String uuid) {
		for (Player player : getPlayers()) {
			if (player.getUUID().equals(uuid)) {
				return player;
			}
		}
		return null;
	}

	public EntityList<Player> getPlayers() {
		return players;
	}

	/**
	 * Finds a specific quest by ID
	 *
	 * @param q
	 * @return
	 * @throws IllegalArgumentException when a quest by that ID isn't found
	 */
	public QuestInterface getQuest(int q) throws IllegalArgumentException {
		for (QuestInterface quest : this.getQuests()) {
			if (quest.getQuestId() == q) {
				return quest;
			}
		}
		throw new IllegalArgumentException("No quest found");
	}

	/**
	 * Finds a specific miniquest/minigame by ID
	 *
	 * @param m
	 * @return
	 * @throws IllegalArgumentException when a quest by that ID isn't found
	 */
	public MiniGameInterface getMiniGame(int m) throws IllegalArgumentException {
		for (MiniGameInterface minigame : getMiniGames()) {
			if (minigame.getMiniGameId() == m) {
				return minigame;
			}
		}
		throw new IllegalArgumentException("No mini-game found");
	}

	public List<QuestInterface> getQuests() {
		return quests;
	}

	public List<MiniGameInterface> getMiniGames() {
		return minigames;
	}

	public synchronized List<Shop> getShops() {
		return shops;
	}

	public synchronized TileValue getTile(int x, int y) {
		if (!withinWorld(x, y)) {
			return null;
		}
		TileValue t = tiles[x][y];
		if (t == null) {
			t = new TileValue();
			tiles[x][y] = t;
		}
		return t;
	}

	public synchronized TileValue getTile(Point point) {
		return getTile(point.getX(), point.getY());
	}

	public boolean hasNpc(Npc n) {
		return getNpcs().contains(n);
	}
	/*
	 * Note to self - Remove CollidingWallObject, Remove getWallGameObject, And others if this doesn't work in long run.
	 * Classes - viewArea, world, region, gameObjectAction, GameObjectWallAction, ItemUseOnObject
	 */

	public boolean hasPlayer(Player player) {
		return getPlayers().contains(player);
	}

	public boolean isLoggedIn(long usernameHash) {
		Player friend = getPlayer(usernameHash);
		if (friend != null) {
			return friend.loggedIn();
		}
		return false;
	}

	public void load() {
		try {
			getClanManager().initialize();
			getPartyManager().initialize();

			if(getMarket() != null) {
				getMarket().start();
			}

			getWorldLoader().loadWorld();
			getWorldLoader().getWorldPopulator().populateWorld();
			shutdownCheck();
			//AchievementSystem.loadAchievements();
			// getWorld().getServer().getEventHandler().add(new WildernessCycleEvent());
			//setFishingTrawler(new FishingTrawler());
			//getWorld().getServer().getEventHandler().add(getFishingTrawler());
			if(getMarket() != null) {
				market.start();
			}
		} catch (Exception e) {
			LOGGER.catching(e);
		}
	}

	public void registerGameObject(GameObject o) {
		Point objectCoordinates = Point.location(o.getLoc().getX(), o.getLoc().getY());
		GameObject collidingGameObject = getRegionManager().getRegion(objectCoordinates).getGameObject(objectCoordinates, null);
		GameObject collidingWallObject = getRegionManager().getRegion(objectCoordinates).getWallGameObject(objectCoordinates, o.getLoc().getDirection(), null);
		if (collidingGameObject != null && o.getType() == 0) {
			unregisterGameObject(collidingGameObject);
		}
		if (collidingWallObject != null && o.getType() == 1) {
			unregisterGameObject(collidingWallObject);
		}
		o.setLocation(Point.location(o.getLoc().getX(), o.getLoc().getY()));

		int dir = o.getDirection();
		if (o.getID() == 1147) {
			return;
		}
		switch (o.getType()) {
			case 0:
				if (o.getGameObjectDef().getType() != 1 && o.getGameObjectDef().getType() != 2) {
					return;
				}
				int width, height;
				if (dir == 0 || dir == 4) {
					width = o.getGameObjectDef().getWidth();
					height = o.getGameObjectDef().getHeight();
				} else {
					height = o.getGameObjectDef().getWidth();
					width = o.getGameObjectDef().getHeight();
				}
				for (int x = o.getX(); x < o.getX() + width; ++x) {
					for (int y = o.getY(); y < o.getY() + height; ++y) {
						if (isProjectileClipAllowed(o)) {
							handleProjectileClipAllowance(x, y, dir, o.getType(), o.getGameObjectDef().getType(), -1);
						}
						if (o.getGameObjectDef().getType() == 1) {
							getTile(x, y).traversalMask |= CollisionFlag.FULL_BLOCK_C;
						} else if (dir == 0) {
							getTile(x, y).traversalMask |= CollisionFlag.WALL_EAST;
							if (getTile(x - 1, y) != null)
								getTile(x - 1, y).traversalMask |= CollisionFlag.WALL_WEST;
						} else if (dir == 2) {
							getTile(x, y).traversalMask |= CollisionFlag.WALL_SOUTH;
							if (getTile(x, y + 1) != null)
								getTile(x, y + 1).traversalMask |= CollisionFlag.WALL_NORTH;
						} else if (dir == 4) {
							getTile(x, y).traversalMask |= CollisionFlag.WALL_WEST;
							if (getTile(x + 1, y) != null)
								getTile(x + 1, y).traversalMask |= CollisionFlag.WALL_EAST;
						} else if (dir == 6) {
							getTile(x, y).traversalMask |= CollisionFlag.WALL_NORTH;
							if (getTile(x, y - 1) != null)
								getTile(x, y - 1).traversalMask |= CollisionFlag.WALL_SOUTH;
						}
					}
				}
				break;

			case 1:
				if (o.getDoorDef().getDoorType() != 1) {
					return;
				}
				int x = o.getX(), y = o.getY();
				if (isProjectileClipAllowed(o)) {
					handleProjectileClipAllowance(x, y, dir, o.getType(), -1, o.getDoorDef().getDoorType());
				}
				if (dir == 0) {

					getTile(x, y).traversalMask |= CollisionFlag.WALL_NORTH;
					if (getTile(x, y - 1) != null)
						getTile(x, y - 1).traversalMask |= CollisionFlag.WALL_SOUTH;
				} else if (dir == 1) {
					getTile(x, y).traversalMask |= CollisionFlag.WALL_EAST;
					if (getTile(x - 1, y) != null)
						getTile(x - 1, y).traversalMask |= CollisionFlag.WALL_WEST;
				} else if (dir == 2) {
					getTile(x, y).traversalMask |= CollisionFlag.FULL_BLOCK_A;
				} else if (dir == 3) {
					getTile(x, y).traversalMask |= CollisionFlag.FULL_BLOCK_B;
				}
				break;
		}
	}

	private boolean isProjectileClipAllowed(GameObject o) {
		for (String s : com.openrsc.server.constants.Constants.objectsProjectileClipAllowed) {
			if (o.getType() == 0) {
				// there are many of the objects that need to
				// have clip enabled.
				if (!o.getGameObjectDef().getName().equalsIgnoreCase("tree")) {
					if (o.getGameObjectDef().getHeight() == 1 && o.getGameObjectDef().getWidth() == 1 && !o.getGameObjectDef().getName().toLowerCase().equalsIgnoreCase("chest"))
						return true;
				}
				if (o.getGameObjectDef().getName().toLowerCase().equalsIgnoreCase(s)) {
					return true;
				}
			} else if (o.getType() == 1) {
				if (o.getDoorDef().getName().toLowerCase().equalsIgnoreCase(s)) {
					return true;
				}
			}
		}
		return false;
	}

	private void handleProjectileClipAllowance(int x, int y, int dir, int type, int objectType, int doorType) {

		// Always give the current tile a clip mask.
		getTile(x, y).projectileAllowed = true;

		if ((type == 0 && objectType == 1) || (type == 1 && doorType != 1)) return;

		if (dir == 0 && getTile(x - 1, y) != null) {
			getTile(x - 1, y).projectileAllowed = true;
		}

		else if (dir == 2 && getTile(x, y + 1) != null) {
			getTile(x, y + 1).projectileAllowed = true;
		}

		else if (dir == 4 && getTile(x + 1, y) != null) {
			getTile(x + 1, y).projectileAllowed = true;
		}

		else if (dir == 6 && getTile(x, y - 1) != null) {
			getTile(x, y - 1).projectileAllowed = true;
		}
	}

	public void registerItem(final GroundItem i) {
		registerItem(i, i.getConfig().GAME_TICK * 200);
	}

	public void registerItem(final GroundItem i, int delayTime) {
		try {
			if (i.getLoc() == null) {
				getServer().getGameEventHandler().add(new SingleEvent(this, null, delayTime, "Register Item") {
					public void action() {
						unregisterItem(i);
					}
				});
			}
		} catch (Exception e) {
			i.remove();
			LOGGER.catching(e);
		}
	}

	public Npc registerNpc(Npc n) {
		NPCLoc npc = n.getLoc();
		if (npc.startX < npc.minX || npc.startX > npc.maxX || npc.startY < npc.minY || npc.startY > npc.maxY
			|| (getTile(npc.startX, npc.startY).overlay & 64) != 0) {
			LOGGER.error("Broken Npc: <id>" + npc.id + "</id><startX>" + npc.startX + "</startX><startY>"
				+ npc.startY + "</startY>");
		}

		getNpcs().add(n);
		setNpcPosition(n);
		return n;
	}

	public void registerObjects(GameObject... obs) {
		for (GameObject o : obs) {
			o.setLocation(Point.location(o.getLoc().getX(), o.getLoc().getY()));
		}
	}

	public boolean registerPlayer(Player player) {
		if (!getPlayers().contains(player)) {
			player.setUUID(UUID.randomUUID().toString());

			player.setBusy(false);

			getPlayers().add(player);
			player.updateRegion();
			getServer().getGameLogger().run(new PlayerOnlineFlagQuery(getServer(), player.getDatabaseID(), player.getCurrentIP(), true));
			getServer().getGameLogger().addQuery(new LoginLog(player.getWorld(), player.getDatabaseID(), player.getCurrentIP()));
			for (Player other : getPlayers()) {
				other.getSocial().alertOfLogin(player);
			}
			getClanManager().checkAndAttachToClan(player);
			getPartyManager().checkAndAttachToParty(player);

			if (player.getCache().hasKey("skull_remaining") && (player.getCache().getLong("skull_remaining") > 0)) {
				player.addSkull(player.getCache().getLong("skull_remaining"));
				player.setSkullTimer(player.getCache().getLong("skull_remaining"));
			}

			if (player.getCache().hasKey("charge_remaining") && (player.getCache().getLong("charge_remaining") > 0)) {
				player.addCharge(player.getCache().getLong("charge_remaining"));
				player.setChargeTimer(player.getCache().getLong("charge_remaining"));
			}

			return true;
		}
		return false;
	}

	public void registerQuest(QuestInterface quest) {
		if (quest.getQuestName() == null) {
			throw new IllegalArgumentException("Quest name cannot be null");
		} else if (quest.getQuestName().length() > 40) {
			throw new IllegalArgumentException("Quest name cannot be longer then 40 characters");
		}
		for (QuestInterface q : getQuests()) {
			if (q.getQuestId() == quest.getQuestId()) {
				throw new IllegalArgumentException("Quest ID must be unique");
			}
		}

		if (!getServer().getConfig().WANT_CUSTOM_QUESTS
		&& quest.getQuestId() > Quests.LEGENDS_QUEST)
			return;

		getQuests().add(quest);
	}

	public void registerMiniGame(MiniGameInterface minigame) {
		if (minigame.getMiniGameName() == null) {
			throw new IllegalArgumentException("Minigame name cannot be null");
		} else if (minigame.getMiniGameName().length() > 40) {
			throw new IllegalArgumentException("Minigame name cannot be longer then 40 characters");
		}
		for (MiniGameInterface m : getMiniGames()) {
			if (m.getMiniGameId() == minigame.getMiniGameId()) {
				System.out.println(minigame.getMiniGameId());
				throw new IllegalArgumentException("MiniGame ID must be unique");
			}
		}
		getMiniGames().add(minigame);
	}

	public void registerShop(Shop shop) {
		getShops().add(shop);
	}

	public void registerShops(Shop... shop) {
		getShops().addAll(Arrays.asList(shop));
	}

	public void replaceGameObject(GameObject old, GameObject _new) {
		unregisterGameObject(old);
		registerGameObject(_new);
	}

	public void sendKilledUpdate(long killedHash, long killerHash, int type) {
		for (final Player player : getPlayers())
			ActionSender.sendKillUpdate(player, killedHash, killerHash, type);
	}

	public void sendModAnnouncement(String string) {
		for (Player player : getPlayers()) {
			if (player.isMod()) {
				player.message("[@cya@SERVER@whi@]: " + string);
			}
		}
	}

	public void sendWorldAnnouncement(String msg) {
		if (getServer().getConfig().WANT_GLOBAL_CHAT) {
			for (Player player : getPlayers()) {
				player.playerServerMessage(MessageType.QUEST, "@gre@[Global] @whi@" + msg);
			}
		}
	}

	public void sendWorldMessage(String msg) {
		for (Player player : getPlayers()) {
			player.playerServerMessage(MessageType.QUEST, msg);
		}
	}

	/**
	 * Removes an object from the server
	 */
	public void unregisterGameObject(GameObject o) {
		o.remove();
		int dir = o.getDirection();
		switch (o.getType()) {
			case 0:
				if (o.getGameObjectDef().getType() != 1 && o.getGameObjectDef().getType() != 2) {
					return;
				}
				int width, height;
				if (dir == 0 || dir == 4) {
					width = o.getGameObjectDef().getWidth();
					height = o.getGameObjectDef().getHeight();
				} else {
					height = o.getGameObjectDef().getWidth();
					width = o.getGameObjectDef().getHeight();
				}
				for (int x = o.getX(); x < o.getX() + width; ++x) {
					for (int y = o.getY(); y < o.getY() + height; ++y) {
						if (o.getGameObjectDef().getType() == 1) {
							getTile(x, y).traversalMask &= 0xffbf;
						} else if (dir == 0) {
							getTile(x, y).traversalMask &= 0xfffd;
							getTile(x - 1, y).traversalMask &= 65535 - 8;
						} else if (dir == 2) {
							getTile(x, y).traversalMask &= 0xfffb;
							getTile(x, y + 1).traversalMask &= 65535 - 1;
						} else if (dir == 4) {
							getTile(x, y).traversalMask &= 0xfff7;
							getTile(x + 1, y).traversalMask &= 65535 - 2;
						} else if (dir == 6) {
							getTile(x, y).traversalMask &= 0xfffe;
							getTile(x, y - 1).traversalMask &= 65535 - 4;
						}
					}
				}
				break;
			case 1:
				if (o.getDoorDef().getDoorType() != 1) {
					return;
				}
				int x = o.getX(), y = o.getY();
				if (dir == 0) {
					getTile(x, y).traversalMask &= 0xfffe;
					getTile(x, y - 1).traversalMask &= 65535 - 4;
				} else if (dir == 1) {
					getTile(x, y).traversalMask &= 0xfffd;
					getTile(x - 1, y).traversalMask &= 65535 - 8;
				} else if (dir == 2) {
					getTile(x, y).traversalMask &= 0xffef;
				} else if (dir == 3) {
					getTile(x, y).traversalMask &= 0xffdf;
				}
				break;
		}
	}

	public GlobalMessage getNextGlobalMessage() {
		return globalMessageQueue.poll();
	}

	public void addGlobalMessage(GlobalMessage privateMessage) {
		getGlobalMessageQueue().add(privateMessage);
	}

	/**
	 * Removes an item from the server
	 */
	public void unregisterItem(GroundItem i) {
		i.remove();
	}

	/**
	 * Removes an npc from the server
	 */
	public void unregisterNpc(Npc n) {
		if (hasNpc(n)) {
			getNpcs().remove(n);
		}
		n.superRemove();
	}

	/**
	 * Removes a player from the server and saves their account
	 */
	public void unregisterPlayer(final Player player) {
		try {
			if (getServer().getLoginExecutor() != null) {
				getServer().getGameLogger().addQuery(new PlayerOnlineFlagQuery(getServer(), player.getDatabaseID(), false));
				if (getServer().getConfig().AVATAR_GENERATOR)
					avatarGenerator.generateAvatar(player.getDatabaseID(), player.getSettings().getAppearance(), player.getWornItems());
			}
			player.logout();
			LOGGER.info("Unregistered " + player.getUsername() + " from player list.");
		} catch (Exception e) {
			LOGGER.catching(e);
		}

	}

	public void unregisterQuest(QuestInterface quest) {
		if (getQuests().contains(quest)) {
			getQuests().remove(quest);
		}
	}

	public void unregisterMiniGame(MiniGameInterface minigame) {
		if (getMiniGames().contains(minigame)) {
			getMiniGames().remove(minigame);
		}
	}

	/**
	 * Are the given coords within the world boundaries
	 */
	public boolean withinWorld(int x, int y) {
		return x >= 0 && x < Constants.MAX_WIDTH && y >= 0 && y < Constants.MAX_HEIGHT;
	}

	public FishingTrawler getFishingTrawler(TrawlerBoat boat) {
		FishingTrawler trawlerInstance = fishingTrawler.get(boat);
		if (trawlerInstance != null && !trawlerInstance.shouldRemove()) {
			return trawlerInstance;
		} else {
			trawlerInstance = new FishingTrawler(this, boat);
			trawlerInstance.register(this);
			fishingTrawler.put(boat, trawlerInstance);
			getServer().getGameEventHandler().add(trawlerInstance);
			return trawlerInstance;
		}
	}

	public FishingTrawler getFishingTrawler(Player player) {
		if (fishingTrawler.get(TrawlerBoat.EAST) != null && fishingTrawler.get(TrawlerBoat.EAST).getPlayers().contains(player)) {
			return fishingTrawler.get(TrawlerBoat.EAST);
		}
		if (fishingTrawler.get(TrawlerBoat.WEST) != null && fishingTrawler.get(TrawlerBoat.WEST).getPlayers().contains(player)) {
			return fishingTrawler.get(TrawlerBoat.WEST);
		}
		return null;
	}

	// notified when event is stopped to deallocate reference
	@Override
	public void update(FishingTrawler ctx) {
		if (ctx != null && ctx.getPlayers().size() == 0) {
			fishingTrawler.put(ctx.getBoat(), null);
		}
	}

	public void produceUnderAttack(Player player) {
		getPlayersUnderAttack().put(player, true);
	}

	public void produceUnderAttack(Npc n) {
		getNpcsUnderAttack().put(n, true);
	}

	public boolean checkUnderAttack(Player player) {
		return getPlayersUnderAttack().getOrDefault(player, false);
	}

	public boolean checkUnderAttack(Npc n) {
		return getNpcsUnderAttack().getOrDefault(n, false);
	}

	public void releaseUnderAttack(Player player) {
		if (getPlayersUnderAttack().containsKey(player)) {
			getPlayersUnderAttack().remove(player);
		}
	}

	public void releaseUnderAttack(Npc n) {
		if (getNpcsUnderAttack().containsKey(n)) {
			getNpcsUnderAttack().remove(n);
		}
	}

	public Map<Player, Boolean> getPlayersUnderAttack() {
		return playerUnderAttackMap;
	}

	public Map<Npc, Boolean> getNpcsUnderAttack() {
		return npcUnderAttackMap;
	}

	public synchronized WorldLoader getWorldLoader() {
		return worldLoader;
	}

	public final Server getServer() {
		return server;
	}

	public final IPTracker<String> getWildernessIPTracker() {
		return wildernessIPTracker;
	}

	public synchronized RegionManager getRegionManager() {
		return regionManager;
	}

	public synchronized Market getMarket() {
		return market;
	}

	public synchronized PartyManager getPartyManager() {
		return partyManager;
	}

	public synchronized ClanManager getClanManager() {
		return clanManager;
	}

	public boolean isTelegrabEnabled() {
		return telegrabEnabled;
	}

	public Queue<GlobalMessage> getGlobalMessageQueue() {
		return globalMessageQueue;
	}

	public HashMap<String, ArrayList<Npc>> getNpcPositions() {
		return npcPositions;
	}

	public void setNpcPosition(Npc n) {
		String key = n.getX() + "," + n.getY();
		npcPositions.putIfAbsent(key, new ArrayList<>());
		npcPositions.get(key).add(n);
	}

	public void removeNpcPosition(Npc n) {
		String key = n.getX() + "," + n.getY();
		if (npcPositions.containsKey(key)) {
			ArrayList<Npc> ar = npcPositions.get(key);
			if (ar.size() > 1) {
				for (int i = 0; i < ar.size(); i++) {
					if (n.getUUID().equals(ar.get(i).getUUID())) {
						ar.remove(i);
						break;
					}
				}
			}
			else {
				npcPositions.remove(key);
			}
		}
	}
}
