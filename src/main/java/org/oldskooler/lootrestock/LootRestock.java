package org.oldskooler.lootrestock;

 import net.fabricmc.api.ModInitializer;
 import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
 import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
 import net.fabricmc.fabric.api.event.player.AttackBlockCallback;
 import net.fabricmc.fabric.api.event.player.AttackEntityCallback;
 import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
 import net.fabricmc.fabric.api.event.player.UseBlockCallback;
 import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.permissions.Permissions;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.vehicle.minecart.MinecartChest;
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
 import org.slf4j.Logger;
 import org.slf4j.LoggerFactory;

 import java.util.HashMap;
 import java.util.Map;
 import java.util.UUID;

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
    private final Map<BreakAttemptKey, Long> protectedBreakAttempts = new HashMap<>();

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
                if (isRestockingContainer(world, pos)) {
                    sendActionBar(player, getRestockNotice());
                }
            }
            return InteractionResult.PASS;
        });

        // Register entity interaction callback
        UseEntityCallback.EVENT.register((player, world, hand, entity, hitResult) -> {
            if (!world.isClientSide()) {
                registerEntityInteraction(world, entity);
                if (isRestockingMinecart(world, entity)) {
                    sendActionBar(player, getRestockNotice());
                }
            }
            return InteractionResult.PASS;
        });

        // Register block attack callback to start the longer crouch-break timer.
        AttackBlockCallback.EVENT.register((player, world, hand, pos, direction) -> {
            if (!world.isClientSide() && isRestockingContainer(world, pos)) {
                BreakDecision decision = getBreakDecision(player);
                if (!decision.canBreak()) {
                    protectedBreakAttempts.remove(BreakAttemptKey.block(player, world, pos));
                    sendActionBar(player, decision.message());
                    return InteractionResult.FAIL;
                }

                BreakAttemptKey key = BreakAttemptKey.block(player, world, pos);
                protectedBreakAttempts.putIfAbsent(key, System.currentTimeMillis());

                if (config.requireCrouchToBreak() && config.getCrouchBreakMs() > 0) {
                    sendActionBar(player, "Keep crouching to remove this restocking chest.");
                }
            }

            return InteractionResult.PASS;
        });

        // Register block destroy interaction callback
        PlayerBlockBreakEvents.BEFORE.register((world, player, pos, state, blockEntity) -> {
            if (!world.isClientSide() && isRestockingContainer(world, pos, blockEntity)) {
                boolean isTracked = this.dataManager.isTracked(world, pos);

                if (!isTracked) {
                    registerBlockInteraction(world, pos);
                }

                BreakDecision decision = getBreakDecision(player);
                if (!decision.canBreak()) {
                    protectedBreakAttempts.remove(BreakAttemptKey.block(player, world, pos));
                    sendActionBar(player, decision.message());
                    return false;
                }

                BreakAttemptKey key = BreakAttemptKey.block(player, world, pos);
                long startedAt = protectedBreakAttempts.computeIfAbsent(key, ignored -> System.currentTimeMillis());
                long elapsedMs = System.currentTimeMillis() - startedAt;
                if (config.requireCrouchToBreak() && elapsedMs < config.getCrouchBreakMs()) {
                    long remainingSeconds = Math.max(1L, (config.getCrouchBreakMs() - elapsedMs + 999L) / 1000L);
                    sendActionBar(player, "Keep crouching for " + remainingSeconds + "s to remove this restocking chest.");
                    return false;
                }

                return true;
            }

            return true;
        });

        PlayerBlockBreakEvents.AFTER.register((world, player, pos, state, blockEntity) -> {
            protectedBreakAttempts.remove(BreakAttemptKey.block(player, world, pos));
            dataManager.remove(ChestDataManager.createChestKey(world, pos));
        });

        // Register entity destroy interaction callback
        AttackEntityCallback.EVENT.register((player, world, hand, entity, result) -> {
            if (!world.isClientSide() && entity instanceof MinecartChest chestMinecart) {
                boolean isEntityTracked = this.dataManager.isEntityTracked(world, entity.getStringUUID());
                
                if (isEntityTracked || chestMinecart.getContainerLootTable() != null) {
                    // Add it to tracked list if not already
                    if (!isEntityTracked) {
                        registerEntityInteraction(world, entity);
                    }

                    BreakDecision decision = getBreakDecision(player);
                    if (!decision.canBreak()) {
                        sendActionBar(player, decision.message());
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
         }
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

     private void onServerStart(MinecraftServer server) {
        dataManager.initialize(server);
        LOGGER.info("LootRestock mod loaded {} tracked chests", dataManager.getTrackedChestCount());
    }

    private void onServerStop(MinecraftServer server) {
        LOGGER.info("Saving {} tracked chests", dataManager.getTrackedChestCount());
        protectedBreakAttempts.clear();
        dataManager.save();
    }

    private boolean isRestockingContainer(Level world, BlockPos pos) {
        return isRestockingContainer(world, pos, world.getBlockEntity(pos));
    }

    private boolean isRestockingContainer(Level world, BlockPos pos, BlockEntity blockEntity) {
        if (!(blockEntity instanceof RandomizableContainerBlockEntity chestBlockEntity)) {
            return false;
        }

        return dataManager.isTracked(world, pos) || chestBlockEntity.getLootTable() != null;
    }

    private boolean isRestockingMinecart(Level world, Entity entity) {
        return entity instanceof MinecartChest chestMinecart
                && (dataManager.isEntityTracked(world, entity.getStringUUID())
                || chestMinecart.getContainerLootTable() != null);
    }

    private BreakDecision getBreakDecision(Player player) {
        if (!hasChestBreakPermission(player)) {
            return new BreakDecision(false, "This restocking chest is protected.");
        }

        if (config.requireCrouchToBreak() && !player.isShiftKeyDown()) {
            return new BreakDecision(false, "Loot chest restocks. Sneak and hold to remove it.");
        }

        return new BreakDecision(true, "");
    }

    private boolean hasChestBreakPermission(Player player) {
        return switch (config.getChestBreakingPermission()) {
            case ANYONE -> true;
            case OP_ONLY -> player.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER);
            case NO_ONE -> false;
        };
    }

    private String getRestockNotice() {
        if (config.requireCrouchToBreak()) {
            return "Loot chest restocks. Please leave it unless needed; sneak + hold to remove.";
        }

        return "Loot chest restocks. Please leave it in place unless you need to remove it.";
    }

    private void sendActionBar(Player player, String message) {
        player.sendOverlayMessage(Component.literal(message));
    }

    private record BreakDecision(boolean canBreak, String message) {
    }

    private record BreakAttemptKey(UUID playerUuid, String chestKey) {
        private static BreakAttemptKey block(Player player, Level world, BlockPos pos) {
            return new BreakAttemptKey(player.getUUID(), ChestDataManager.createChestKey(world, pos));
        }
    }
}
