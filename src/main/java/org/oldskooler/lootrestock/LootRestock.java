package org.oldskooler.lootrestock;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.AttackEntityCallback;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.Containers;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.vehicle.minecart.MinecartChest;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BarrelBlock;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.RandomizableContainerBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.HitResult;
import org.oldskooler.lootrestock.config.ModConfig;
import org.oldskooler.lootrestock.data.ChestDataManager;
import org.oldskooler.lootrestock.handler.ChestInteractionHandler;
import org.oldskooler.lootrestock.handler.ChestResetHandler;
import org.oldskooler.lootrestock.util.EntitySearchUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * LootRestock is a Fabric mod that tracks and resets lootable chests
 * after a configurable amount of time has passed since last looted.
 *
 * Features:
 * <ul>
 *   <li>Tracks interactions with chests (and optionally barrels) that use loot tables</li>
 *   <li>Automatically resets their loot after a configurable cooldown</li>
 *   <li>Optionally only resets when the chest is empty</li>
 *   <li>Removes chests from tracking if the world is deleted or the block is no longer a lootable container</li>
 * </ul>
 */
public class LootRestock implements ModInitializer {
    public static final String MOD_ID = "lootrestock";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    private ModConfig config;
    private ChestDataManager dataManager;
    private ChestInteractionHandler interactionHandler;
    private ChestResetHandler resetHandler;

    private long tickCounter = 0;

    @Override
    public void onInitialize() {
        LOGGER.info("Initializing LootRestock");

        config = new ModConfig();
        config.load();

        dataManager = new ChestDataManager();
        interactionHandler = new ChestInteractionHandler(dataManager);
        resetHandler = new ChestResetHandler(dataManager, config);

        registerEventHandlers();
    }

