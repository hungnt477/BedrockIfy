package me.juancarloscp52.bedrockify.common.block.cauldron;

import me.juancarloscp52.bedrockify.Bedrockify;
import me.juancarloscp52.bedrockify.common.block.ColoredWaterCauldronBlock;
import me.juancarloscp52.bedrockify.common.block.PotionCauldronBlock;
import me.juancarloscp52.bedrockify.common.block.entity.WaterCauldronBlockEntity;
import me.juancarloscp52.bedrockify.common.features.cauldron.BedrockCauldronBlocks;
import me.juancarloscp52.bedrockify.common.features.cauldron.ColorBlenderHelper;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.fabricmc.fabric.api.networking.v1.PlayerLookup;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.LeveledCauldronBlock;
import net.minecraft.block.cauldron.CauldronBehavior;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.*;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.potion.Potion;
import net.minecraft.potion.PotionUtil;
import net.minecraft.potion.Potions;
import net.minecraft.registry.Registries;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.stat.Stats;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraft.world.event.GameEvent;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Defines the behavior of Bedrock's Cauldron.
 */
public interface BedrockCauldronBehavior {
    Map<Item, CauldronBehavior> POTION_CAULDRON_BEHAVIOR = CauldronBehavior.createMap();
    Map<Item, CauldronBehavior> COLORED_WATER_CAULDRON_BEHAVIOR = CauldronBehavior.createMap();

    CauldronBehavior DYE_ITEM_BY_COLORED_WATER = (state, world, pos, player, hand, stack) -> {
        if (state == null || world == null || pos == null || !Bedrockify.getInstance().settings.bedrockCauldron) {
            return ActionResult.PASS;
        }

        Item item = stack.getItem();
        if (!(item instanceof DyeableItem)) {
            return ActionResult.PASS;
        }

        Optional<WaterCauldronBlockEntity> entity = retrieveCauldronEntity(world, pos);
        if (entity.isEmpty()) {
            return emptyCauldronFromWrongState(state, world, pos, player, hand, stack);
        }

        final WaterCauldronBlockEntity blockEntity = entity.get();
        if (!world.isClient) {
            ColoredWaterCauldronBlock.decrementWhenDye(state, world, pos);
            player.incrementStat(Stats.USE_CAULDRON);
            player.setStackInHand(hand, ColorBlenderHelper.blendColors(stack, blockEntity.getTintColor()));
            world.playSound(null, pos, SoundEvents.ENTITY_GENERIC_SPLASH, SoundCategory.BLOCKS, 0.15f, 1.25f);
            world.emitGameEvent(null, GameEvent.FLUID_PICKUP, pos);
        }

        return ActionResult.success(world.isClient);
    };

    CauldronBehavior DYE_WATER = (state, world, pos, player, hand, stack) -> {
        if (state == null || world == null || pos == null || !Bedrockify.getInstance().settings.bedrockCauldron) {
            return ActionResult.PASS;
        }

        Item item = stack.getItem();
        if (!(item instanceof DyeItem dyeItem)) {
            return ActionResult.PASS;
        }

        Optional<WaterCauldronBlockEntity> entity = world.getBlockEntity(pos, BedrockCauldronBlocks.WATER_CAULDRON_ENTITY);
        final int level;
        if (entity.isPresent()) {
            // The Cauldron already has color.
            final int currentColor = entity.get().getTintColor();
            if (ColorBlenderHelper.blendColors(currentColor, ColorBlenderHelper.fromDyeItem(dyeItem)) == currentColor) {
                return ActionResult.SUCCESS;
            }
            level = state.get(ColoredWaterCauldronBlock.LEVEL);
        } else {
            // Otherwise it may be Water Cauldron.
            level = ColoredWaterCauldronBlock.getLevelFromWaterCauldronState(state);
        }

        if (!world.isClient) {
            BlockState newState = BedrockCauldronBlocks.COLORED_WATER_CAULDRON.getDefaultState()
                    .with(ColoredWaterCauldronBlock.LEVEL, level);
            world.setBlockState(pos, newState);
            if (!player.getAbilities().creativeMode) {
                stack.decrement(1);
            }
            player.incrementStat(Stats.USED.getOrCreateStat(item));
            world.playSound(null, pos, SoundEvents.ITEM_DYE_USE, SoundCategory.BLOCKS);
            world.emitGameEvent(null, GameEvent.BLOCK_CHANGE, pos);
        }
        world.getBlockEntity(pos, BedrockCauldronBlocks.WATER_CAULDRON_ENTITY).ifPresent(blockEntity -> {
            blockEntity.blendDyeItem(dyeItem);
        });

        return ActionResult.success(world.isClient);
    };

