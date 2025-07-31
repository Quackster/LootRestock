/**
 * LootRestock is a Fabric mod that tracks and resets lootable chests
 * after a configurable amount of time has passed since last looted.
 *
 * Features:
 * <ul>
 *   <li>Tracks interactions with chests (and optionally barrels) that use loot tables</li>
 *   <li>Automatically resets their loot after a configurable cooldown</li>
 *   <li>Optionally only resets when the chest is empty</li>
 *   <li>Removes chests from tracking if the world is deleted or the block is no longer a lootable container</li>
 * </ul>
 *
 * Configuration:
 * <ul>
 *   <li><b>reset_time_value</b>: Number (e.g., 7)</li>
 *   <li><b>reset_time_unit</b>: Time unit (seconds, minutes, hours, days)</li>
 *   <li><b>only_reset_when_empty</b>: true/false (default: true)</li>
 *   <li><b>include_barrels</b>: true/false (default: false)</li>
 * </ul>
 *
 * The config file is located at: {@code lootrestock.properties}
 * Tracked data is saved to: {@code chest_reset_data.json}
 */
package org.oldskooler.lootrestock;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.minecraft.block.BlockState;
import net.minecraft.block.ChestBlock;
import net.minecraft.block.BarrelBlock;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.entity.LootableContainerBlockEntity;
import net.minecraft.entity.vehicle.ChestMinecartEntity;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Identifier;
import net.minecraft.util.WorldSavePath;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.ChunkSectionPos;
import net.minecraft.world.World;
import org.oldskooler.lootrestock.util.LootChestHelper;
import org.oldskooler.lootrestock.util.LootEntityHelper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * The main mod initializer for LootRestock.
 * Tracks chests that are looted and resets them after a configurable number of days.
 */
public class LootRestock implements ModInitializer {
	public static final String MOD_ID = "lootrestock";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	private static final String CONFIG_FILE_NAME = "lootrestock.properties";
	private static final String CONFIG_TIME_VALUE_KEY = "reset_time_value";
	private static final String CONFIG_TIME_UNIT_KEY = "reset_time_unit";
	private static final String CONFIG_ONLY_RESET_WHEN_EMPTY_KEY = "only_reset_when_empty";
	private static final String CONFIG_INCLUDE_BARRELS_KEY = "include_barrels";

	private static final long DEFAULT_RESET_TIME_VALUE = 7;
	private static final String DEFAULT_RESET_TIME_UNIT = "days";
	private static final boolean DEFAULT_ONLY_RESET_WHEN_EMPTY = true;
	private static final boolean DEFAULT_INCLUDE_BARRELS = false;
	private static final boolean DEBUG_CHEST_LOOT = true;

	private boolean includeBarrels = DEFAULT_INCLUDE_BARRELS;
	private boolean onlyResetWhenEmpty = DEFAULT_ONLY_RESET_WHEN_EMPTY;

	private long resetTimeMs;
	private long tickCounter = 0;

	private Map<String, ChestData> trackedChests = new HashMap<>();
	private MinecraftServer server;
	private Path dataFile;

	@Override
	public void onInitialize() {
		LOGGER.info("Initializing LootRestock");

		loadConfig();

		// Register block interaction callback
		UseBlockCallback.EVENT.register((player, world, hand, hitResult) -> {
			if (!world.isClient() && hitResult.getType() == HitResult.Type.BLOCK) {
				BlockPos pos = hitResult.getBlockPos();
				BlockState state = world.getBlockState(pos);

				if (state.getBlock() instanceof ChestBlock || (includeBarrels && state.getBlock() instanceof BarrelBlock)) {
					BlockEntity blockEntity = world.getBlockEntity(pos);
					if (blockEntity instanceof LootableContainerBlockEntity) {
						handleChestInteraction(world, pos, (LootableContainerBlockEntity) blockEntity);
					}
				}
			}

			return ActionResult.PASS;
		});

		UseEntityCallback.EVENT.register((player, world, hand, entity, hitResult) -> {
			if (!world.isClient() && entity instanceof ChestMinecartEntity chestMinecart) {
				handleMinecartChestInteraction(world, chestMinecart);
			}
			return ActionResult.PASS;
		});

		ServerLifecycleEvents.SERVER_STARTED.register(this::onServerStart);
		ServerLifecycleEvents.SERVER_STOPPING.register(this::onServerStop);

		// Schedule periodic reset task
		ServerTickEvents.END_SERVER_TICK.register(server -> {
			tickCounter++;
			if (tickCounter >= 20) { // 20 ticks = 1 second
				tickCounter = 0;
				checkAndResetChests(); // call your method
			}
		});

	}