    private void registerEventHandlers() {
        // Register block interaction callback
        UseBlockCallback.EVENT.register((player, world, hand, hitResult) -> {
            if (!world.isClientSide() && hitResult.getType() == HitResult.Type.BLOCK) {
                BlockPos pos = hitResult.getBlockPos();
                registerBlockInteraction(world, pos);
            }
            return InteractionResult.PASS;
        });

        // Register entity interaction callback
        UseEntityCallback.EVENT.register((player, world, hand, entity, hitResult) -> {
            if (!world.isClientSide()) {
                registerEntityInteraction(world, entity);
            }
            return InteractionResult.PASS;
        });

        // Register block destroy interaction callback
        PlayerBlockBreakEvents.BEFORE.register((world, player, pos, state, blockEntity) -> {
            if (!world.isClientSide() && isProtectedItemFrameSupport(world, player, pos)) {
                return false;
            }

            if (!world.isClientSide() && blockEntity instanceof RandomizableContainerBlockEntity chestBlockEntity) {
                boolean isTracked = this.dataManager.isTracked(world, pos);
                
                if (isTracked || chestBlockEntity.getLootTable() != null) {
                    if (canBreak(player, config.getChestBreakingPermission())) {
                        return true;
                    }

                    // Add it to tracked list if not already
                    if (!isTracked) {
                        registerBlockInteraction(world, pos);
                    }

                    // Drop the inventory contents naturally
                    Containers.dropContents(world, pos, chestBlockEntity);

                    // Prevent from actually breaking
                    return false;
                }
            }

            return true;
        });

        // Register entity destroy interaction callback
        AttackEntityCallback.EVENT.register((player, world, hand, entity, result) -> {
            if (!world.isClientSide()) {
                if (entity instanceof MinecartChest chestMinecart) {
                    boolean isEntityTracked = this.dataManager.isEntityTracked(world, entity.getStringUUID());

                    if (isEntityTracked || chestMinecart.getContainerLootTable() != null) {
                        if (canBreak(player, config.getChestBreakingPermission())) {
                            return InteractionResult.PASS;
                        }

                        // Add it to tracked list if not already
                        if (!isEntityTracked) {
                            registerEntityInteraction(world, entity);
                        }

                        // Drop the inventory contents naturally
                        Containers.dropContents(world, chestMinecart.blockPosition(), chestMinecart.getItemStacks());

                        // Prevent from actually breaking
                        return InteractionResult.FAIL;
                    }
                } else if (config.includeItemFrames() && entity instanceof ItemFrame itemFrame) {
                    boolean isItemFrameTracked = this.dataManager.isItemFrameTracked(world, entity.getStringUUID());
                    ItemStack heldStack = itemFrame.getItem();

                    if (isItemFrameTracked || !heldStack.isEmpty()) {
                        if (canBreak(player, config.getItemFrameBreakingPermission())) {
                            return InteractionResult.PASS;
                        }

                        if (!isItemFrameTracked) {
                            registerItemFrameInteraction(world, itemFrame);
                        }

                        if (!heldStack.isEmpty()) {
                            Containers.dropItemStack(world, itemFrame.getX(), itemFrame.getY(), itemFrame.getZ(), heldStack.copy());
                            itemFrame.setItem(ItemStack.EMPTY);
                        }

                        // Keep tracked item frames in place so their item can respawn later
                        return InteractionResult.FAIL;
                    }
                }
            }

            return InteractionResult.PASS;
        });


        // Server lifecycle events
        ServerLifecycleEvents.SERVER_STARTED.register(this::onServerStart);
        ServerLifecycleEvents.SERVER_STOPPING.register(this::onServerStop);

        // Periodic reset task
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            tickCounter++;
            if (tickCounter >= 20) { // 20 ticks = 1 second
                tickCounter = 0;
                resetHandler.checkAndResetChests(server);
            }
        });
    }

    private void registerEntityInteraction(Level world, Entity entity) {
        if (entity instanceof MinecartChest chestMinecart) {
            interactionHandler.handleMinecartChestInteraction(world, chestMinecart);
        } else if (config.includeItemFrames() && entity instanceof ItemFrame itemFrame) {
            interactionHandler.handleItemFrameInteraction(world, itemFrame);
        }
    }

    private void registerItemFrameInteraction(Level world, ItemFrame itemFrame) {
        interactionHandler.handleItemFrameInteraction(world, itemFrame);
    }

    private void registerBlockInteraction(Level world, BlockPos pos) {
        BlockState state = world.getBlockState(pos);

        if (state.getBlock() instanceof ChestBlock ||
                (config.includeBarrels() && state.getBlock() instanceof BarrelBlock)) {
            BlockEntity blockEntity = world.getBlockEntity(pos);
            if (blockEntity instanceof RandomizableContainerBlockEntity) {
                interactionHandler.handleChestInteraction(
                        world, pos, (RandomizableContainerBlockEntity) blockEntity
                );
            }
        }
    }

    private boolean isProtectedItemFrameSupport(Level world, Player player, BlockPos pos) {
        if (!config.includeItemFrames() || canBreak(player, config.getItemFrameBreakingPermission())) {
            return false;
        }

        return EntitySearchUtil.findItemFramesAttachedTo(world, pos).stream()
                .anyMatch(itemFrame -> this.dataManager.isItemFrameTracked(world, itemFrame.getStringUUID())
                        || !itemFrame.getItem().isEmpty());
    }

    private boolean canBreak(Player player, ModConfig.ChestBreakingPermission permission) {
        return switch (permission) {
            case ANYONE -> true;
            case OP_ONLY -> player.canUseGameMasterBlocks();
            case NO_ONE -> false;
        };
    }

    private void onServerStart(MinecraftServer server) {
        dataManager.initialize(server);
        LOGGER.info("LootRestock mod loaded {} tracked chests", dataManager.getTrackedChestCount());
    }

    private void onServerStop(MinecraftServer server) {
        LOGGER.info("Saving {} tracked chests", dataManager.getTrackedChestCount());
        dataManager.save();
    }
}
