package dev.betterlitematica.fabric;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.client.MinecraftClient;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.BlockView;

public final class ProjectionReceiptTracker {
    private static final LongOpenHashSet observed=new LongOpenHashSet(4096);
    private static boolean active;
    private ProjectionReceiptTracker(){}
    public static void begin(){if(MinecraftClient.getInstance().isOnThread()){observed.clear();active=true;}}
    public static void observed(BlockView world,BlockPos pos){var c=MinecraftClient.getInstance();if(c.isOnThread()&&world==c.world&&active&&observed.size()<4096)observed.add(pos.asLong());}
    public static void refresh(BlockPos pos){var c=MinecraftClient.getInstance();if(c.isOnThread()&&c.world!=null&&(!active||!observed.contains(pos.asLong())))BetterLitematicaClient.projectionBlockChanged(c.world,pos);}
    public static void end(){if(MinecraftClient.getInstance().isOnThread()){active=false;observed.clear();}}
}
