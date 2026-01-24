package org.oldskooler.lootrestock.util;

import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.vehicle.minecart.MinecartChest;
import net.minecraft.world.phys.AABB;

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
    public static MinecartChest findMinecartChestByUuid(ServerLevel world, String uuidStr, BlockPos pos) {
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
    public static MinecartChest findMinecartChestByUuid(ServerLevel world, String uuidStr,
                                                              BlockPos pos, int searchDistance) {
        try {
            AABB searchBox = new AABB(pos).inflate(searchDistance);
            UUID uuid = UUID.fromString(uuidStr);

            return world.getEntitiesOfClass(MinecartChest.class, searchBox,
                            entity -> entity.getUUID().equals(uuid))
                    .stream()
                    .findFirst()
                    .orElse(null);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}