	/**
	 * Loads the configuration file and calculates the reset interval in milliseconds.
	 */
	private void loadConfig() {
		Properties config = new Properties();
		Path configPath = Path.of(CONFIG_FILE_NAME);

		long timeValue = DEFAULT_RESET_TIME_VALUE;
		String timeUnit = DEFAULT_RESET_TIME_UNIT;

		try {
			if (Files.exists(configPath)) {
				try (InputStream in = Files.newInputStream(configPath)) {
					config.load(in);
					timeValue = Long.parseLong(config.getProperty(CONFIG_TIME_VALUE_KEY, String.valueOf(DEFAULT_RESET_TIME_VALUE)));
					timeUnit = config.getProperty(CONFIG_TIME_UNIT_KEY, DEFAULT_RESET_TIME_UNIT).toLowerCase();
					onlyResetWhenEmpty = Boolean.parseBoolean(
							config.getProperty(CONFIG_ONLY_RESET_WHEN_EMPTY_KEY, String.valueOf(DEFAULT_ONLY_RESET_WHEN_EMPTY))
					);
					includeBarrels = Boolean.parseBoolean(
							config.getProperty(CONFIG_INCLUDE_BARRELS_KEY, String.valueOf(DEFAULT_INCLUDE_BARRELS))
					);

					LOGGER.info("'" + CONFIG_TIME_VALUE_KEY + "' = {}", timeValue);
					LOGGER.info("'" + CONFIG_TIME_UNIT_KEY + "' = {}", timeUnit);
					LOGGER.info("'" + CONFIG_ONLY_RESET_WHEN_EMPTY_KEY + "' = {}", onlyResetWhenEmpty);
					LOGGER.info("'" + CONFIG_INCLUDE_BARRELS_KEY + "' = {}", includeBarrels);

					if (timeValue <= 0) {
						throw new IllegalArgumentException(CONFIG_TIME_VALUE_KEY + " must be greater than 0");
					}
				}
			} else {
				config.setProperty(CONFIG_TIME_VALUE_KEY, String.valueOf(DEFAULT_RESET_TIME_VALUE));
				config.setProperty(CONFIG_TIME_UNIT_KEY, DEFAULT_RESET_TIME_UNIT);
				config.setProperty(CONFIG_ONLY_RESET_WHEN_EMPTY_KEY, String.valueOf(DEFAULT_ONLY_RESET_WHEN_EMPTY));
				config.setProperty(CONFIG_INCLUDE_BARRELS_KEY, String.valueOf(DEFAULT_INCLUDE_BARRELS));
				Files.createFile(configPath);
				try (OutputStream out = Files.newOutputStream(configPath)) {
					config.store(out, "LootRestock Configuration");
				}
			}
		} catch (IOException | NumberFormatException e) {
			LOGGER.warn("Failed to load or parse config. Using defaults: {} {}", DEFAULT_RESET_TIME_VALUE, DEFAULT_RESET_TIME_UNIT);
			timeValue = DEFAULT_RESET_TIME_VALUE;
			timeUnit = DEFAULT_RESET_TIME_UNIT;
		}

		// Convert time to milliseconds
		switch (timeUnit) {
			case "seconds":
				resetTimeMs = timeValue * 1000L;
				break;
			case "minutes":
				resetTimeMs = timeValue * 60 * 1000L;
				break;
			case "hours":
				resetTimeMs = timeValue * 60 * 60 * 1000L;
				break;
			case "days":
				resetTimeMs = timeValue * 24 * 60 * 60 * 1000L;
				break;
			default:
				timeUnit = "days";
				LOGGER.warn("Unrecognized time unit '{}'. Defaulting to days.", timeUnit);
				resetTimeMs = timeValue * 24 * 60 * 60 * 1000L;
				break;
		}

		LOGGER.info("Chest reset time set to {} {} ({} ms)", timeValue, timeUnit, resetTimeMs);
	}