    /**
     * Increases the water level and replaces the block with the vanilla Water Cauldron.
     */
    CauldronBehavior PLACE_WATER_BY_POTION = (state, world, pos, player, hand, stack) -> {
        if (state == null || world == null || pos == null || !Bedrockify.getInstance().settings.bedrockCauldron) {
            return ActionResult.PASS;
        }
        if (PotionUtil.getPotion(stack) != Potions.WATER) {
            return ActionResult.SUCCESS;
        }

        final int nextLevel;
        if (state.isOf(Blocks.WATER_CAULDRON)) {
            if (state.get(LeveledCauldronBlock.LEVEL) >= LeveledCauldronBlock.MAX_LEVEL) {
                return ActionResult.SUCCESS;
            }
            nextLevel = state.cycle(LeveledCauldronBlock.LEVEL).get(LeveledCauldronBlock.LEVEL);
        } else if (state.isOf(BedrockCauldronBlocks.COLORED_WATER_CAULDRON)) {
            if (state.get(ColoredWaterCauldronBlock.LEVEL) >= ColoredWaterCauldronBlock.MAX_LEVEL) {
                return ActionResult.SUCCESS;
            }
            nextLevel = Math.min(ColoredWaterCauldronBlock.convertToWaterCauldronLevel(state) + 1, LeveledCauldronBlock.MAX_LEVEL);
        } else {
            nextLevel = Blocks.WATER_CAULDRON.getDefaultState().get(LeveledCauldronBlock.LEVEL);
        }

        if (!world.isClient) {
            // Replace with Water Cauldron.
            player.incrementStat(Stats.USE_CAULDRON);
            player.incrementStat(Stats.USED.getOrCreateStat(stack.getItem()));
            player.setStackInHand(hand, ItemUsage.exchangeStack(stack, player, new ItemStack(Items.GLASS_BOTTLE)));
            world.setBlockState(pos, Blocks.WATER_CAULDRON.getDefaultState().with(LeveledCauldronBlock.LEVEL, nextLevel));
            world.playSound(null, pos, SoundEvents.ITEM_BOTTLE_EMPTY, SoundCategory.BLOCKS);
            world.emitGameEvent(null, GameEvent.FLUID_PLACE, pos);
        }

        return ActionResult.success(world.isClient);
    };

    /**
     * Decreases the fluid level and takes out from the Potion Cauldron.
     */
    CauldronBehavior PICK_POTION_FLUID = (state, world, pos, player, hand, stack) -> {
        if (state == null || world == null || pos == null || !Bedrockify.getInstance().settings.bedrockCauldron) {
            return ActionResult.PASS;
        }

        Optional<WaterCauldronBlockEntity> entity = retrieveCauldronEntity(world, pos);
        if (entity.isEmpty()) {
            return emptyCauldronFromWrongState(state, world, pos, player, hand, stack);
        }

        final WaterCauldronBlockEntity blockEntity = entity.get();
        final Item item = stack.getItem();
        Optional<Potion> optionalPotion = retrievePotion(blockEntity);
        if (optionalPotion.isEmpty()) {
            return emptyCauldronFromWrongState(state, world, pos, player, hand, stack);
        }

        final Potion potion = optionalPotion.get();
        final Item potionType = blockEntity.getPotionType();
        if (!world.isClient) {
            if (!PotionCauldronBlock.tryPickFluid(state, world, pos)) {
                return ActionResult.SUCCESS;
            }
            player.incrementStat(Stats.USE_CAULDRON);
            player.incrementStat(Stats.USED.getOrCreateStat(item));
            player.setStackInHand(hand, ItemUsage.exchangeStack(stack, player, PotionUtil.setPotion(new ItemStack(potionType), potion)));
            world.playSound(null, pos, SoundEvents.ITEM_BOTTLE_FILL, SoundCategory.BLOCKS);
            world.emitGameEvent(null, GameEvent.FLUID_PICKUP, pos);
            addPotionParticle(world.getBlockState(pos), world, pos, PotionUtil.getColor(potion));
        }

        return ActionResult.success(world.isClient);
    };

