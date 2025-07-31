package org.oldskooler.lootrestock.util;

import net.minecraft.block.entity.LootableContainerBlockEntity;
import net.minecraft.entity.vehicle.ChestMinecartEntity;
import net.minecraft.util.Identifier;
import org.oldskooler.lootrestock.mixin.LootableContainerAccessor;
import org.oldskooler.lootrestock.mixin.LootableEntityAccessor;

public class LootEntityHelper {
    public static Identifier getLootTableId(ChestMinecartEntity entity) {
        return ((LootableEntityAccessor) entity).getLootTableId();
    }

    public static void setLootTableId(ChestMinecartEntity entity, Identifier id) {
        ((LootableEntityAccessor) entity).setLootTableId(id);
    }

    public static long getLootTableSeed(ChestMinecartEntity entity) {
        return ((LootableEntityAccessor) entity).getLootSeed();
    }

    public static void setLootTableSeed(ChestMinecartEntity entity, long seed) {
        ((LootableEntityAccessor) entity).setLootSeed(seed);
    }
}