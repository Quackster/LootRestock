/**
 * LootRefresh is a Fabric mod that tracks and resets lootable chests
 * after a configurable amount of time has passed since last looted.
 * <p>
 * Configuration:
 * <ul>
 *   <li><b>reset_time_value</b>: Number (e.g., 7)</li>
 *   <li><b>reset_time_unit</b>: Time unit (seconds, minutes, hours, days)</li>
 * </ul>
 * The config file is located at: lootrefresh.properties
 */
package org.oldskooler.lootrefresh;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.block.BlockState;
import net.minecraft.block.ChestBlock;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.entity.LootableContainerBlockEntity;
import net.minecraft.loot.LootTable;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Identifier;
import net.minecraft.util.WorldSavePath;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkSectionPos;
import net.minecraft.world.World;
import net.minecraft.world.chunk.Chunk;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * The main mod initializer for LootRefresh.
 * Tracks chests that are looted and resets them after a configurable number of days.
 */
public class LootRefresh implements ModInitializer {
	public static final String MOD_ID = "lootrefresh";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	private static final String CONFIG_FILE_NAME = "lootrefresh.properties";
	private static final String CONFIG_TIME_VALUE_KEY = "reset_time_value";
	private static final String CONFIG_TIME_UNIT_KEY = "reset_time_unit";
	private static final String CONFIG_ONLY_RESET_WHEN_EMPTY_KEY = "only_reset_when_empty";

	private static final long DEFAULT_RESET_TIME_VALUE = 7;
	private static final String DEFAULT_RESET_TIME_UNIT = "days";
	private static final boolean DEFAULT_ONLY_RESET_WHEN_EMPTY = true;

	private boolean onlyResetWhenEmpty = DEFAULT_ONLY_RESET_WHEN_EMPTY;

	private long resetTimeMs;

	private Map<String, ChestData> trackedChests = new HashMap<>();
	private ScheduledExecutorService scheduler;
	private MinecraftServer server;
	private Path dataFile;

