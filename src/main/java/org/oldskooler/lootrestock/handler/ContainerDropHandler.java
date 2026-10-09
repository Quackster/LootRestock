package org.oldskooler.lootrestock.handler;

import net.minecraft.core.BlockPos;
import net.minecraft.world.Containers;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.vehicle.ContainerEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.RandomizableContainerBlockEntity;
import org.oldskooler.lootrestock.data.ChestDataManager;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Awards players the container item when they roll a container's loot table
 * but are not allowed to break the container itself.
 *
 * Interactions are queued while the loot table is still present and confirmed
 * at the end of the tick, so a container item is only dropped if vanilla actually
 * unpacked the loot (e.g. not when the chest is blocked or the player is a spectator).
 */
public class ContainerDropHandler {
    private final Map<String, PendingUnpack> pendingUnpacks = new LinkedHashMap<>();

    /**
     * Queues a block container whose loot table may be unpacked this tick.
     *
     * @param world the world the container is in
     * @param pos the block position of the container
     */
    public void queueBlockUnpack(Level world, BlockPos pos) {
        if (!(world.getBlockEntity(pos) instanceof RandomizableContainerBlockEntity chest)
                || chest.getLootTable() == null) {
            return;
        }

        ItemStack dropStack = new ItemStack(chest.getBlockState().getBlock().asItem());
        pendingUnpacks.putIfAbsent(ChestDataManager.createChestKey(world, pos),
                new PendingUnpack(world, pos, null, dropStack));
    }

    /**
     * Queues a container entity (chest minecart or chest boat) whose loot table may be unpacked this tick.
     *
     * @param world the world the entity is in
     * @param entity the container entity
     */
    public void queueEntityUnpack(Level world, Entity entity) {
        if (!(entity instanceof ContainerEntity chest) || chest.getContainerLootTable() == null) {
            return;
        }

        pendingUnpacks.putIfAbsent(ChestDataManager.createEntityChestKey(world, entity.getStringUUID()),
                new PendingUnpack(world, null, entity, entity.getPickResult()));
    }

    /**
     * Drops the container item for every queued container whose loot table has since been unpacked.
     */
    public void processPendingUnpacks() {
        if (pendingUnpacks.isEmpty()) {
            return;
        }

        for (PendingUnpack pending : pendingUnpacks.values()) {
            if (pending.entity() != null) {
                Entity entity = pending.entity();
                if (!entity.isRemoved() && ((ContainerEntity) entity).getContainerLootTable() == null) {
                    Containers.dropItemStack(pending.world(), entity.getX(), entity.getY(), entity.getZ(),
                            pending.dropStack());
                }
            } else {
                BlockPos pos = pending.pos();
                BlockEntity blockEntity = pending.world().getBlockEntity(pos);
                if (blockEntity instanceof RandomizableContainerBlockEntity chest && chest.getLootTable() == null) {
                    Containers.dropItemStack(pending.world(), pos.getX(), pos.getY(), pos.getZ(),
                            pending.dropStack());
                }
            }
        }

        pendingUnpacks.clear();
    }

    private record PendingUnpack(Level world, BlockPos pos, Entity entity, ItemStack dropStack) {
    }
}
