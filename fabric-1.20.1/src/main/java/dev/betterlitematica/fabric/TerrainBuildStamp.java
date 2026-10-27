package dev.betterlitematica.fabric;

/** Immutable coordinates and generation captured before a terrain job reaches a worker. */
public record TerrainBuildStamp(TerrainSectionOwner owner,long generation,int x,int y,int z) {
    public boolean current(){return owner.betterlitematica$generation()==generation;}
}