    /**
     * Replaces Empty Cauldron with Potion Cauldron, or increases its fluid.
     */
    CauldronBehavior PLACE_POTION_FLUID = (state, world, pos, player, hand, stack) -> {
        if (state == null || world == null || pos == null || !Bedrockify.getInstance().settings.bedrockCauldron) {
            return ActionResult.PASS;
        }

        if (!(stack.getItem() instanceof PotionItem)) {
            return ActionResult.PASS;
        }

        final Potion potion = PotionUtil.getPotion(stack);
        if (PotionUtil.getPotionEffects(stack).isEmpty() && potion != Potions.WATER) {
            // Prevent to place the fluid of Awkward Potion, Mundane Potion, etc.
            return ActionResult.SUCCESS;
        }

        final BlockState newState;
        final Identifier inStackPotionId = Registries.POTION.getId(potion);
        if (state.contains(PotionCauldronBlock.LEVEL)) {
            // The cauldron already has fluid.
            Optional<WaterCauldronBlockEntity> entity = retrieveCauldronEntity(world, pos);
            if (entity.isEmpty()) {
                return emptyCauldronFromWrongState(state, world, pos, player, hand, stack);
            }

            final Identifier currentPotionId = entity.get().getFluidId();
            // Not the same potion.
            if (!Objects.equals(currentPotionId, inStackPotionId)) {
                return evaporateCauldron(state, world, pos, player, hand, stack, new ItemStack(Items.GLASS_BOTTLE));
            }

            // The cauldron is full.
            final int currentLevel = state.get(PotionCauldronBlock.LEVEL);
            if (currentLevel >= PotionCauldronBlock.MAX_LEVEL) {
                return ActionResult.SUCCESS;
            }

            final int nextLevel = Math.min(currentLevel + PotionCauldronBlock.BOTTLE_LEVEL, PotionCauldronBlock.MAX_LEVEL);
            newState = BedrockCauldronBlocks.POTION_CAULDRON.getDefaultState().with(PotionCauldronBlock.LEVEL, nextLevel);
        } else {
            // The cauldron is empty.
            newState = BedrockCauldronBlocks.POTION_CAULDRON.getDefaultState();
        }

        if (potion == Potions.WATER) {
            // Redirect to the behavior of Water Cauldron.
            return PLACE_WATER_BY_POTION.interact(state, world, pos, player, hand, stack);
        }

        final ItemStack processing = stack.copy();
        if (!world.isClient) {
            player.incrementStat(Stats.USE_CAULDRON);
            player.incrementStat(Stats.USED.getOrCreateStat(stack.getItem()));
            player.setStackInHand(hand, ItemUsage.exchangeStack(stack, player, new ItemStack(Items.GLASS_BOTTLE)));
            world.setBlockState(pos, newState);
            world.playSound(null, pos, SoundEvents.ITEM_BOTTLE_EMPTY, SoundCategory.BLOCKS);
            world.emitGameEvent(null, GameEvent.FLUID_PLACE, pos);
            addPotionParticle(newState, world, pos, PotionUtil.getColor(potion));
        }

        // BlockEntity spawns after replacing the block with BedrockCauldronBlocks#POTION_CAULDRON.
        world.getBlockEntity(pos, BedrockCauldronBlocks.WATER_CAULDRON_ENTITY).ifPresent(blockEntity -> {
            blockEntity.setPotion(processing);
        });

        return ActionResult.success(world.isClient);
    };

