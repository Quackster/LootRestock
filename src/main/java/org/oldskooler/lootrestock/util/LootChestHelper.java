package org.oldskooler.lootrestock.util;

import net.minecraft.block.entity.LootableContainerBlockEntity;
import net.minecraft.util.Identifier;
import org.oldskooler.lootrestock.mixin.LootableContainerAccessor;

public class LootChestHelper {
    public static Identifier getLootTableId(LootableContainerBlockEntity entity) {
        return ((LootableContainerAccessor) entity).getLootTableId();
    }

    public static void setLootTableId(LootableContainerBlockEntity entity, Identifier id) {
        ((LootableContainerAccessor) entity).setLootTableId(id);
    }

    public static long getLootTableSeed(LootableContainerBlockEntity entity) {
        return ((LootableContainerAccessor) entity).getLootTableSeed();
    }

    public static void setLootTableSeed(LootableContainerBlockEntity entity, long seed) {
        ((LootableContainerAccessor) entity).setLootTableSeed(seed);
    }
}