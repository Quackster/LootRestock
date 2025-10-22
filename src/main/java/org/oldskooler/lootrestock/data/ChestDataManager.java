package org.oldskooler.lootrestock.data;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.WorldSavePath;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import org.oldskooler.lootrestock.LootRestock;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * Manages the persistent storage and retrieval of chest tracking data.
 */
public class ChestDataManager {
    private static final String DATA_FILE_NAME = "chest_reset_data.json";

    private Map<String, ChestData> trackedChests = new HashMap<>();
    private Path dataFile;

    /**
     * Initializes the data manager with the server instance.
     * Loads existing chest data from disk.
     *
     * @param server the MinecraftServer instance
     */
    public void initialize(MinecraftServer server) {
        this.dataFile = server.getSavePath(WorldSavePath.ROOT).resolve(DATA_FILE_NAME);
        load();
    }

    /**
     * Loads previously tracked chest data from disk.
     */
    public void load() {
        if (dataFile == null || !Files.exists(dataFile)) {
            return;
        }

        try (Reader reader = Files.newBufferedReader(dataFile)) {
            Gson gson = new Gson();
            trackedChests = gson.fromJson(reader, new TypeToken<Map<String, ChestData>>(){}.getType());
            if (trackedChests == null) {
                trackedChests = new HashMap<>();
            }
        } catch (Exception e) {
            LootRestock.LOGGER.error("Failed to load chest data from JSON: {}", e.getMessage());
            trackedChests = new HashMap<>();
        }
    }

    /**
     * Saves current tracked chest data to disk.
     */
    public void save() {
        if (dataFile == null) {
            return;
        }

        try {
            Files.createDirectories(dataFile.getParent());
            Gson gson = new GsonBuilder().setPrettyPrinting().create();
            try (Writer writer = Files.newBufferedWriter(dataFile)) {
                gson.toJson(trackedChests, writer);
            }
        } catch (IOException e) {
            LootRestock.LOGGER.error("Failed to save chest data to JSON: {}", e.getMessage());
        }
    }

    /**
     * Gets or creates a ChestData entry for the given key.
     *
     * @param key the unique chest key
     * @return the ChestData entry
     */
    public ChestData getOrCreate(String key) {
        return trackedChests.computeIfAbsent(key, k -> {
            ChestData newData = new ChestData();
            newData.setLastLootedTime(System.currentTimeMillis());
            return newData;
        });
    }

    /**
     * Gets the ChestData for a specific key, or null if it doesn't exist.
     *
     * @param key the unique chest key
     * @return the ChestData, or null
     */
    public ChestData get(String key) {
        return trackedChests.get(key);
    }

    /**
     * Removes a chest from tracking.
     *
     * @param key the unique chest key
     */
    public void remove(String key) {
        trackedChests.remove(key);
    }

    /**
     * Gets all tracked chests.
     *
     * @return map of all tracked chests
     */
    public Map<String, ChestData> getAll() {
        return trackedChests;
    }

    /**
     * Gets the number of tracked chests.
     *
     * @return the count of tracked chests
     */
    public int getTrackedChestCount() {
        return trackedChests.size();
    }

    /**
     * Creates a unique key for a block-based chest in the world.
     *
     * @param world the world the chest is in
     * @param pos the block position of the chest
     * @return a unique string key
     */
    public static String createChestKey(World world, BlockPos pos) {
        return world.getRegistryKey().getValue().toString() + ":" + pos.toShortString();
    }

    /**
     * Creates a unique key for an entity-based chest (minecart).
     *
     * @param world the world the chest is in
     * @param entityUuid the UUID of the chest minecart
     * @return a unique string key
     */
    public static String createEntityChestKey(World world, String entityUuid) {
        return world.getRegistryKey().getValue() + ":entity:" + entityUuid;
    }


    /**
     * Checks if a block-based chest is being tracked.
     *
     * @param world the world the chest is in
     * @param pos the block position of the chest
     * @return true if the chest is tracked, false otherwise
     */
    public boolean isTracked(World world, BlockPos pos) {
        String key = createChestKey(world, pos);
        return trackedChests.containsKey(key);
    }

    /**
     * Checks if an entity-based chest is being tracked.
     *
     * @param world the world the chest is in
     * @param entityUuid the UUID of the chest minecart
     * @return true if the chest is tracked, false otherwise
     */
    public boolean isEntityTracked(World world, String entityUuid) {
        String key = createEntityChestKey(world, entityUuid);
        return trackedChests.containsKey(key);
    }
}