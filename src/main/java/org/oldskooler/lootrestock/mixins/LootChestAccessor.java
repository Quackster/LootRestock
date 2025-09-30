package org.oldskooler.lootrestock.mixins;

import net.minecraft.block.entity.LootableContainerBlockEntity;
import net.minecraft.util.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(LootableContainerBlockEntity.class)
public interface LootChestAccessor {
    @Accessor("lootTableId")
    Identifier getLootTableId();

    @Accessor("lootTableSeed")
    long getLootTableSeed();
}