	@Override
	public void onInitialize() {
		LOGGER.info("Initializing Loot Refresh mod");

		loadConfig();

		scheduler = Executors.newScheduledThreadPool(1);

		// Register block interaction callback
		UseBlockCallback.EVENT.register((player, world, hand, hitResult) -> {
			if (!world.isClient() && hitResult.getType() == net.minecraft.util.hit.HitResult.Type.BLOCK) {
				BlockPos pos = hitResult.getBlockPos();
				BlockState state = world.getBlockState(pos);

				if (state.getBlock() instanceof ChestBlock) {
					BlockEntity blockEntity = world.getBlockEntity(pos);
					if (blockEntity instanceof LootableContainerBlockEntity) {
						handleChestInteraction(world, pos, (LootableContainerBlockEntity) blockEntity);
					}
				}
			}
			return ActionResult.PASS;
		});

		ServerLifecycleEvents.SERVER_STARTED.register(this::onServerStart);
		ServerLifecycleEvents.SERVER_STOPPING.register(this::onServerStop);

		// Schedule periodic reset task
		// scheduler.scheduleAtFixedRate(this::checkAndResetChests, 1, 1, TimeUnit.HOURS);
		scheduler.scheduleAtFixedRate(this::checkAndResetChests, 1, 1, TimeUnit.SECONDS);
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

                    LOGGER.info("'" + CONFIG_TIME_VALUE_KEY + "' = {}", timeValue);
                    LOGGER.info("'" + CONFIG_TIME_UNIT_KEY + "' = {}", timeUnit);
					LOGGER.info("'" + CONFIG_ONLY_RESET_WHEN_EMPTY_KEY + "' = {}", onlyResetWhenEmpty);

					if (timeValue <= 0) {
						throw new IllegalArgumentException(CONFIG_TIME_VALUE_KEY + " must be greater than 0");
					}
				}
			} else {
				config.setProperty(CONFIG_TIME_VALUE_KEY, String.valueOf(DEFAULT_RESET_TIME_VALUE));
				config.setProperty(CONFIG_TIME_UNIT_KEY, DEFAULT_RESET_TIME_UNIT);
				config.setProperty(CONFIG_ONLY_RESET_WHEN_EMPTY_KEY, String.valueOf(DEFAULT_ONLY_RESET_WHEN_EMPTY));
				Files.createFile(configPath);
				try (OutputStream out = Files.newOutputStream(configPath)) {
					config.store(out, "LootRefresh Configuration");
				}
			}
		} catch (IOException | NumberFormatException e) {
			LOGGER.warn("Failed to load or parse config. Using defaults: {} {}", DEFAULT_RESET_TIME_VALUE, DEFAULT_RESET_TIME_UNIT);
			timeValue = DEFAULT_RESET_TIME_VALUE;
			timeUnit = DEFAULT_RESET_TIME_UNIT;
		}

		// Convert time to milliseconds
		resetTimeMs = switch (timeUnit) {
			case "seconds" -> timeValue * 1000L;
			case "minutes" -> timeValue * 60 * 1000L;
			case "hours"   -> timeValue * 60 * 60 * 1000L;
			case "days"    -> timeValue * 24 * 60 * 60 * 1000L;
			default -> {
				LOGGER.warn("Unrecognized time unit '{}'. Defaulting to days.", timeUnit);
				yield timeValue * 24 * 60 * 60 * 1000L;
			}
		};

		LOGGER.info("Chest reset time set to {} {} ({} ms)", timeValue, timeUnit, resetTimeMs);
	}

	/**
	 * Initializes data on server start.
	 */
	private void onServerStart(MinecraftServer server) {
		this.server = server;
		this.dataFile = server.getSavePath(WorldSavePath.ROOT).resolve("chest_reset_data.dat");
		loadChestData();
		LOGGER.info("LootRefresh mod loaded {} tracked chests", trackedChests.size());
	}

	/**
	 * Cleans up resources and saves data on server shutdown.
	 */
	private void onServerStop(MinecraftServer server) {
		saveChestData();
		if (scheduler != null && !scheduler.isShutdown()) {
			scheduler.shutdown();
		}
	}

	/**
	 * Handles when a player opens or interacts with a chest.
	 */
	private void handleChestInteraction(World world, BlockPos pos, LootableContainerBlockEntity chest) {
		String chestKey = getChestKey(world, pos);

		if (chest.getLootTable() != null/* && !chest.isEmpty()*/) {
			ChestData data = trackedChests.computeIfAbsent(chestKey, k -> new ChestData());
			data.worldName = world.getRegistryKey().getValue().toString();
			data.pos = pos.toImmutable();
			data.lootTable = chest.getLootTable().getValue();
			data.lootSeed = chest.getLootTableSeed();
			data.lastLootedTime = System.currentTimeMillis();
			data.isEmpty = chest.isEmpty();
			data.dirty = true;
			
			LOGGER.debug("Tracking chest at {} with loot table {}", pos, data.lootTable);
		}/* else if (trackedChests.containsKey(chestKey)) {
			ChestData data = trackedChests.get(chestKey);
			data.isEmpty = true;
			data.lastLootedTime = System.currentTimeMillis();
		}*/
	}

	/**
	 * Checks all tracked chests and resets ones that have passed the cooldown period.
	 */
	private void checkAndResetChests() {
		if (server == null) return;

		long currentTime = System.currentTimeMillis();
		int resetCount = 0;
		boolean needsSave = false;

		for (Map.Entry<String, ChestData> entry : trackedChests.entrySet()) {
			ChestData data = entry.getValue();

			// Reset logic
			if ((!onlyResetWhenEmpty || data.isEmpty) &&
					(currentTime - data.lastLootedTime) >= resetTimeMs) {
				if (resetChest(data)) {
					resetCount++;
					data.isEmpty = false;
					data.lastLootedTime = currentTime;
					data.dirty = true;
				}
			}

			// Check if we need to save due to new/modified data
			if (data.dirty) {
				needsSave = true;
			}
		}

		if (needsSave) {
			saveChestData();
			// Clear dirty flags
			for (ChestData data : trackedChests.values()) {
				data.dirty = false;
			}
		}

		if (resetCount > 0) {
			LOGGER.info("Reset {} chests", resetCount);
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
			Identifier worldId = Identifier.of(data.worldName);
			ServerWorld world = null;

			for (ServerWorld serverWorld : server.getWorlds()) {
				if (serverWorld.getRegistryKey().getValue().equals(worldId)) {
					world = serverWorld;
					break;
				}
			}

			if (world == null) {
				LOGGER.warn("Could not find world: {}", data.worldName);
				return false;
			}

			// Don't reset if chunk is not loaded
			if (!world.isChunkLoaded(
					ChunkSectionPos.getSectionCoord(data.pos.getX()),
					ChunkSectionPos.getSectionCoord(data.pos.getY()))) {
				LOGGER.debug("Chunk not loaded for chest at {}. Skipping reset.", data.pos);
				return false;
			}

			BlockEntity blockEntity = world.getBlockEntity(data.pos);
			if (blockEntity instanceof LootableContainerBlockEntity chest) {
				chest.clear();
				chest.setLootTable(RegistryKey.of(RegistryKeys.LOOT_TABLE, data.lootTable), world.getRandom().nextLong());
				chest.markDirty();

				LOGGER.debug("Reset chest at {} in world {}", data.pos, data.worldName);
				return true;
			}
		} catch (Exception e) {
			LOGGER.error("Failed to reset chest at {}: {}", data.pos, e.getMessage());
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

		try (ObjectInputStream ois = new ObjectInputStream(Files.newInputStream(dataFile))) {
			trackedChests = (Map<String, ChestData>) ois.readObject();
		} catch (Exception e) {
			LOGGER.error("Failed to load chest data: {}", e.getMessage());
			trackedChests = new HashMap<>();
		}
	}

	/**
	 * Saves current tracked chest data to disk.
	 */
	private void saveChestData() {
		try {
			Files.createDirectories(dataFile.getParent());
			try (ObjectOutputStream oos = new ObjectOutputStream(Files.newOutputStream(dataFile))) {
				oos.writeObject(trackedChests);
			}
		} catch (Exception e) {
			LOGGER.error("Failed to save chest data: {}", e.getMessage());
		}
	}

	/**
	 * Serializable class for tracking chest state.
	 */
	private static class ChestData implements Serializable {
		private static final long serialVersionUID = 1L;

		String worldName;
		BlockPos pos;
		Identifier lootTable;
		long lootSeed;
		long lastLootedTime;
		boolean isEmpty;

		// Transient means this field won't be serialized
		transient boolean dirty = false;
	}
}