	/**
	 * Initializes data on server start.
	 */
	private void onServerStart(MinecraftServer server) {
		this.server = server;
		this.dataFile = server.getSavePath(WorldSavePath.ROOT).resolve("chest_reset_data.json");
		loadChestData();
		LOGGER.info("LootRestock mod loaded {} tracked chests", trackedChests.size());
	}

	/**
	 * Cleans up resources and saves data on server shutdown.
	 */
	private void onServerStop(MinecraftServer server) {
		LOGGER.info("Saving {} tracked chests", trackedChests.size());
		saveChestData();
	}

	/**
	 * Handles interaction with a block-based chest (e.g., normal chest or barrel).
	 * If the block has a loot table, it is added to the tracking map, and its state is recorded.
	 *
	 * @param world the world the chest is in
	 * @param pos the block position of the chest
	 * @param chest the LootableContainerBlockEntity being interacted with
	 */
	private void handleChestInteraction(World world, BlockPos pos, LootableContainerBlockEntity chest) {
		String chestKey = getChestKey(world, pos);

		if (LootChestHelper.getLootTableId(chest) != null) {
			ChestData data = trackedChests.computeIfAbsent(chestKey, k -> {
				ChestData newData = new ChestData();
				newData.lastLootedTime = System.currentTimeMillis(); // Fix: initialize to current time
				return newData;
			});
			data.worldName = world.getRegistryKey().getValue().toString();
			data.x = pos.getX();
			data.y = pos.getY();
			data.z = pos.getZ();
			data.lootTableId = LootChestHelper.getLootTableId(chest).toString(); //chest.getLootTable().getValue().toString();
			data.lootSeed = LootChestHelper.getLootTableSeed(chest); //chest.getLootTableSeed();
			data.isEmpty = chest.isEmpty();
			data.dirty = true;
		}
	}

	/**
	 * Handles interaction with a Chest Minecart entity.
	 * <p>
	 * If the entity has a loot table, it is added to the tracked chest map
	 * and its current state is recorded. The reset timer starts from the time
	 * of interaction.
	 *
	 * @param world the world the chest minecart is in
	 * @param chest the ChestMinecartEntity being interacted with
	 */
	private void handleMinecartChestInteraction(World world, ChestMinecartEntity chest) {
		String chestKey = world.getRegistryKey().getValue() + ":entity:" + chest.getUuidAsString();

		if (LootEntityHelper.getLootTableId(chest) != null) {
			ChestData data = trackedChests.computeIfAbsent(chestKey, k -> {
				ChestData newData = new ChestData();
				newData.lastLootedTime = System.currentTimeMillis();
				return newData;
			});

			data.worldName = world.getRegistryKey().getValue().toString();
			data.entityUuid = chest.getUuidAsString();
			data.lootTableId = LootEntityHelper.getLootTableId(chest).toString(); // chest.getLootTable().getValue().toString();
			data.lootSeed = LootEntityHelper.getLootTableSeed(chest); // chest.getLootTableSeed();
			data.x = chest.getBlockPos().getX();
			data.y = chest.getBlockPos().getY();
			data.z = chest.getBlockPos().getZ();
			data.isEmpty = chest.isEmpty();
			data.dirty = true;
		}
	}

