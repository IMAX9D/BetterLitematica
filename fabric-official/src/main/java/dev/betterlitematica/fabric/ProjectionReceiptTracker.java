package dev.betterlitematica.fabric;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;

public final class ProjectionReceiptTracker {
    private static final LongOpenHashSet observed=new LongOpenHashSet(4096);
    private static boolean active;
    private ProjectionReceiptTracker(){}
    public static void begin(){if(Minecraft.getInstance().isSameThread()){observed.clear();active=true;}}
    public static void observed(net.minecraft.world.level.BlockGetter world,BlockPos pos){var c=Minecraft.getInstance();if(c.isSameThread()&&world==c.level&&active&&observed.size()<4096)observed.add(pos.asLong());}
    public static void refresh(BlockPos pos){var c=Minecraft.getInstance();if(c.isSameThread()&&c.level!=null&&(!active||!observed.contains(pos.asLong())))BetterLitematicaClient.projectionBlockChanged(c.level,pos);}
    public static void end(){if(Minecraft.getInstance().isSameThread()){active=false;observed.clear();}}
}
