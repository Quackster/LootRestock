package org.oldskooler.lootrestock.handler;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.entity.vehicle.minecart.MinecartChest;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.RandomizableContainerBlockEntity;
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
    public void handleChestInteraction(Level world, BlockPos pos, RandomizableContainerBlockEntity chest) {
        if (chest.getLootTable() == null) {
            return;
        }

        String chestKey = ChestDataManager.createChestKey(world, pos);
        ChestData data = dataManager.getOrCreate(chestKey);

        data.setType(ChestData.TYPE_BLOCK_CHEST);
        data.setWorldName(world.dimension().identifier().toString());
        data.setX(pos.getX());
        data.setY(pos.getY());
        data.setZ(pos.getZ());
        data.setLootTableId(chest.getLootTable().identifier().toString());
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
    public void handleMinecartChestInteraction(Level world, MinecartChest chest) {
        if (chest.getContainerLootTable() == null) {
            return;
        }

        String chestKey = ChestDataManager.createEntityChestKey(world, chest.getStringUUID());
        ChestData data = dataManager.getOrCreate(chestKey);

        data.setType(ChestData.TYPE_ENTITY_CHEST);
        data.setWorldName(world.dimension().identifier().toString());
        data.setEntityUuid(chest.getStringUUID());
        data.setLootTableId(chest.getContainerLootTable().identifier().toString());
        data.setLootSeed(chest.getContainerLootTableSeed());
        data.setX(chest.blockPosition().getX());
        data.setY(chest.blockPosition().getY());
        data.setZ(chest.blockPosition().getZ());
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
    public void handleItemFrameInteraction(Level world, ItemFrame itemFrame) {
        ItemStack heldStack = itemFrame.getItem();
        if (heldStack.isEmpty()) {
            return;
        }

        BlockPos pos = itemFrame.blockPosition();
        String frameKey = ChestDataManager.createItemFrameKey(world, itemFrame.getStringUUID());
        ChestData data = dataManager.getOrCreate(frameKey);

        data.setType(ChestData.TYPE_ITEM_FRAME);
        data.setWorldName(world.dimension().identifier().toString());
        data.setEntityUuid(itemFrame.getStringUUID());
        data.setX(pos.getX());
        data.setY(pos.getY());
        data.setZ(pos.getZ());
        data.setItemId(BuiltInRegistries.ITEM.getKey(heldStack.getItem()).toString());
        data.setItemCount(heldStack.getCount());
        data.setEmpty(false);
        data.setDirty(true);
    }
}
