package org.oldskooler.lootrestock.util;

import net.minecraft.entity.vehicle.ChestMinecartEntity;
import net.minecraft.entity.decoration.ItemFrameEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;

import java.util.UUID;

/**
 * Utility class for searching and locating entities in the world.
 */
public class EntitySearchUtil {
    private static final int DEFAULT_SEARCH_DISTANCE = 2;

    /**
     * Attempts to locate a ChestMinecartEntity in the given world by its UUID.
     * Searches within a bounding box around the given block position to limit the area.
     *
     * @param world the server world to search in
     * @param uuidStr the UUID string of the chest minecart to find
     * @param pos the position around which to search
     * @return the matching ChestMinecartEntity, or null if not found or UUID is invalid
     */
    public static ChestMinecartEntity findMinecartChestByUuid(ServerWorld world, String uuidStr, BlockPos pos) {
        return findMinecartChestByUuid(world, uuidStr, pos, DEFAULT_SEARCH_DISTANCE);
    }

    /**
     * Attempts to locate a ChestMinecartEntity in the given world by its UUID.
     * Searches within a bounding box around the given block position to limit the area.
     *
     * @param world the server world to search in
     * @param uuidStr the UUID string of the chest minecart to find
     * @param pos the position around which to search
     * @param searchDistance the distance in blocks to search around the position
     * @return the matching ChestMinecartEntity, or null if not found or UUID is invalid
     */
    public static ChestMinecartEntity findMinecartChestByUuid(ServerWorld world, String uuidStr,
                                                              BlockPos pos, int searchDistance) {
        try {
            Box searchBox = new Box(pos).expand(searchDistance);
            UUID uuid = UUID.fromString(uuidStr);

            return world.getEntitiesByClass(ChestMinecartEntity.class, searchBox,
                            entity -> entity.getUuid().equals(uuid))
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
    public static ItemFrameEntity findItemFrameByUuid(ServerWorld world, String uuidStr, BlockPos pos) {
        try {
            Box searchBox = new Box(pos).expand(DEFAULT_SEARCH_DISTANCE);
            UUID uuid = UUID.fromString(uuidStr);

            return world.getEntitiesByClass(ItemFrameEntity.class, searchBox,
                            entity -> entity.getUuid().equals(uuid))
                    .stream()
                    .findFirst()
                    .orElse(null);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
