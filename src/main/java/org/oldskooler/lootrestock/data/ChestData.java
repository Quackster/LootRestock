package org.oldskooler.lootrestock.data;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;

/**
 * Serializable class for tracking chest state.
 * Supports block-based chests, entity-based chests, and item frames.
 */
public class ChestData {
    public static final String TYPE_BLOCK_CHEST = "block_chest";
    public static final String TYPE_ENTITY_CHEST = "entity_chest";
    public static final String TYPE_ITEM_FRAME = "item_frame";

    // Common fields
    String type;
    String worldName;
    int x, y, z;
    String lootTableId;
    long lootSeed;
    long lastLootedTime;
    boolean isEmpty;

    // Entity-based fields
    String entityUuid;

    // Item frame fields
    String itemId;
    int itemCount;

    // Transient field for tracking changes
    transient boolean dirty = false;

    public BlockPos getBlockPos() {
        return new BlockPos(x, y, z);
    }

    public Identifier getLootTableIdentifier() {
        return Identifier.parse(lootTableId);
    }

    public Identifier getItemIdentifier() {
        return Identifier.parse(itemId);
    }

    /**
     * Retrieves the ServerWorld instance for this chest's world.
     *
     * @param server the MinecraftServer instance
     * @return the ServerWorld, or null if the world no longer exists
     */
    public ServerLevel getWorld(MinecraftServer server) {
        Identifier worldId = Identifier.parse(worldName);
        for (ServerLevel serverWorld : server.getAllLevels()) {
            if (serverWorld.dimension().identifier().equals(worldId)) {
                return serverWorld;
            }
        }
        return null;
    }

    /**
     * Checks if this data represents an entity-based chest (minecart).
     *
     * @return true if this is a minecart chest, false if it's a block-based chest
     */
    public boolean isEntityChest() {
        return TYPE_ENTITY_CHEST.equals(type) || (type == null && entityUuid != null);
    }

    /**
     * Checks if this data represents an item frame.
     *
     * @return true if this is an item frame
     */
    public boolean isItemFrame() {
        return TYPE_ITEM_FRAME.equals(type);
    }

    public String getType() {
        if (type != null) {
            return type;
        }
        return entityUuid == null ? TYPE_BLOCK_CHEST : TYPE_ENTITY_CHEST;
    }

    public void setType(String type) {
        this.type = type;
    }

    public String getWorldName() {
        return worldName;
    }

    public void setWorldName(String worldName) {
        this.worldName = worldName;
    }

    public int getX() {
        return x;
    }

    public void setX(int x) {
        this.x = x;
    }

    public int getY() {
        return y;
    }

    public void setY(int y) {
        this.y = y;
    }

    public int getZ() {
        return z;
    }

    public void setZ(int z) {
        this.z = z;
    }

    public String getEntityUuid() {
        return entityUuid;
    }

    public void setEntityUuid(String entityUuid) {
        this.entityUuid = entityUuid;
    }

    public String getLootTableId() {
        return lootTableId;
    }

    public void setLootTableId(String lootTableId) {
        this.lootTableId = lootTableId;
    }

    public long getLootSeed() {
        return lootSeed;
    }

    public void setLootSeed(long lootSeed) {
        this.lootSeed = lootSeed;
    }

    public String getItemId() {
        return itemId;
    }

    public void setItemId(String itemId) {
        this.itemId = itemId;
    }

    public int getItemCount() {
        return itemCount;
    }

    public void setItemCount(int itemCount) {
        this.itemCount = itemCount;
    }

    public long getLastLootedTime() {
        return lastLootedTime;
    }

    public void setLastLootedTime(long lastLootedTime) {
        this.lastLootedTime = lastLootedTime;
    }

    public boolean isEmpty() {
        return isEmpty;
    }

    public void setEmpty(boolean empty) {
        isEmpty = empty;
    }

    public boolean isDirty() {
        return dirty;
    }

    public void setDirty(boolean dirty) {
        this.dirty = dirty;
    }
}
