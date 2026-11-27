package dev.betterlitematica.fabric;

public interface TerrainSectionOwner {
    long betterlitematica$generation();
    TerrainBuildStamp betterlitematica$stamp(boolean newBuild);
    void betterlitematica$published(long generation);
}
