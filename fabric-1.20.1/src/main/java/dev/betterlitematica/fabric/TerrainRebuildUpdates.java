package dev.betterlitematica.fabric;
import java.util.concurrent.atomic.AtomicLong;
/** A new dirty section can join the compact work list; completed work is pruned on every reuse. */
public final class TerrainRebuildUpdates {
    private static final AtomicLong REVISION=new AtomicLong();
    private TerrainRebuildUpdates(){}
    public static long revision(){return REVISION.get();}
    public static void becameDirty(){REVISION.incrementAndGet();}
}