    /**
     * Creates tipped arrows.
     */
    CauldronBehavior TIPPED_ARROW_WITH_POTION = (state, world, pos, player, hand, stack) -> {
        if (state == null || world == null || pos == null || !Bedrockify.getInstance().settings.bedrockCauldron) {
            return ActionResult.PASS;
        }

        if (!stack.isOf(Items.ARROW)) {
            return ActionResult.PASS;
        }

        Optional<WaterCauldronBlockEntity> entity = retrieveCauldronEntity(world, pos);
        if (entity.isEmpty()) {
            return emptyCauldronFromWrongState(state, world, pos, player, hand, stack);
        }

        final WaterCauldronBlockEntity blockEntity = entity.get();
        Optional<Potion> optionalPotion = retrievePotion(blockEntity);
        if (optionalPotion.isEmpty()) {
            return emptyCauldronFromWrongState(state, world, pos, player, hand, stack);
        }

        final Potion potion = optionalPotion.get();
        if (!world.isClient) {
            // Determine arrow count and fluid level to decrease.
            final int tippedArrowCount = PotionCauldronBlock.getMaxTippedArrowCount(stack, state);
            if (tippedArrowCount <= 0) {
                return ActionResult.SUCCESS;
            }
            final int consumedFluidLevel = PotionCauldronBlock.getDecLevelByStack(stack, tippedArrowCount);
            final int afterFluidLevel = state.get(PotionCauldronBlock.LEVEL) - consumedFluidLevel;

            // Create tipped arrows.
            final ItemStack result = new ItemStack(Items.TIPPED_ARROW, tippedArrowCount);
            PotionUtil.setPotion(result, potion);
            PotionUtil.setCustomPotionEffects(result, PotionUtil.getCustomPotionEffects(new ItemStack(Items.LINGERING_POTION)));

            if (player.isCreative()) {
                if (!player.getInventory().contains(result)) {
                    player.getInventory().insertStack(result);
                }

                player.setStackInHand(hand, stack);
            } else {
                // Exchange an item.
                stack.decrement(tippedArrowCount);

                if (stack.isEmpty()) {
                    player.setStackInHand(hand, result);
                } else {
                    if (!player.getInventory().insertStack(result)) {
                        player.dropItem(result, false);
                    }
                    player.setStackInHand(hand, stack);
                }
            }

            // Consume the fluid.
            if (afterFluidLevel <= 1) {
                world.setBlockState(pos, Blocks.CAULDRON.getDefaultState());
            } else {
                world.setBlockState(pos, BedrockCauldronBlocks.POTION_CAULDRON.getDefaultState().with(PotionCauldronBlock.LEVEL, afterFluidLevel));
            }
            player.incrementStat(Stats.USE_CAULDRON);
            player.increaseStat(Stats.USED.getOrCreateStat(Items.ARROW), tippedArrowCount);
            world.playSound(null, pos, SoundEvents.ENTITY_GENERIC_SPLASH, SoundCategory.BLOCKS, 0.15f, 1.25f);
            world.emitGameEvent(null, GameEvent.FLUID_PICKUP, pos);
        }

        return ActionResult.success(world.isClient);
    };

    CauldronBehavior PICK_COLORED_WATER = (state, world, pos, player, hand, stack) -> {
        if (!world.isClient) {
            if (!ColoredWaterCauldronBlock.tryPickFluid(state, world, pos)) {
                return ActionResult.SUCCESS;
            }
            Item item = stack.getItem();
            player.setStackInHand(hand, ItemUsage.exchangeStack(stack, player, PotionUtil.setPotion(new ItemStack(Items.POTION), Potions.WATER)));
            player.incrementStat(Stats.USE_CAULDRON);
            player.incrementStat(Stats.USED.getOrCreateStat(item));
            world.playSound(null, pos, SoundEvents.ITEM_BOTTLE_FILL, SoundCategory.BLOCKS);
            world.emitGameEvent(null, GameEvent.FLUID_PICKUP, pos);
        }

        return ActionResult.success(world.isClient);
    };

    CauldronBehavior FILL_BUCKET_WITH_COLORED_WATER = (state, world, pos, player, hand, stack) -> {
        return CauldronBehavior.emptyCauldron(state, world, pos, player, hand, stack, new ItemStack(Items.WATER_BUCKET), (statex) -> {
            return statex.get(ColoredWaterCauldronBlock.LEVEL) == ColoredWaterCauldronBlock.MAX_LEVEL;
        }, SoundEvents.ITEM_BUCKET_FILL);
    };

