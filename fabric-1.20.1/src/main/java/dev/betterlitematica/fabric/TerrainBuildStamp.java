package dev.betterlitematica.fabric;

public record TerrainBuildStamp(TerrainSectionOwner owner,long generation,int x,int y,int z) {
    public boolean current(){return owner.betterlitematica$generation()==generation;}
}
