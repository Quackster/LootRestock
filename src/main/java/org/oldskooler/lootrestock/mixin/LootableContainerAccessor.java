package org.oldskooler.lootrestock.mixin;

import net.minecraft.block.entity.LootableContainerBlockEntity;
import net.minecraft.loot.LootTable;
import net.minecraft.registry.RegistryKey;
import net.minecraft.util.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(LootableContainerBlockEntity.class)
public interface LootableContainerAccessor {
	@Accessor("lootTableId")
	Identifier getLootTableId();

	@Accessor("lootTableId")
	void setLootTableId(Identifier id);

	@Accessor("lootTableSeed")
	long getLootTableSeed();

	@Accessor("lootTableSeed")
	void setLootTableSeed(long seed);
}