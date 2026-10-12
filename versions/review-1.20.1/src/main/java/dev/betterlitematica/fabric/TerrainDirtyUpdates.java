package dev.betterlitematica.fabric;
import java.util.concurrent.atomic.AtomicLong;
/** Only newly dirty sections require rescanning the visible list. Clean entries can be pruned in place. */
public final class TerrainDirtyUpdates {
    private static final AtomicLong REVISION=new AtomicLong();
    private TerrainDirtyUpdates(){}
    public static long revision(){return REVISION.get();}
    public static void becameDirty(){REVISION.incrementAndGet();}
}