	/**
	 * Iterates through all tracked chests and resets those whose cooldown period has elapsed.
	 * <p>
	 * Supports both block-based and entity-based chests. Only chests that are empty
	 * (if configured) and have been inactive longer than the reset interval are reset.
	 * Invalid or missing chests are automatically removed from tracking.
	 */
	private void checkAndResetChests() {
		if (server == null) return;

		long currentTime = System.currentTimeMillis();
		int resetCount = 0;
		boolean needsSave = false;

		Iterator<Map.Entry<String, ChestData>> iterator = trackedChests.entrySet().iterator();

		while (iterator.hasNext()) {
			Map.Entry<String, ChestData> entry = iterator.next();
			ChestData data = entry.getValue();
			BlockPos pos = data.getBlockPos();
			ServerWorld world = data.getWorld(server);

			if (world == null) {
				LOGGER.info("Removing chest from tracking: world '{}' no longer exists", data.worldName);
				iterator.remove();
				needsSave = true;
				continue;
			}

			if (!world.isChunkLoaded(
					ChunkSectionPos.getSectionCoord(pos.getX()),
					ChunkSectionPos.getSectionCoord(pos.getZ()))) {
				LOGGER.debug("Chunk not loaded for chest at {}. Skipping reset.", pos);
				continue;
			}

			if (data.isEntityChest()) {
				ChestMinecartEntity entity = getMinecartChestByUuid(world, data.entityUuid, data.getBlockPos());
				if (entity == null) {
					LOGGER.info("Removing tracked entity chest: not found {}", data.entityUuid);
					iterator.remove();
					needsSave = true;
					continue;
				}

				if ((!onlyResetWhenEmpty || entity.isEmpty()) &&
						(currentTime - data.lastLootedTime) >= resetTimeMs) {
					if (resetChestEntity(entity, data)) {
						resetCount++;
						data.isEmpty = entity.isEmpty();
						data.lastLootedTime = currentTime;
						data.dirty = true;
					}
				}

			} else {
				BlockEntity blockEntity = world.getBlockEntity(pos);
				if (!(blockEntity instanceof LootableContainerBlockEntity chest)) {
					LOGGER.info("Removing chest from tracking: block at {} is no longer a lootable container", pos);
					iterator.remove();
					needsSave = true;
					continue;
				}

				if ((!onlyResetWhenEmpty || chest.isEmpty()) &&
						(currentTime - data.lastLootedTime) >= resetTimeMs) {
					if (resetChest(data)) {
						resetCount++;
						data.isEmpty = chest.isEmpty();
						data.lastLootedTime = currentTime;
						data.dirty = true;
					}
				}
			}

			if (data.dirty) {
				needsSave = true;
			}
		}

		if (needsSave) {
			saveChestData();
			for (ChestData data : trackedChests.values()) {
				data.dirty = false;
			}
		}

		if (resetCount > 0) {
			LOGGER.info("Reset {} chests", resetCount);
		}
	}

	/**
	 * Attempts to locate a ChestMinecartEntity in the given world by its UUID.
	 * Searches within a bounding box around the given block position to limit the area.
	 *
	 * @param world the server world to search in
	 * @param uuidStr the UUID string of the chest minecart to find
	 * @param pos the position around which to search
	 * @return the matching ChestMinecartEntity, or {@code null} if not found or UUID is invalid
	 */

	private ChestMinecartEntity getMinecartChestByUuid(ServerWorld world, String uuidStr, BlockPos pos) {
		try {
			int distanceMax = 2;
			Box myBox = new Box(pos).expand(distanceMax);

			UUID uuid = UUID.fromString(uuidStr);
			return world.getEntitiesByClass(ChestMinecartEntity.class, myBox, entity -> entity.getUuid().equals(uuid))
					.stream().findFirst().orElse(null);
		} catch (IllegalArgumentException e) {
			return null;
		}
	}

	/**
	 * Resets the contents of a chest minecart using its stored loot table.
	 * This clears its inventory and regenerates loot with a new random seed.
	 *
	 * @param chest the ChestMinecartEntity to reset
	 * @param data the stored chest data, including loot table info
	 * @return {@code true} if the chest was successfully reset, {@code false} otherwise
	 */
	private boolean resetChestEntity(ChestMinecartEntity chest, ChestData data) {
		try {
			chest.clear();
			chest.setLootTable(data.getLootTableIdentifier(), chest.getWorld().getRandom().nextLong()); //RegistryKey.of(RegistryKeys.LOOT_TABLE, data.getLootTableIdentifier()), chest.getWorld().getRandom().nextLong());
			chest.generateInventoryLoot(null);
			chest.markDirty();

			LOGGER.info("Reset chest minecart at {} in world {}", chest.getBlockPos(), data.worldName);
			return true;
		} catch (Exception e) {
			LOGGER.error("Failed to reset chest minecart: {}", e.getMessage());
			return false;
		}
	}

