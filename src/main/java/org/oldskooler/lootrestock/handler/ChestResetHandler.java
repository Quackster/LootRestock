package org.oldskooler.lootrestock.handler;

import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.entity.LootableContainerBlockEntity;
import net.minecraft.entity.vehicle.ChestMinecartEntity;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.ChunkSectionPos;
import org.oldskooler.lootrestock.LootRestock;
import org.oldskooler.lootrestock.config.ModConfig;
import org.oldskooler.lootrestock.data.ChestData;
import org.oldskooler.lootrestock.data.ChestDataManager;
import org.oldskooler.lootrestock.util.EntitySearchUtil;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Handles the periodic checking and resetting of lootable containers.
 */
public class ChestResetHandler {
    private final ChestDataManager dataManager;
    private final ModConfig config;

    public ChestResetHandler(ChestDataManager dataManager, ModConfig config) {
        this.dataManager = dataManager;
        this.config = config;
    }

    /**
     * Iterates through all tracked chests and resets those whose cooldown period has elapsed.
     * Supports both block-based and entity-based chests. Only chests that are empty
     * (if configured) and have been inactive longer than the reset interval are reset.
     * Invalid or missing chests are automatically removed from tracking.
     *
     * @param server the MinecraftServer instance
     */
    public void checkAndResetChests(MinecraftServer server) {
        if (server == null) {
            return;
        }

        long currentTime = System.currentTimeMillis();
        AtomicInteger resetCount = new AtomicInteger(0);
        boolean needsSave = false;

        Iterator<Map.Entry<String, ChestData>> iterator = dataManager.getAll().entrySet().iterator();

        while (iterator.hasNext()) {
            Map.Entry<String, ChestData> entry = iterator.next();
            ChestData data = entry.getValue();
            BlockPos pos = data.getBlockPos();
            ServerWorld world = data.getWorld(server);

            if (world == null) {
                LootRestock.LOGGER.info("Removing chest from tracking: world '{}' no longer exists",
                        data.getWorldName());
                iterator.remove();
                needsSave = true;
                continue;
            }

            if (!world.isChunkLoaded(
                    ChunkSectionPos.getSectionCoord(pos.getX()),
                    ChunkSectionPos.getSectionCoord(pos.getZ()))) {
                LootRestock.LOGGER.debug("Chunk not loaded for chest at {}. Skipping reset.", pos);
                continue;
            }

            if (data.isEntityChest()) {
                needsSave |= processEntityChest(data, world, currentTime, iterator, resetCount);
            } else {
                needsSave |= processBlockChest(data, world, currentTime, iterator, resetCount);
            }

            if (data.isDirty()) {
                needsSave = true;
            }
        }

        if (needsSave) {
            dataManager.save();
            for (ChestData data : dataManager.getAll().values()) {
                data.setDirty(false);
            }
        }

        if (resetCount.get() > 0) {
            LootRestock.LOGGER.info("Reset {} chests", resetCount.get());
        }
    }

    private boolean processEntityChest(ChestData data, ServerWorld world, long currentTime,
                                       Iterator<Map.Entry<String, ChestData>> iterator, AtomicInteger resetCount) {
        ChestMinecartEntity entity = EntitySearchUtil.findMinecartChestByUuid(
                world, data.getEntityUuid(), data.getBlockPos()
        );

        if (entity == null) {
            LootRestock.LOGGER.info("Removing tracked entity chest: not found {}", data.getEntityUuid());
            iterator.remove();
            return true;
        }

        if (shouldReset(entity.isEmpty(), currentTime, data.getLastLootedTime())) {
            if (resetChestEntity(entity, data)) {
                resetCount.incrementAndGet();
                data.setEmpty(entity.isEmpty());
                data.setLastLootedTime(currentTime);
                data.setDirty(true);
            }
        }

        return false;
    }

    private boolean processBlockChest(ChestData data, ServerWorld world, long currentTime,
                                      Iterator<Map.Entry<String, ChestData>> iterator, AtomicInteger resetCount) {
        BlockEntity blockEntity = world.getBlockEntity(data.getBlockPos());

        if (!(blockEntity instanceof LootableContainerBlockEntity chest)) {
            LootRestock.LOGGER.info("Removing chest from tracking: block at {} is no longer a lootable container",
                    data.getBlockPos());
            iterator.remove();
            return true;
        }

        if (shouldReset(chest.isEmpty(), currentTime, data.getLastLootedTime())) {
            if (resetChest(data, world)) {
                resetCount.incrementAndGet();
                data.setEmpty(chest.isEmpty());
                data.setLastLootedTime(currentTime);
                data.setDirty(true);
            }
        }

        return false;
    }

