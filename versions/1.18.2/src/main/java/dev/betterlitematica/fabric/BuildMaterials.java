package dev.betterlitematica.fabric;

import net.minecraft.block.*;
import net.minecraft.item.*;
import net.minecraft.state.property.Properties;
import net.minecraft.block.enums.*;
import java.util.*;

/** Item requirements are placement costs, not drops or a recipe expansion. */
final class BuildMaterials {
    record Requirement(Item item, int count) {}
    private BuildMaterials() {}
    static List<Requirement> forState(BlockState state) {
        Block block = state.getBlock();
        if (state.isAir() || block == Blocks.PISTON_HEAD || block == Blocks.MOVING_PISTON
            || block == Blocks.NETHER_PORTAL || block == Blocks.END_PORTAL || block == Blocks.END_GATEWAY) return List.of();
        if (state.contains(Properties.DOUBLE_BLOCK_HALF) && state.get(Properties.DOUBLE_BLOCK_HALF) == DoubleBlockHalf.UPPER) return List.of();
        if (state.contains(Properties.BED_PART) && state.get(Properties.BED_PART) == BedPart.HEAD) return List.of();
        if (block instanceof FluidBlock) {
            if (state.get(FluidBlock.LEVEL) != 0) return List.of();
            if (block == Blocks.WATER) return List.of(new Requirement(Items.WATER_BUCKET, 1));
            if (block == Blocks.LAVA) return List.of(new Requirement(Items.LAVA_BUCKET, 1));
        }
        if (block instanceof FlowerPotBlock pot && block != Blocks.FLOWER_POT)
            return List.of(new Requirement(Items.FLOWER_POT, 1), new Requirement(pot.getContent().asItem(), 1));
        Item item = block == Blocks.FARMLAND ? Items.DIRT : block.asItem();
        if (item == Items.AIR) return List.of();
        int units = 1;
        if (state.contains(Properties.SLAB_TYPE) && state.get(Properties.SLAB_TYPE) == SlabType.DOUBLE) units = 2;
        if (block == Blocks.SNOW) units = state.get(SnowBlock.LAYERS);
        if (block instanceof TurtleEggBlock) units = state.get(TurtleEggBlock.EGGS);
        if (block instanceof SeaPickleBlock) units = state.get(SeaPickleBlock.PICKLES);
        if (block instanceof CandleBlock) units = state.get(CandleBlock.CANDLES);
        if (block instanceof AbstractLichenBlock) units = (int)java.util.Arrays.stream(net.minecraft.util.math.Direction.values()).filter(d->AbstractLichenBlock.hasDirection(state,d)).count();
        return units == 0 ? List.of() : List.of(new Requirement(item, units));
    }
}
