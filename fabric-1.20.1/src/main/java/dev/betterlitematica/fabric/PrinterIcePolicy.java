package dev.betterlitematica.fabric;

import dev.betterlitematica.core.Vec3i;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.FluidBlock;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.util.math.BlockPos;
import java.util.List;
import java.util.function.Function;

final class PrinterIcePolicy {
    private PrinterIcePolicy(){}
    static boolean waterSource(BlockState state){
        return state!=null&&state.isOf(Blocks.WATER)&&state.get(FluidBlock.LEVEL)==0;
    }
    static BlockState target(BlockState wanted,BlockState actual,boolean retainIce){

        return retainIce&&waterSource(wanted)&&!waterSource(actual)?Blocks.ICE.getDefaultState():wanted;
    }
    static Function<Vec3i,ProjectionController.PrinterSample> view(
        Function<Vec3i,ProjectionController.PrinterSample> source,PrinterSettings settings,ClientWorld world){
        if(!settings.print||!settings.waterToIce||settings.autoBreakIce||world==null)return source;
        var skip=List.copyOf(settings.skip);
        return pos->{
            var sample=source.apply(pos);
            if(sample==null||!sample.inside()||!waterSource(sample.state())||PrinterRules.filtered(sample.state(),skip))return sample;
            var wanted=target(sample.state(),world.getBlockState(new BlockPos(pos.x(),pos.y(),pos.z())),true);
            return wanted==sample.state()?sample:new ProjectionController.PrinterSample(true,wanted);
        };
    }
}
