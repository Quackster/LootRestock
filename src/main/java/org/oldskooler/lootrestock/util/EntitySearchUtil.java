package org.oldskooler.lootrestock.util;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.entity.vehicle.boat.AbstractChestBoat;
import net.minecraft.world.entity.vehicle.minecart.MinecartChest;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;

import java.util.List;
import java.util.UUID;

/**
 * Utility class for searching and locating entities in the world.
 */
public class EntitySearchUtil {
    private static final int DEFAULT_SEARCH_DISTANCE = 2;

    /**
     * Returns whether the entity is a lootable container entity, such as a chest minecart or chest boat.
     *
     * @param entity the entity to check
     * @return true if the entity is a chest minecart or chest boat
     */
    public static boolean isLootContainerEntity(Entity entity) {
        return entity instanceof MinecartChest || entity instanceof AbstractChestBoat;
    }

    /**
     * Attempts to locate a chest minecart or chest boat in the given world by its UUID.
     * Searches within a bounding box around the given block position to limit the area.
     *
     * @param world the server world to search in
     * @param uuidStr the UUID string of the container entity to find
     * @param pos the position around which to search
     * @return the matching container entity, or null if not found or UUID is invalid
     */
    public static Entity findContainerEntityByUuid(ServerLevel world, String uuidStr, BlockPos pos) {
        return findContainerEntityByUuid(world, uuidStr, pos, DEFAULT_SEARCH_DISTANCE);
    }

    /**
     * Attempts to locate a chest minecart or chest boat in the given world by its UUID.
     * Searches within a bounding box around the given block position to limit the area.
     *
     * @param world the server world to search in
     * @param uuidStr the UUID string of the container entity to find
     * @param pos the position around which to search
     * @param searchDistance the distance in blocks to search around the position
     * @return the matching container entity, or null if not found or UUID is invalid
     */
    public static Entity findContainerEntityByUuid(ServerLevel world, String uuidStr,
                                                   BlockPos pos, int searchDistance) {
        try {
            AABB searchBox = new AABB(pos).inflate(searchDistance);
            UUID uuid = UUID.fromString(uuidStr);

            return world.getEntitiesOfClass(Entity.class, searchBox,
                            entity -> isLootContainerEntity(entity) && entity.getUUID().equals(uuid))
                    .stream()
                    .findFirst()
                    .orElse(null);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /**
     * Attempts to locate an ItemFrameEntity in the given world by its UUID.
     * Searches within a bounding box around the stored position to avoid scanning the whole world.
     *
     * @param world the server world to search in
     * @param uuidStr the UUID string of the item frame to find
     * @param pos the position around which to search
     * @return the matching ItemFrameEntity, or null if not found or UUID is invalid
     */
    public static ItemFrame findItemFrameByUuid(ServerLevel world, String uuidStr, BlockPos pos) {
        try {
            AABB searchBox = new AABB(pos).inflate(DEFAULT_SEARCH_DISTANCE);
            UUID uuid = UUID.fromString(uuidStr);

            return world.getEntitiesOfClass(ItemFrame.class, searchBox,
                            entity -> entity.getUUID().equals(uuid))
                    .stream()
                    .findFirst()
                    .orElse(null);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /**
     * Finds item frames whose support block is the provided position.
     *
     * @param world the world to search in
     * @param supportPos the block position item frames are attached to
     * @return item frames attached to the support block
     */
    public static List<ItemFrame> findItemFramesAttachedTo(Level world, BlockPos supportPos) {
        AABB searchBox = new AABB(supportPos).inflate(1);

        return world.getEntitiesOfClass(ItemFrame.class, searchBox,
                itemFrame -> itemFrame.blockPosition()
                        .relative(itemFrame.getDirection().getOpposite())
                        .equals(supportPos));
    }
}
