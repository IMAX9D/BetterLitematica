package dev.betterlitematica.fabric;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CandleBlock;
import net.minecraft.world.level.block.ComparatorBlock;
import net.minecraft.world.level.block.ComposterBlock;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.FlowerPotBlock;
import net.minecraft.world.level.block.NoteBlock;
import net.minecraft.world.level.block.ObserverBlock;
import net.minecraft.world.level.block.RepeaterBlock;
import net.minecraft.world.level.block.SeaPickleBlock;
import net.minecraft.world.level.block.SnowLayerBlock;
import net.minecraft.world.level.block.TrapDoorBlock;
import net.minecraft.world.level.block.TurtleEggBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.SlabType;
import java.util.*;

/** Placement prediction is the authority; unsupported target states are never reported as placed. */
final class PrinterRules {
    private PrinterRules(){}
    static BlockState coralSubstitute(BlockState wanted){
        var id=BuiltInRegistries.BLOCK.getKey(wanted.getBlock());String name=id.getPath();if(!id.getNamespace().equals("minecraft")||!name.startsWith("dead_")||!name.contains("coral"))return null;
        var live=BuiltInRegistries.BLOCK.getValue(net.minecraft.resources.Identifier.fromNamespaceAndPath("minecraft",name.substring(5))).defaultBlockState();if(live.isAir())return null;for(var property:wanted.getProperties())live=copyProperty(live,wanted,property);return live;
    }
    private static <T extends Comparable<T>> BlockState copyProperty(BlockState target,BlockState source,net.minecraft.world.level.block.state.properties.Property<T> property){return target.hasProperty(property)?target.setValue(property,source.getValue(property)):target;}
    static boolean pendingCoral(BlockState actual,BlockState wanted){var substitute=coralSubstitute(wanted);return substitute!=null&&substitute.equals(actual);}
    static boolean observerReady(net.minecraft.core.BlockPos start,java.util.function.Function<net.minecraft.core.BlockPos,BlockState> expected,java.util.function.Function<net.minecraft.core.BlockPos,BlockState> actual){
        var seen=new HashSet<net.minecraft.core.BlockPos>();var pos=start.immutable();for(int i=0;i<64;i++){if(!seen.add(pos))return false;var state=expected.apply(pos);if(state==null)return false;if(!state.is(Blocks.OBSERVER))return true;pos=pos.relative(state.getValue(ObserverBlock.FACING));var wanted=expected.apply(pos);if(wanted==null||!wanted.equals(actual.apply(pos)))return false;}return false;
    }
    static BlockState cycleDirection(BlockState state){
        if(state.hasProperty(BlockStateProperties.FACING))return state.cycle(BlockStateProperties.FACING);if(state.hasProperty(BlockStateProperties.HORIZONTAL_FACING))return state.cycle(BlockStateProperties.HORIZONTAL_FACING);if(state.hasProperty(BlockStateProperties.AXIS))return state.cycle(BlockStateProperties.AXIS);if(state.hasProperty(BlockStateProperties.ROTATION_16))return state.setValue(BlockStateProperties.ROTATION_16,(state.getValue(BlockStateProperties.ROTATION_16)+4)%16);return state;
    }
    static String direction(BlockState state){if(state.hasProperty(BlockStateProperties.FACING))return state.getValue(BlockStateProperties.FACING).getSerializedName();if(state.hasProperty(BlockStateProperties.HORIZONTAL_FACING))return state.getValue(BlockStateProperties.HORIZONTAL_FACING).getSerializedName();if(state.hasProperty(BlockStateProperties.AXIS))return state.getValue(BlockStateProperties.AXIS).getSerializedName();if(state.hasProperty(BlockStateProperties.ROTATION_16))return Integer.toString(state.getValue(BlockStateProperties.ROTATION_16));return "";}
    static boolean fluidMatches(String fluidId,List<String> filters){for(String token:filters){String id=token.contains(":")?token:"minecraft:"+token;if(id.equals(fluidId)||fluidId.equals(id.replace(":",":flowing_")))return true;}return false;}
    static boolean filtered(BlockState state,List<String> filters){
        if(filters.isEmpty())return false;String id=BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
        for(String token:filters){String text=token.toLowerCase(Locale.ROOT);if(id.equals(text)||(!text.contains(":")&&id.endsWith(":"+text)))return true;}return false;
    }
    static boolean adjustable(BlockState actual,BlockState wanted,PrinterSettings s){
        if(actual.equals(wanted)||actual.isAir())return false;
        if(actual.getBlock()!=wanted.getBlock())return s.stripLogs&&isUnstripped(actual,wanted)||actual.is(Blocks.FLOWER_POT)&&wanted.getBlock() instanceof FlowerPotBlock||actual.is(Blocks.DIRT)&&wanted.is(Blocks.FARMLAND);
        if(wanted.hasProperty(BlockStateProperties.WATERLOGGED)&&actual.getValue(BlockStateProperties.WATERLOGGED)!=wanted.getValue(BlockStateProperties.WATERLOGGED))return true;
        if(wanted.hasProperty(BlockStateProperties.SLAB_TYPE)&&wanted.getValue(BlockStateProperties.SLAB_TYPE)==SlabType.DOUBLE)return true;
        if(wanted.is(Blocks.SNOW)&&actual.getValue(SnowLayerBlock.LAYERS)<wanted.getValue(SnowLayerBlock.LAYERS))return true;
        if(wanted.getBlock() instanceof CandleBlock&&actual.getValue(CandleBlock.CANDLES)<wanted.getValue(CandleBlock.CANDLES))return true;
        if(wanted.getBlock() instanceof SeaPickleBlock&&actual.getValue(SeaPickleBlock.PICKLES)<wanted.getValue(SeaPickleBlock.PICKLES))return true;
        if(wanted.getBlock() instanceof TurtleEggBlock&&actual.getValue(TurtleEggBlock.EGGS)<wanted.getValue(TurtleEggBlock.EGGS))return true;
        if(s.noteTuning&&wanted.is(Blocks.NOTE_BLOCK)&&actual.getValue(NoteBlock.NOTE)!=wanted.getValue(NoteBlock.NOTE))return true;
        if(wanted.is(Blocks.REPEATER)&&actual.getValue(RepeaterBlock.DELAY)!=wanted.getValue(RepeaterBlock.DELAY))return true;
        if(wanted.is(Blocks.COMPARATOR)&&actual.getValue(ComparatorBlock.MODE)!=wanted.getValue(ComparatorBlock.MODE))return true;
        if(manualOpen(wanted)&&actual.getValue(BlockStateProperties.OPEN)!=wanted.getValue(BlockStateProperties.OPEN))return true;
        if(wanted.is(Blocks.LEVER)&&actual.getValue(BlockStateProperties.POWERED)!=wanted.getValue(BlockStateProperties.POWERED))return true;
        if(s.bonemeal&&wanted.getBlock() instanceof CropBlock crop&&crop.getAge(actual)<crop.getAge(wanted))return true;
        return s.composter&&wanted.is(Blocks.COMPOSTER)&&actual.getValue(ComposterBlock.LEVEL)<wanted.getValue(ComposterBlock.LEVEL);
    }
    static boolean manualOpen(BlockState state){return DoorBlock.isWoodenDoor(state)||state.getBlock() instanceof TrapDoorBlock&&!state.is(Blocks.IRON_TRAPDOOR)||state.getBlock() instanceof FenceGateBlock;}
    static boolean isUnstripped(BlockState actual,BlockState wanted){
        String target=BuiltInRegistries.BLOCK.getKey(wanted.getBlock()).toString(),source=BuiltInRegistries.BLOCK.getKey(actual.getBlock()).toString();
        int colon=target.indexOf(':');return target.substring(colon+1).startsWith("stripped_")&&source.equals(target.substring(0,colon+1)+target.substring(colon+10));
    }
    static boolean placementMatches(BlockState predicted,BlockState wanted){
        if(predicted==null||predicted.getBlock()!=wanted.getBlock())return false;
        BlockState normalized=predicted;
        // A subsequent use action completes only these explicit, monotonic multi-step states.
        if(wanted.hasProperty(BlockStateProperties.WATERLOGGED))normalized=normalized.setValue(BlockStateProperties.WATERLOGGED,wanted.getValue(BlockStateProperties.WATERLOGGED));
        if(wanted.hasProperty(BlockStateProperties.SLAB_TYPE)&&wanted.getValue(BlockStateProperties.SLAB_TYPE)==SlabType.DOUBLE)normalized=normalized.setValue(BlockStateProperties.SLAB_TYPE,SlabType.DOUBLE);
        if(wanted.is(Blocks.SNOW)&&predicted.getValue(SnowLayerBlock.LAYERS)<=wanted.getValue(SnowLayerBlock.LAYERS))normalized=normalized.setValue(SnowLayerBlock.LAYERS,wanted.getValue(SnowLayerBlock.LAYERS));
        if(wanted.getBlock() instanceof CandleBlock&&predicted.getValue(CandleBlock.CANDLES)<=wanted.getValue(CandleBlock.CANDLES))normalized=normalized.setValue(CandleBlock.CANDLES,wanted.getValue(CandleBlock.CANDLES));
        if(wanted.getBlock() instanceof SeaPickleBlock&&predicted.getValue(SeaPickleBlock.PICKLES)<=wanted.getValue(SeaPickleBlock.PICKLES))normalized=normalized.setValue(SeaPickleBlock.PICKLES,wanted.getValue(SeaPickleBlock.PICKLES));
        if(wanted.getBlock() instanceof TurtleEggBlock&&predicted.getValue(TurtleEggBlock.EGGS)<=wanted.getValue(TurtleEggBlock.EGGS))normalized=normalized.setValue(TurtleEggBlock.EGGS,wanted.getValue(TurtleEggBlock.EGGS));
        if(wanted.is(Blocks.NOTE_BLOCK))normalized=normalized.setValue(NoteBlock.NOTE,wanted.getValue(NoteBlock.NOTE));
        if(wanted.is(Blocks.REPEATER))normalized=normalized.setValue(RepeaterBlock.DELAY,wanted.getValue(RepeaterBlock.DELAY));
        if(wanted.is(Blocks.COMPARATOR))normalized=normalized.setValue(ComparatorBlock.MODE,wanted.getValue(ComparatorBlock.MODE));
        return normalized.equals(wanted);
    }
}
