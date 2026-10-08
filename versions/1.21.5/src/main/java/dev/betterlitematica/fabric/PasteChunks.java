package dev.betterlitematica.fabric;


import dev.betterlitematica.core.PlacementBounds;
import net.minecraft.server.world.*;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.chunk.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;

/** Server-thread-only temporary chunk ownership. Never blocks on generation or joins an unfinished future. */
final class PasteChunks implements AutoCloseable {
    static final int LIMIT=64,REQUESTS_PER_TICK=4;
    // Ticket matching uses type identity: one nonpersistent type per operation preserves ownership.
    private final ChunkTicketType TICKET=new ChunkTicketType(0,false,ChunkTicketType.Use.LOADING);
    private static final class Entry {
        final ChunkPos pos;final CompletableFuture<OptionalChunk<Chunk>> future;
        int users;WorldChunk chunk;
        Entry(ChunkPos pos,CompletableFuture<OptionalChunk<Chunk>> future){this.pos=pos;this.future=future;}
    }
    private final ServerChunkManager manager;
    private final LinkedHashMap<Long,Entry> entries=new LinkedHashMap<>(LIMIT,.75f,true);
    private int requests;
    PasteChunks(ServerWorld world){manager=world.getChunkManager();}
    void beginTick(){requests=REQUESTS_PER_TICK;}
    boolean retain(PlacementBounds bounds){
        int missing=0,loading=0;
        for(int z=bounds.min().z()>>4;z<=bounds.max().z()>>4;z++)for(int x=bounds.min().x()>>4;x<=bounds.max().x()>>4;x++)if(!entries.containsKey(ChunkPos.toLong(x,z))){missing++;if(manager.getWorldChunk(x,z)==null)loading++;}
        if(loading>requests)return false;
        while(entries.size()+missing>LIMIT){
            boolean freed=false;var it=entries.entrySet().iterator();
            while(it.hasNext()){var candidate=it.next();var pos=candidate.getValue().pos;boolean needed=pos.x>=(bounds.min().x()>>4)&&pos.x<=(bounds.max().x()>>4)&&pos.z>=(bounds.min().z()>>4)&&pos.z<=(bounds.max().z()>>4);if(candidate.getValue().users==0&&!needed){remove(candidate.getKey(),candidate.getValue());it.remove();freed=true;break;}}
            if(!freed)return false;
        }
        for(int z=bounds.min().z()>>4;z<=bounds.max().z()>>4;z++)for(int x=bounds.min().x()>>4;x<=bounds.max().x()>>4;x++){
            long key=ChunkPos.toLong(x,z);var entry=entries.get(key);
            if(entry==null){
                var pos=new ChunkPos(x,z);manager.addTicket(TICKET,pos,0);
                try{var loaded=manager.getWorldChunk(x,z);entry=new Entry(pos,loaded==null?manager.getChunkFuture(x,z,ChunkStatus.FULL,true):CompletableFuture.completedFuture(OptionalChunk.of(loaded)));entry.chunk=loaded;entries.put(key,entry);if(loaded==null)requests--;}
                catch(RuntimeException failure){manager.removeTicket(TICKET,pos,0);throw failure;}
            }
            entry.users++;
        }
        return true;
    }
    WorldChunk[] ready(PlacementBounds bounds){
        int nx=(bounds.max().x()>>4)-(bounds.min().x()>>4)+1,nz=(bounds.max().z()>>4)-(bounds.min().z()>>4)+1;
        WorldChunk[] chunks=new WorldChunk[nx*nz];int index=0;
        for(int z=bounds.min().z()>>4;z<=bounds.max().z()>>4;z++)for(int x=bounds.min().x()>>4;x<=bounds.max().x()>>4;x++){
            var entry=entries.get(ChunkPos.toLong(x,z));if(entry==null)throw new IllegalStateException("粘贴区块已释放");
            if(entry.chunk==null){var value=entry.future.getNow(null);if(value==null)return null;var chunk=value.orElseThrow(()->new IllegalStateException("无法准备粘贴区块："+entry.pos));if(!(chunk instanceof WorldChunk ready))throw new IllegalStateException("粘贴区块尚未完整加载");entry.chunk=ready;}
            chunks[index++]=entry.chunk;
        }
        return chunks;
    }
    void release(PlacementBounds bounds){
        for(int z=bounds.min().z()>>4;z<=bounds.max().z()>>4;z++)for(int x=bounds.min().x()>>4;x<=bounds.max().x()>>4;x++){
            var entry=entries.get(ChunkPos.toLong(x,z));if(entry==null||entry.users==0)throw new IllegalStateException("粘贴区块引用失配");entry.users--;
        }
    }
    int retained(){return entries.size();}
    private void remove(long key,Entry entry){manager.removeTicket(TICKET,entry.pos,0);}
    @Override public void close(){for(var entry:entries.entrySet())remove(entry.getKey(),entry.getValue());entries.clear();}
}
