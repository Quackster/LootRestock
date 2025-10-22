package org.oldskooler.lootrestock;

 import net.fabricmc.api.ModInitializer;
 import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
 import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
 import net.fabricmc.fabric.api.event.player.AttackEntityCallback;
 import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
 import net.fabricmc.fabric.api.event.player.UseBlockCallback;
 import net.fabricmc.fabric.api.event.player.UseEntityCallback;
 import net.minecraft.block.BlockState;
 import net.minecraft.block.BarrelBlock;
 import net.minecraft.block.ChestBlock;
 import net.minecraft.block.entity.BlockEntity;
 import net.minecraft.block.entity.LootableContainerBlockEntity;
 import net.minecraft.entity.Entity;
 import net.minecraft.entity.vehicle.ChestMinecartEntity;
 import net.minecraft.server.MinecraftServer;
 import net.minecraft.util.ActionResult;
 import net.minecraft.util.ItemScatterer;
 import net.minecraft.util.hit.HitResult;
 import net.minecraft.util.math.BlockPos;
 import net.minecraft.world.World;
 import org.oldskooler.lootrestock.config.ModConfig;
 import org.oldskooler.lootrestock.data.ChestDataManager;
 import org.oldskooler.lootrestock.handler.ChestInteractionHandler;
 import org.oldskooler.lootrestock.handler.ChestResetHandler;
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
            if (!world.isClient() && hitResult.getType() == HitResult.Type.BLOCK) {
                BlockPos pos = hitResult.getBlockPos();
                registerBlockInteraction(world, pos);
            }
            return ActionResult.PASS;
        });

        // Register entity interaction callback
        UseEntityCallback.EVENT.register((player, world, hand, entity, hitResult) -> {
            if (!world.isClient()) {
                registerEntityInteraction(world, entity);
            }
            return ActionResult.PASS;
        });

        // Register block destroy interaction callback
        PlayerBlockBreakEvents.BEFORE.register((world, player, pos, state, blockEntity) -> {
            if (!world.isClient() && blockEntity instanceof LootableContainerBlockEntity chestBlockEntity) {
                boolean isTracked = this.dataManager.isTracked(world, pos);
                
                if (isTracked || chestBlockEntity.getLootTable() != null) {
                    // Add it to tracked list if not already
                    if (!isTracked) {
                        registerBlockInteraction(world, pos);
                    }

                    // Drop the inventory contents naturally
                    ItemScatterer.spawn(world, pos, chestBlockEntity);

                    // Prevent from actually breaking
                    return false;
                }
            }

            return true;
        });

        // Register entity destroy interaction callback
        AttackEntityCallback.EVENT.register((player, world, hand, entity, result) -> {
            if (!world.isClient() && entity instanceof ChestMinecartEntity chestMinecart) {
                boolean isEntityTracked = this.dataManager.isEntityTracked(world, entity.getUuidAsString());
                
                if (isEntityTracked || chestMinecart.getLootTable() != null) {
                    // Add it to tracked list if not already
                    if (!isEntityTracked) {
                        registerEntityInteraction(world, entity);
                    }

                    // Drop the inventory contents naturally
                    ItemScatterer.spawn(world, chestMinecart.getBlockPos(), chestMinecart.getInventory());
                    
                    // Prevent from actually breaking
                    return ActionResult.FAIL;
                }
            }

            return ActionResult.PASS;
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

     private void registerEntityInteraction(World world, Entity entity) {
         if (entity instanceof ChestMinecartEntity chestMinecart) {
             interactionHandler.handleMinecartChestInteraction(world, chestMinecart);
         }
     }

     private void registerBlockInteraction(World world, BlockPos pos) {
         BlockState state = world.getBlockState(pos);

         if (state.getBlock() instanceof ChestBlock ||
                 (config.includeBarrels() && state.getBlock() instanceof BarrelBlock)) {
             BlockEntity blockEntity = world.getBlockEntity(pos);
             if (blockEntity instanceof LootableContainerBlockEntity) {
                 interactionHandler.handleChestInteraction(
                         world, pos, (LootableContainerBlockEntity) blockEntity
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
        dataManager.save();
    }
}
