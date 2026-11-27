package dev.betterlitematica.fabric;
import java.util.concurrent.atomic.AtomicLong;

public final class TerrainRebuildUpdates {
    private static final AtomicLong REVISION=new AtomicLong();
    private TerrainRebuildUpdates(){}
    public static long revision(){return REVISION.get();}
    public static void becameDirty(){TerrainSectionChanges.unlocatedChange();REVISION.incrementAndGet();}
    public static void becameDirty(TerrainSectionChanges.Owner owner){owner.betterlitematica$sectionChanged();REVISION.incrementAndGet();}
}