    /**
     * Helper method that checks and retrieves the {@link WaterCauldronBlockEntity}.<br>
     * There are no Entity, the error log will appear.
     *
     * @return {@link Optional} of WaterCauldronBlockEntity.
     */
    static Optional<WaterCauldronBlockEntity> retrieveCauldronEntity(World world, BlockPos pos) {
        Optional<WaterCauldronBlockEntity> entity = world.getBlockEntity(pos, BedrockCauldronBlocks.WATER_CAULDRON_ENTITY);
        if (entity.isEmpty()) {
            // Increasing the fluid requires to retrieve the potion.
            Bedrockify.LOGGER.error(
                    "[%s] something went wrong to get the fluid in cauldron".formatted(Bedrockify.class.getSimpleName()),
                    new NullPointerException("RegistryWorldView#getBlockEntity is not present at " + pos));
            return Optional.empty();
        }
        return entity;
    }

    /**
     * Helper method that checks exact potion fluid and returns {@link Potion}.
     *
     * @return {@link Optional} of Potion.
     */
    static Optional<Potion> retrievePotion(WaterCauldronBlockEntity blockEntity) {
        final Identifier potionId = blockEntity.getFluidId();
        final Potion potion = Registries.POTION.get(potionId);
        if (Objects.equals(Registries.POTION.getId(potion), Registries.POTION.getDefaultId())) {
            Bedrockify.LOGGER.error(
                    "[%s] something went wrong to get the potion from Registries".formatted(Bedrockify.class.getSimpleName()),
                    new IllegalStateException("potion has disappeared, maybe the mod is gone?"));
            return Optional.empty();
        }
        return Optional.of(potion);
    }

    static ActionResult emptyCauldronFromWrongState(BlockState state, World world, BlockPos pos, PlayerEntity player, Hand hand, ItemStack stack) {
        return evaporateCauldron(state, world, pos, player, hand, stack, stack.copy());
    }

    static void registerEvaporateBucketBehavior(Map<Item, CauldronBehavior> map) {
        map.put(Items.WATER_BUCKET, (state, world, pos, player, hand, stack) -> {
            return evaporateCauldron(state, world, pos, player, hand, stack, new ItemStack(Items.BUCKET));
        });
        map.put(Items.LAVA_BUCKET, (state, world, pos, player, hand, stack) -> {
            return evaporateCauldron(state, world, pos, player, hand, stack, new ItemStack(Items.BUCKET));
        });
        map.put(Items.POWDER_SNOW_BUCKET, (state, world, pos, player, hand, stack) -> {
            return evaporateCauldron(state, world, pos, player, hand, stack, new ItemStack(Items.BUCKET));
        });
    }

    /**
     * Empties the cauldron by spawning particles and sound.
     */
    static ActionResult evaporateCauldron(BlockState state, World world, BlockPos pos, PlayerEntity player, Hand hand, ItemStack current, ItemStack after) {
        if (!world.isClient) {
            final Identifier particleId = Registries.PARTICLE_TYPE.getId(ParticleTypes.POOF);
            for (int i = 0; i < 10; ++i) {
                PacketByteBuf buf = PacketByteBufs.create();
                buf.writeIdentifier(particleId);
                buf.writeDouble(pos.getX() + 0.25 + Math.random() * 0.5);
                buf.writeDouble(pos.getY() + 0.35);
                buf.writeDouble(pos.getZ() + 0.25 + Math.random() * 0.5);
                buf.writeDouble(Math.random() * 0.075);
                buf.writeDouble(0);
                buf.writeDouble(Math.random() * 0.075);

                PlayerLookup.world((ServerWorld) world).forEach(serverPlayerEntity -> {
                    ServerPlayNetworking.send(serverPlayerEntity, Bedrockify.CAULDRON_ACTION_PARTICLES, buf);
                });
            }
        }
        return CauldronBehavior.emptyCauldron(state, world, pos, player, hand, current, after, statex -> true, SoundEvents.BLOCK_FIRE_EXTINGUISH);
    }

