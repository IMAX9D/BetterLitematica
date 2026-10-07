package dev.betterlitematica.fabric;

import java.util.*;
import net.minecraft.block.entity.SignBlockEntity;
import net.minecraft.client.MinecraftClient;
import net.minecraft.network.packet.c2s.play.UpdateSignC2SPacket;
import net.minecraft.util.math.BlockPos;

/** Client-thread ownership of sign editor requests caused by our own placement packets.
 * Pausing stops new placements, but an already-sent placement still completes its text
 * transaction. Session changes and explicit manual interaction revoke that ownership.
 */
final class PrinterSigns {
    private static final int MAX_PENDING=128;
    private static final long TIMEOUT=10_000_000_000L;
    record Intent(SignPrintTarget target,boolean inline,long deadline) {}
    private final MinecraftClient client;
    private final ProjectionController controller;
    private final Map<Long,Intent> pending=new HashMap<>();
    private Object world,connection;
    private long epoch=-1,submitted;

    PrinterSigns(MinecraftClient client,ProjectionController controller){this.client=client;this.controller=controller;}
    private void sync(){
        if(world!=client.world||connection!=client.getNetworkHandler()||epoch!=controller.sessionEpoch()){
            clear();world=client.world;connection=client.getNetworkHandler();epoch=controller.sessionEpoch();
        }
        long now=System.nanoTime();pending.values().removeIf(intent->now>=intent.deadline());
    }
    void tick(){
        sync();pending.entrySet().removeIf(entry->{
            var intent=entry.getValue();
            var pos=BlockPos.fromLong(entry.getKey());
            return intent.inline()&&client.world!=null&&WorldChunks.loaded(client.world,pos)
                &&client.world.getBlockEntity(pos) instanceof SignBlockEntity sign&&intent.target().matches(sign);
        });
    }
    void clear(){pending.clear();}
    void manual(BlockPos pos){sync();pending.remove(pos.asLong());}
    Intent arm(BlockPos pos,SignPrintTarget target,boolean inline){
        sync();if(client.world==null||client.getNetworkHandler()==null||pending.size()>=MAX_PENDING||pending.containsKey(pos.asLong()))return null;
        var intent=new Intent(target,inline,System.nanoTime()+TIMEOUT);pending.put(pos.asLong(),intent);return intent;
    }
    void abandon(BlockPos pos,Intent intent){pending.remove(pos.asLong(),intent);}
    int pending(){return pending.size();}
    long submitted(){return submitted;}

    boolean opened(SignBlockEntity sign,boolean front){
        sync();var intent=pending.remove(sign.getPos().asLong());
        if(intent==null||client.player==null||client.world==null||client.getNetworkHandler()==null)return false;
        if(sign.getCachedState().getBlock()!=intent.target().state().getBlock())return false;
        // Respond only to the side the server opened. Creating and closing a vanilla
        // editor instead would send its old lines from removed(), overwriting our text.
        var lines=intent.target().lines(front);
        client.getNetworkHandler().sendPacket(new UpdateSignC2SPacket(sign.getPos(),front,lines[0],lines[1],lines[2],lines[3]));
        submitted++;return true;
    }
}
