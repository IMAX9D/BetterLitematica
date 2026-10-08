package dev.betterlitematica.fabric;

import net.minecraft.block.*;
import net.minecraft.block.enums.*;
import net.minecraft.state.property.Properties;
import net.minecraft.registry.Registries;
import java.util.*;

/** Placement prediction is the authority; unsupported target states are never reported as placed. */
final class PrinterRules {
    private PrinterRules(){}
    static BlockState coralSubstitute(BlockState wanted){
        var id=Registries.BLOCK.getId(wanted.getBlock());String name=id.getPath();if(!id.getNamespace().equals("minecraft")||!name.startsWith("dead_")||!name.contains("coral"))return null;
        var live=Registries.BLOCK.get(net.minecraft.util.Identifier.of("minecraft",name.substring(5))).getDefaultState();if(live.isAir())return null;for(var property:wanted.getProperties())live=copyProperty(live,wanted,property);return live;
    }
    private static <T extends Comparable<T>> BlockState copyProperty(BlockState target,BlockState source,net.minecraft.state.property.Property<T> property){return target.contains(property)?target.with(property,source.get(property)):target;}
    static boolean pendingCoral(BlockState actual,BlockState wanted){var substitute=coralSubstitute(wanted);return substitute!=null&&substitute.equals(actual);}
    static boolean observerReady(net.minecraft.util.math.BlockPos start,java.util.function.Function<net.minecraft.util.math.BlockPos,BlockState> expected,java.util.function.Function<net.minecraft.util.math.BlockPos,BlockState> actual){
        var seen=new HashSet<net.minecraft.util.math.BlockPos>();var pos=start.toImmutable();for(int i=0;i<64;i++){if(!seen.add(pos))return false;var state=expected.apply(pos);if(state==null)return false;if(!state.isOf(Blocks.OBSERVER))return true;pos=pos.offset(state.get(ObserverBlock.FACING));var wanted=expected.apply(pos);if(wanted==null||!wanted.equals(actual.apply(pos)))return false;}return false;
    }
    static BlockState cycleDirection(BlockState state){
        if(state.contains(Properties.FACING))return state.cycle(Properties.FACING);if(state.contains(Properties.HORIZONTAL_FACING))return state.cycle(Properties.HORIZONTAL_FACING);if(state.contains(Properties.AXIS))return state.cycle(Properties.AXIS);if(state.contains(Properties.ROTATION))return state.with(Properties.ROTATION,(state.get(Properties.ROTATION)+4)%16);return state;
    }
    static String direction(BlockState state){if(state.contains(Properties.FACING))return state.get(Properties.FACING).asString();if(state.contains(Properties.HORIZONTAL_FACING))return state.get(Properties.HORIZONTAL_FACING).asString();if(state.contains(Properties.AXIS))return state.get(Properties.AXIS).asString();if(state.contains(Properties.ROTATION))return Integer.toString(state.get(Properties.ROTATION));return "";}
    static boolean fluidMatches(String fluidId,List<String> filters){for(String token:filters){String id=token.contains(":")?token:"minecraft:"+token;if(id.equals(fluidId)||fluidId.equals(id.replace(":",":flowing_")))return true;}return false;}
    static boolean filtered(BlockState state,List<String> filters){
        if(filters.isEmpty())return false;String id=Registries.BLOCK.getId(state.getBlock()).toString();
        for(String token:filters){String text=token.toLowerCase(Locale.ROOT);if(id.equals(text)||(!text.contains(":")&&id.endsWith(":"+text)))return true;}return false;
    }
    static boolean adjustable(BlockState actual,BlockState wanted,PrinterSettings s){
        if(actual.equals(wanted)||actual.isAir())return false;
        if(actual.getBlock()!=wanted.getBlock())return s.stripLogs&&isUnstripped(actual,wanted)||actual.isOf(Blocks.FLOWER_POT)&&wanted.getBlock() instanceof FlowerPotBlock||actual.isOf(Blocks.DIRT)&&wanted.isOf(Blocks.FARMLAND);
        if(wanted.contains(Properties.WATERLOGGED)&&actual.get(Properties.WATERLOGGED)!=wanted.get(Properties.WATERLOGGED))return true;
        if(wanted.contains(Properties.SLAB_TYPE)&&wanted.get(Properties.SLAB_TYPE)==SlabType.DOUBLE)return true;
        if(wanted.isOf(Blocks.SNOW)&&actual.get(SnowBlock.LAYERS)<wanted.get(SnowBlock.LAYERS))return true;
        if(wanted.getBlock() instanceof CandleBlock&&actual.get(CandleBlock.CANDLES)<wanted.get(CandleBlock.CANDLES))return true;
        if(wanted.getBlock() instanceof SeaPickleBlock&&actual.get(SeaPickleBlock.PICKLES)<wanted.get(SeaPickleBlock.PICKLES))return true;
        if(wanted.getBlock() instanceof TurtleEggBlock&&actual.get(TurtleEggBlock.EGGS)<wanted.get(TurtleEggBlock.EGGS))return true;
        if(s.noteTuning&&wanted.isOf(Blocks.NOTE_BLOCK)&&actual.get(NoteBlock.NOTE)!=wanted.get(NoteBlock.NOTE))return true;
        if(wanted.isOf(Blocks.REPEATER)&&actual.get(RepeaterBlock.DELAY)!=wanted.get(RepeaterBlock.DELAY))return true;
        if(wanted.isOf(Blocks.COMPARATOR)&&actual.get(ComparatorBlock.MODE)!=wanted.get(ComparatorBlock.MODE))return true;
        if(manualOpen(wanted)&&actual.get(Properties.OPEN)!=wanted.get(Properties.OPEN))return true;
        if(wanted.isOf(Blocks.LEVER)&&actual.get(Properties.POWERED)!=wanted.get(Properties.POWERED))return true;
        if(s.bonemeal&&wanted.getBlock() instanceof CropBlock crop&&crop.getAge(actual)<crop.getAge(wanted))return true;
        return s.composter&&wanted.isOf(Blocks.COMPOSTER)&&actual.get(ComposterBlock.LEVEL)<wanted.get(ComposterBlock.LEVEL);
    }
    static boolean manualOpen(BlockState state){return DoorBlock.canOpenByHand(state)||state.getBlock() instanceof TrapdoorBlock&&!state.isOf(Blocks.IRON_TRAPDOOR)||state.getBlock() instanceof FenceGateBlock;}
    static boolean isUnstripped(BlockState actual,BlockState wanted){
        String target=Registries.BLOCK.getId(wanted.getBlock()).toString(),source=Registries.BLOCK.getId(actual.getBlock()).toString();
        int colon=target.indexOf(':');return target.substring(colon+1).startsWith("stripped_")&&source.equals(target.substring(0,colon+1)+target.substring(colon+10));
    }
    static boolean placementMatches(BlockState predicted,BlockState wanted){
        if(predicted==null||predicted.getBlock()!=wanted.getBlock())return false;
        BlockState normalized=predicted;
        // A subsequent use action completes only these explicit, monotonic multi-step states.
        if(wanted.contains(Properties.WATERLOGGED))normalized=normalized.with(Properties.WATERLOGGED,wanted.get(Properties.WATERLOGGED));
        if(wanted.contains(Properties.SLAB_TYPE)&&wanted.get(Properties.SLAB_TYPE)==SlabType.DOUBLE)normalized=normalized.with(Properties.SLAB_TYPE,SlabType.DOUBLE);
        if(wanted.isOf(Blocks.SNOW)&&predicted.get(SnowBlock.LAYERS)<=wanted.get(SnowBlock.LAYERS))normalized=normalized.with(SnowBlock.LAYERS,wanted.get(SnowBlock.LAYERS));
        if(wanted.getBlock() instanceof CandleBlock&&predicted.get(CandleBlock.CANDLES)<=wanted.get(CandleBlock.CANDLES))normalized=normalized.with(CandleBlock.CANDLES,wanted.get(CandleBlock.CANDLES));
        if(wanted.getBlock() instanceof SeaPickleBlock&&predicted.get(SeaPickleBlock.PICKLES)<=wanted.get(SeaPickleBlock.PICKLES))normalized=normalized.with(SeaPickleBlock.PICKLES,wanted.get(SeaPickleBlock.PICKLES));
        if(wanted.getBlock() instanceof TurtleEggBlock&&predicted.get(TurtleEggBlock.EGGS)<=wanted.get(TurtleEggBlock.EGGS))normalized=normalized.with(TurtleEggBlock.EGGS,wanted.get(TurtleEggBlock.EGGS));
        if(wanted.isOf(Blocks.NOTE_BLOCK))normalized=normalized.with(NoteBlock.NOTE,wanted.get(NoteBlock.NOTE));
        if(wanted.isOf(Blocks.REPEATER))normalized=normalized.with(RepeaterBlock.DELAY,wanted.get(RepeaterBlock.DELAY));
        if(wanted.isOf(Blocks.COMPARATOR))normalized=normalized.with(ComparatorBlock.MODE,wanted.get(ComparatorBlock.MODE));
        return normalized.equals(wanted);
    }
}
