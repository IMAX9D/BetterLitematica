package dev.betterlitematica.fabric;

/** Implemented on vanilla section handles; workers never read their mutable origin. */
public interface TerrainSectionOwner {
    long betterlitematica$generation();
    TerrainBuildStamp betterlitematica$stamp(boolean newBuild);
    void betterlitematica$published(long generation);
}
