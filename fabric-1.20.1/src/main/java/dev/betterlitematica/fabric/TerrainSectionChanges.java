package dev.betterlitematica.fabric;

import java.util.BitSet;
import java.util.concurrent.atomic.AtomicLong;

public final class TerrainSectionChanges {
    public interface Owner {
        void betterlitematica$watch(TerrainSectionChanges changes);
        void betterlitematica$sectionChanged();
    }

    private static final AtomicLong unlocated = new AtomicLong();
    private BitSet pending;

    public TerrainSectionChanges(int sections) {
        pending = new BitSet(sections);
    }

    public synchronized void mark(int section) {
        pending.set(section);
    }

    synchronized BitSet drain(BitSet spare) {
        var result = pending;
        pending = spare;
        return result;
    }

    synchronized boolean hasPending() { return !pending.isEmpty(); }

    static void unlocatedChange() { unlocated.incrementAndGet(); }
    static long unlocatedRevision() { return unlocated.get(); }
}
