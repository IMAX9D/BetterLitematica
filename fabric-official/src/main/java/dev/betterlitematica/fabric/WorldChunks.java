package dev.betterlitematica.fabric;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.LevelReader;

/** ClientWorld's 1.20.1 WorldView query is always true; never use it to read real client blocks. */
final class WorldChunks {
    private WorldChunks(){}
    static boolean loaded(LevelReader world,BlockPos pos){return loaded(world,pos.getX()>>4,pos.getZ()>>4);}
    static boolean loaded(LevelReader world,int x,int z){
        return world instanceof ClientLevel client?client.getChunkSource().hasChunk(x,z):world!=null&&world.hasChunk(x,z);
    }
}
