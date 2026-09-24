package dev.betterlitematica.core;
/** Coordinates are relative to a region minimum, not global Minecraft section coordinates. */
public record SectionKey(int region, int x, int y, int z) {
    public SectionKey { if(region<0||x<0||y<0||z<0) throw new IllegalArgumentException("Negative section key"); }
}
