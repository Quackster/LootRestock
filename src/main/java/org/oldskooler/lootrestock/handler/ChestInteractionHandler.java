package org.oldskooler.lootrestock.handler;

import net.minecraft.block.entity.LootableContainerBlockEntity;
import net.minecraft.entity.vehicle.ChestMinecartEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import org.oldskooler.lootrestock.data.ChestData;
import org.oldskooler.lootrestock.data.ChestDataManager;

/**
 * Handles player interactions with lootable containers.
 * Records chest interactions and updates tracking data.
 */
public class ChestInteractionHandler {
    private final ChestDataManager dataManager;

    public ChestInteractionHandler(ChestDataManager dataManager) {
        this.dataManager = dataManager;
    }

    /**
     * Handles interaction with a block-based chest (e.g., normal chest or barrel).
     * If the block has a loot table, it is added to the tracking map, and its state is recorded.
     *
     * @param world the world the chest is in
     * @param pos the block position of the chest
     * @param chest the LootableContainerBlockEntity being interacted with
     */
    public void handleChestInteraction(World world, BlockPos pos, LootableContainerBlockEntity chest) {
        if (chest.getLootTable() == null) {
            return;
        }

        String chestKey = ChestDataManager.createChestKey(world, pos);
        ChestData data = dataManager.getOrCreate(chestKey);

        data.setWorldName(world.getRegistryKey().getValue().toString());
        data.setX(pos.getX());
        data.setY(pos.getY());
        data.setZ(pos.getZ());
        data.setLootTableId(chest.getLootTable().getValue().toString());
        data.setLootSeed(chest.getLootTableSeed());
        data.setEmpty(chest.isEmpty());
        data.setDirty(true);
    }

    /**
     * Handles interaction with a Chest Minecart entity.
     * If the entity has a loot table, it is added to the tracked chest map
     * and its current state is recorded. The reset timer starts from the time
     * of interaction.
     *
     * @param world the world the chest minecart is in
     * @param chest the ChestMinecartEntity being interacted with
     */
    public void handleMinecartChestInteraction(World world, ChestMinecartEntity chest) {
        if (chest.getLootTable() == null) {
            return;
        }

        String chestKey = ChestDataManager.createEntityChestKey(world, chest.getUuidAsString());
        ChestData data = dataManager.getOrCreate(chestKey);

        data.setWorldName(world.getRegistryKey().getValue().toString());
        data.setEntityUuid(chest.getUuidAsString());
        data.setLootTableId(chest.getLootTable().getValue().toString());
        data.setLootSeed(chest.getLootTableSeed());
        data.setX(chest.getBlockPos().getX());
        data.setY(chest.getBlockPos().getY());
        data.setZ(chest.getBlockPos().getZ());
        data.setEmpty(chest.isEmpty());
        data.setDirty(true);
    }
}