	/**
	 * Attempts to reset the contents of a chest using its stored loot table,
	 * but only if the chest is located in a currently loaded chunk.
	 * <p>
	 * This method checks the chest's world and chunk status before accessing
	 * the block entity. If the chunk is not loaded, the reset is skipped to avoid
	 * unnecessary chunk loading and potential performance issues.
	 *
	 * @param data The tracked chest data containing world, position, and loot info.
	 * @return {@code true} if the chest was successfully reset; {@code false} otherwise.
	 */
	private boolean resetChest(ChestData data) {
		try {
			ServerWorld world = data.getWorld(server);

			if (world == null) {
				LOGGER.warn("Could not find world: {}", data.worldName);
				return false;
			}

			BlockPos pos = data.getBlockPos();
			BlockEntity blockEntity = world.getBlockEntity(pos);

			if (blockEntity instanceof LootableContainerBlockEntity chest) {
				chest.clear();
				chest.setLootTable(data.getLootTableIdentifier(), world.getRandom().nextLong());
				chest.checkLootInteraction(null);
				chest.markDirty();

				LOGGER.info("Reset chest at {} in world {}", pos, data.worldName);
				return true;
			}
		} catch (Exception e) {
			LOGGER.error("Failed to reset chest: {}", e.getMessage());
		}
		return false;
	}

	/**
	 * Creates a unique key for a chest in the world.
	 */
	private String getChestKey(World world, BlockPos pos) {
		return world.getRegistryKey().getValue().toString() + ":" + pos.toShortString();
	}

	/**
	 * Loads previously tracked chest data from disk.
	 */
	private void loadChestData() {
		if (!Files.exists(dataFile)) return;

		try (Reader reader = Files.newBufferedReader(dataFile)) {
			Gson gson = new Gson();
			trackedChests = gson.fromJson(reader, new TypeToken<Map<String, ChestData>>(){}.getType());
			if (trackedChests == null) {
				trackedChests = new HashMap<>();
			}
		} catch (Exception e) {
			LOGGER.error("Failed to load chest data from JSON: {}", e.getMessage());
			trackedChests = new HashMap<>();
		}
	}

	/**
	 * Saves current tracked chest data to disk.
	 */
	private void saveChestData() {
		try {
			Files.createDirectories(dataFile.getParent());
			Gson gson = new GsonBuilder().setPrettyPrinting().create();
			try (Writer writer = Files.newBufferedWriter(dataFile)) {
				gson.toJson(trackedChests, writer);
			}
		} catch (IOException e) {
			LOGGER.error("Failed to save chest data to JSON: {}", e.getMessage());
		}
	}

	/**
	 * Serializable class for tracking chest state.
	 */
	private static class ChestData {
		// Block-based chest fields
		String worldName;
		int x, y, z;

		// Entity-based chest fields
		String entityUuid; // Add this field for minecart chests

		String lootTableId;
		long lootSeed;
		long lastLootedTime;
		boolean isEmpty;

		transient boolean dirty = false;

		public BlockPos getBlockPos() {
			return new BlockPos(x, y, z);
		}

		public Identifier getLootTableIdentifier() {
			return new Identifier(lootTableId);
		}

		public ServerWorld getWorld(MinecraftServer server) {
			Identifier worldId = new Identifier(worldName);
			for (ServerWorld serverWorld : server.getWorlds()) {
				if (serverWorld.getRegistryKey().getValue().equals(worldId)) {
					return serverWorld;
				}
			}
			return null;
		}

		public boolean isEntityChest() {
			return entityUuid != null;
		}
	}

}
