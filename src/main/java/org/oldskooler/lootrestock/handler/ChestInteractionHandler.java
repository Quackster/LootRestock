package org.oldskooler.lootrestock.handler;

import net.minecraft.block.entity.LootableContainerBlockEntity;
import net.minecraft.entity.decoration.ItemFrameEntity;
import net.minecraft.entity.vehicle.ChestMinecartEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
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

        data.setType(ChestData.TYPE_BLOCK_CHEST);
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

        data.setType(ChestData.TYPE_ENTITY_CHEST);
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

    /**
     * Handles interaction with an item frame.
     * If the frame contains an item, it is added to the tracked map so that
     * item can be restored after the reset interval.
     *
     * @param world the world the item frame is in
     * @param itemFrame the ItemFrameEntity being interacted with
     */
    public void handleItemFrameInteraction(World world, ItemFrameEntity itemFrame) {
        ItemStack heldStack = itemFrame.getHeldItemStack();
        if (heldStack.isEmpty()) {
            return;
        }

        BlockPos pos = itemFrame.getBlockPos();
        String frameKey = ChestDataManager.createItemFrameKey(world, itemFrame.getUuidAsString());
        ChestData data = dataManager.getOrCreate(frameKey);

        data.setType(ChestData.TYPE_ITEM_FRAME);
        data.setWorldName(world.getRegistryKey().getValue().toString());
        data.setEntityUuid(itemFrame.getUuidAsString());
        data.setX(pos.getX());
        data.setY(pos.getY());
        data.setZ(pos.getZ());
        data.setItemId(Registries.ITEM.getId(heldStack.getItem()).toString());
        data.setItemCount(heldStack.getCount());
        data.setEmpty(false);
        data.setDirty(true);
    }
}
