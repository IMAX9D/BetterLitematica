package dev.betterlitematica.fabric;

import net.minecraft.block.BlockState;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

/** Only the exact synchronous schematic write suppresses placement/removal callbacks. */
public final class PasteBlockUpdates {
    private static final class Context { World world; BlockPos pos; }
    private static final ThreadLocal<Context> ACTIVE=ThreadLocal.withInitial(Context::new);
    private PasteBlockUpdates(){}

    public static boolean quiet(World world,BlockPos pos){
        var context=ACTIVE.get();return context.world==world&&context.pos!=null&&context.pos.equals(pos);
    }

    static boolean set(ServerWorld world,BlockPos pos,BlockState state,int flags){
        var context=ACTIVE.get();World previousWorld=context.world;BlockPos previousPos=context.pos;
        context.world=world;context.pos=pos;
        try{return world.setBlockState(pos,state,flags);}
        finally{context.world=previousWorld;context.pos=previousPos;}
    }
}
