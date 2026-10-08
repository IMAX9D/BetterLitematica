package dev.betterlitematica.fabric;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

/** Only the exact synchronous schematic write suppresses placement/removal callbacks. */
public final class PasteBlockUpdates {
    private static final class Context { Level world; BlockPos pos; }
    private static final ThreadLocal<Context> ACTIVE=ThreadLocal.withInitial(Context::new);
    private PasteBlockUpdates(){}

    public static boolean quiet(Level world,BlockPos pos){
        var context=ACTIVE.get();return context.world==world&&context.pos!=null&&context.pos.equals(pos);
    }

    static boolean set(ServerLevel world,BlockPos pos,BlockState state,int flags){
        var context=ACTIVE.get();Level previousWorld=context.world;BlockPos previousPos=context.pos;
        context.world=world;context.pos=pos;
        try{return world.setBlock(pos,state,flags);}
        finally{context.world=previousWorld;context.pos=previousPos;}
    }
}
