package dev.betterlitematica.fabric;

import net.minecraft.client.world.ClientWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.WorldView;

final class WorldChunks {
    private WorldChunks(){}
    static boolean loaded(WorldView world,BlockPos pos){return loaded(world,pos.getX()>>4,pos.getZ()>>4);}
    static boolean loaded(WorldView world,int x,int z){
        return world instanceof ClientWorld client?client.getChunkManager().isChunkLoaded(x,z):world!=null&&world.isChunkLoaded(x,z);
    }
}
