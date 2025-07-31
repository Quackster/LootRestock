package org.oldskooler.lootrestock.mixin;

import net.minecraft.block.entity.LootableContainerBlockEntity;
import net.minecraft.entity.vehicle.ChestMinecartEntity;
import net.minecraft.entity.vehicle.StorageMinecartEntity;
import net.minecraft.util.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(StorageMinecartEntity.class)
public interface LootableEntityAccessor {
	@Accessor("lootTableId")
	Identifier getLootTableId();

	@Accessor("lootTableId")
	void setLootTableId(Identifier id);

	@Accessor("lootSeed")
	long getLootSeed();

	@Accessor("lootSeed")
	void setLootSeed(long seed);
}