    /**
     * Spawns particles after interacting with a potion.
     */
    static void addPotionParticle(BlockState state, World world, BlockPos pos, int color) {
        if (world.isClient) {
            return;
        }

        final double offsetY;
        if (state.getBlock() instanceof PotionCauldronBlock potionCauldronBlock) {
            offsetY = 0.05 + potionCauldronBlock.getFluidHeight(state);
        } else {
            offsetY = 0.5;
        }
        final double red = ((color >> 16) & 0xff) / 255.;
        final double green = ((color >> 8) & 0xff) / 255.;
        final double blue = (color & 0xff) / 255.;
        final Identifier particleId = Registries.PARTICLE_TYPE.getId(ParticleTypes.ENTITY_EFFECT);
        for (int i = 0; i < 7; ++i) {
            final PacketByteBuf buf = PacketByteBufs.create();
            buf.writeIdentifier(particleId);
            buf.writeDouble(pos.getX() + 0.15 + Math.random() * 0.7);
            buf.writeDouble(pos.getY() + offsetY);
            buf.writeDouble(pos.getZ() + 0.15 + Math.random() * 0.7);
            buf.writeDouble(red);
            buf.writeDouble(green);
            buf.writeDouble(blue);

            PlayerLookup.world((ServerWorld) world).forEach(serverPlayerEntity -> {
                ServerPlayNetworking.send(serverPlayerEntity, Bedrockify.CAULDRON_ACTION_PARTICLES, buf);
            });
        }
    }

    /**
     * Registers the behavior of Bedrock's cauldron from all items and potions.<br>
     * This method needs to be executed after all the registries are ready.
     */
    static void registerBehavior() {
        // Behavior of the dye item for water cauldron.
        Registries.ITEM.stream().filter(item -> item instanceof DyeItem).forEach(dyeItem -> {
            CauldronBehavior.WATER_CAULDRON_BEHAVIOR.putIfAbsent(dyeItem, DYE_WATER);
            COLORED_WATER_CAULDRON_BEHAVIOR.putIfAbsent(dyeItem, DYE_WATER);
        });

        // Behavior of the colored cauldron.
        Registries.ITEM.stream().filter(item -> item instanceof DyeableItem).forEach(item -> {
            COLORED_WATER_CAULDRON_BEHAVIOR.putIfAbsent(item, DYE_ITEM_BY_COLORED_WATER);
        });
        COLORED_WATER_CAULDRON_BEHAVIOR.putIfAbsent(Items.BUCKET, FILL_BUCKET_WITH_COLORED_WATER);
        COLORED_WATER_CAULDRON_BEHAVIOR.putIfAbsent(Items.GLASS_BOTTLE, PICK_COLORED_WATER);
        CauldronBehavior.registerBucketBehavior(COLORED_WATER_CAULDRON_BEHAVIOR);

        // Behavior of the potion.
        Registries.ITEM.stream().filter(item -> item instanceof PotionItem).forEach(potionItem -> {
            CauldronBehavior.EMPTY_CAULDRON_BEHAVIOR.putIfAbsent(potionItem, PLACE_POTION_FLUID);
            POTION_CAULDRON_BEHAVIOR.putIfAbsent(potionItem, PLACE_POTION_FLUID);
            // Allows to accept any potion type for the Water Cauldron.
            CauldronBehavior.WATER_CAULDRON_BEHAVIOR.putIfAbsent(potionItem, PLACE_WATER_BY_POTION);
            COLORED_WATER_CAULDRON_BEHAVIOR.putIfAbsent(potionItem, PLACE_WATER_BY_POTION);
        });
        POTION_CAULDRON_BEHAVIOR.putIfAbsent(Items.GLASS_BOTTLE, PICK_POTION_FLUID);
        POTION_CAULDRON_BEHAVIOR.putIfAbsent(Items.ARROW, TIPPED_ARROW_WITH_POTION);
        registerEvaporateBucketBehavior(POTION_CAULDRON_BEHAVIOR);
    }
}
