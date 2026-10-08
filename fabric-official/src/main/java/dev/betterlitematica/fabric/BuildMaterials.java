package dev.betterlitematica.fabric;

import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CandleBlock;
import net.minecraft.world.level.block.FlowerPotBlock;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.MultifaceBlock;
import net.minecraft.world.level.block.SeaPickleBlock;
import net.minecraft.world.level.block.SnowLayerBlock;
import net.minecraft.world.level.block.TurtleEggBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.block.state.properties.SlabType;
import java.util.*;

/** Item requirements are placement costs, not drops or a recipe expansion. */
final class BuildMaterials {
    record Requirement(Item item, int count) {}
    private BuildMaterials() {}
    static List<Requirement> forState(BlockState state) {
        Block block = state.getBlock();
        if (state.isAir() || block == Blocks.PISTON_HEAD || block == Blocks.MOVING_PISTON
            || block == Blocks.NETHER_PORTAL || block == Blocks.END_PORTAL || block == Blocks.END_GATEWAY) return List.of();
        if (state.hasProperty(BlockStateProperties.DOUBLE_BLOCK_HALF) && state.getValue(BlockStateProperties.DOUBLE_BLOCK_HALF) == DoubleBlockHalf.UPPER) return List.of();
        if (state.hasProperty(BlockStateProperties.BED_PART) && state.getValue(BlockStateProperties.BED_PART) == BedPart.HEAD) return List.of();
        if (block instanceof LiquidBlock) {
            if (state.getValue(LiquidBlock.LEVEL) != 0) return List.of();
            if (block == Blocks.WATER) return List.of(new Requirement(Items.WATER_BUCKET, 1));
            if (block == Blocks.LAVA) return List.of(new Requirement(Items.LAVA_BUCKET, 1));
        }
        if (block instanceof FlowerPotBlock pot && block != Blocks.FLOWER_POT)
            return List.of(new Requirement(Items.FLOWER_POT, 1), new Requirement(pot.getPotted().asItem(), 1));
        Item item = block == Blocks.FARMLAND ? Items.DIRT : block.asItem();
        if (item == Items.AIR) return List.of();
        int units = 1;
        if (state.hasProperty(BlockStateProperties.SLAB_TYPE) && state.getValue(BlockStateProperties.SLAB_TYPE) == SlabType.DOUBLE) units = 2;
        if (block == Blocks.SNOW) units = state.getValue(SnowLayerBlock.LAYERS);
        if (block instanceof TurtleEggBlock) units = state.getValue(TurtleEggBlock.EGGS);
        if (block instanceof SeaPickleBlock) units = state.getValue(SeaPickleBlock.PICKLES);
        if (block instanceof CandleBlock) units = state.getValue(CandleBlock.CANDLES);
        if (block instanceof MultifaceBlock) units = MultifaceBlock.availableFaces(state).size();
        return units == 0 ? List.of() : List.of(new Requirement(item, units));
    }
}
