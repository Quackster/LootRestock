/**
 * LootRefresh is a Fabric mod that tracks and resets lootable chests
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
 * The config file is located at: {@code lootrefresh.properties}
 * Tracked data is saved to: {@code chest_reset_data.dat}
 */
package org.oldskooler.lootrefresh;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.block.BlockState;
import net.minecraft.block.ChestBlock;
import net.minecraft.block.BarrelBlock;
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
import java.util.Iterator;
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
    private static final String CONFIG_INCLUDE_BARRELS_KEY = "include_barrels";

    private static final long DEFAULT_RESET_TIME_VALUE = 7;
    private static final String DEFAULT_RESET_TIME_UNIT = "days";
    private static final boolean DEFAULT_ONLY_RESET_WHEN_EMPTY = true;
    private static final boolean DEFAULT_INCLUDE_BARRELS = false;
    private static final boolean DEBUG_CHEST_LOOT = true;

    private boolean includeBarrels = DEFAULT_INCLUDE_BARRELS;
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

                if (state.getBlock() instanceof ChestBlock || (includeBarrels && state.getBlock() instanceof BarrelBlock)) {
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
                    config.store(out, "LootRefresh Configuration");
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

        if (chest.getLootTable() != null) {
            ChestData data = trackedChests.computeIfAbsent(chestKey, k -> new ChestData());
            data.worldName = world.getRegistryKey().getValue().toString();
            data.x = pos.getX();
            data.y = pos.getY();
            data.z = pos.getZ();
            data.lootTableId = chest.getLootTable().getValue().toString();
            data.lootSeed = chest.getLootTableSeed();
            data.lastLootedTime = System.currentTimeMillis();
            data.isEmpty = chest.isEmpty();
            data.dirty = true;

            LOGGER.debug("Tracking chest at {} with loot table {}", pos, data.lootTableId);
        }
    }

    /**
     * Checks all tracked chests and resets ones that have passed the cooldown period.
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
                LOGGER.info("Chunk not loaded for chest at {}. Skipping reset.", pos);
                continue;
            }

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

            } else {
                if (DEBUG_CHEST_LOOT) {
                    if (onlyResetWhenEmpty && !chest.isEmpty()) {
                        LOGGER.info("Debug: Condition failed - chest is not empty while onlyResetWhenEmpty is true");
                    }
                    if ((currentTime - data.lastLootedTime) < resetTimeMs) {
                        LOGGER.info("Debug: Condition failed - not enough time has passed since last looted");
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
                chest.setLootTable(RegistryKey.of(RegistryKeys.LOOT_TABLE, data.getLootTableIdentifier()), world.getRandom().nextLong());
                chest.generateLoot(null);
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
        int x, y, z; // Replaces BlockPos
        String lootTableId; // Replaces Identifier
        long lootSeed;
        long lastLootedTime;
        boolean isEmpty;

        transient boolean dirty = false;

        // Helper methods to get back full objects from stored data
        public BlockPos getBlockPos() {
            return new BlockPos(x, y, z);
        }

        public Identifier getLootTableIdentifier() {
            return Identifier.of(lootTableId);
        }

        public ServerWorld getWorld(MinecraftServer server) {
            Identifier worldId = Identifier.of(worldName);
            ServerWorld world = null;

            for (ServerWorld serverWorld : server.getWorlds()) {
                if (serverWorld.getRegistryKey().getValue().equals(worldId)) {
                    return serverWorld;
                }
            }

            return null;
        }
    }
}
