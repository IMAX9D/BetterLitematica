package dev.betterlitematica.core;

import java.util.List;

/** Constant-memory traversal, including implicit air cells. The caller owns its tick/time budget. */
public final class RegionCursor {
    private final List<Region> regions;
    private int region, x, y, z;
    private long processed;
    public RegionCursor(List<Region> regions) { this.regions = List.copyOf(regions); }
    public boolean done() { return region >= regions.size(); }
    public int region() { return region; }
    public Vec3i local() { if (done()) throw new IllegalStateException("Traversal is complete"); return regions.get(region).min().add(new Vec3i(x, y, z)); }
    public SectionKey section() { return new SectionKey(region, x / 16, y / 16, z / 16); }
    public int sectionIndex() { return PackedSection.index(x & 15, y & 15, z & 15); }
    public long processed() { return processed; }
    public void advance() {
        if (done()) return; processed++; Vec3i size = regions.get(region).size();
        if (++x == size.x()) { x = 0; if (++z == size.z()) { z = 0; if (++y == size.y()) { y = 0; region++; } } }
    }
}