    private boolean shouldReset(boolean isEmpty, long currentTime, long lastLootedTime) {
        // Respect the "only reset when empty" rule first
        if (config.onlyResetWhenEmpty() && !isEmpty) {
            return false;
        }

        // If a cron expression is configured, use it instead of fixed time logic
        if (config.isCronUsed() && config.getCronExpression() != null) {
            // If a cron parser exists, use it to determine the next scheduled execution after lastLootedTime
            try {
                var cronParser = config.getCronParser();
                if (cronParser != null) {
                    // Convert epoch millis -> LocalDateTime using system default zone
                    ZoneId zone = ZoneId.systemDefault();
                    LocalDateTime lastLootedLdt = LocalDateTime.ofInstant(Instant.ofEpochMilli(lastLootedTime), zone);

                    // getNextExecution returns the next matching LocalDateTime after the provided time
                    LocalDateTime next = cronParser.getNextExecution(lastLootedLdt);

                    if (next != null) {
                        long nextMillis = next.atZone(zone).toInstant().toEpochMilli();
                        return currentTime >= nextMillis;
                    } else {
                        LootRestock.LOGGER.warn("Cron parser returned null next execution; falling back to interval logic.");
                    }
                }
            } catch (Exception e) {
                // Be defensive: any exception -> log and fall back to time-based logic
                LootRestock.LOGGER.warn("Error while evaluating cron schedule: {}", e.toString());
            }
        }

        // Default: use simple time-based reset logic
        return (currentTime - lastLootedTime) >= config.getResetTimeMs();
    }

    /**
     * Resets the contents of a chest minecart using its stored loot table.
     * This clears its inventory and regenerates loot with a new random seed.
     *
     * @param chest the ChestMinecartEntity to reset
     * @param data  the stored chest data, including loot table info
     * @return true if the chest was successfully reset, false otherwise
     */
    private boolean resetChestEntity(ChestMinecartEntity chest, ChestData data) {
        try {
            chest.clear();
            chest.setLootTable(
                    RegistryKey.of(RegistryKeys.LOOT_TABLE, data.getLootTableIdentifier()),
                    chest.getEntityWorld().getRandom().nextLong()
            );
            chest.generateInventoryLoot(null);
            chest.markDirty();

            LootRestock.LOGGER.info("Reset chest minecart at {} in world {}",
                    chest.getBlockPos(), data.getWorldName());
            return true;
        } catch (Exception e) {
            LootRestock.LOGGER.error("Failed to reset chest minecart: {}", e.getMessage());
            return false;
        }
    }

    /**
     * Attempts to reset the contents of a chest using its stored loot table,
     * but only if the chest is located in a currently loaded chunk.
     * This method checks the chest's world and chunk status before accessing
     * the block entity. If the chunk is not loaded, the reset is skipped to avoid
     * unnecessary chunk loading and potential performance issues.
     *
     * @param data  The tracked chest data containing world, position, and loot info.
     * @param world The ServerWorld where the chest is located
     * @return true if the chest was successfully reset; false otherwise.
     */
    private boolean resetChest(ChestData data, ServerWorld world) {
        try {
            BlockPos pos = data.getBlockPos();
            BlockEntity blockEntity = world.getBlockEntity(pos);

            if (blockEntity instanceof LootableContainerBlockEntity chest) {
                chest.clear();
                chest.setLootTable(
                        RegistryKey.of(RegistryKeys.LOOT_TABLE, data.getLootTableIdentifier()),
                        world.getRandom().nextLong()
                );
                chest.generateLoot(null);
                chest.markDirty();

                LootRestock.LOGGER.info("Reset chest at {} in world {}", pos, data.getWorldName());
                return true;
            }
        } catch (Exception e) {
            LootRestock.LOGGER.error("Failed to reset chest: {}", e.getMessage());
        }
        return false;
    }
}