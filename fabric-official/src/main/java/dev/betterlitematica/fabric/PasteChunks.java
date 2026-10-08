package dev.betterlitematica.fabric;

import net.minecraft.server.level.ChunkResult;
import dev.betterlitematica.core.PlacementBounds;

import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.chunk.LevelChunk;
import java.util.*;
import java.util.concurrent.CompletableFuture;

/** Server-thread-only temporary chunk ownership. Never blocks on generation or joins an unfinished future. */
final class PasteChunks implements AutoCloseable {
    static final int LIMIT=64,REQUESTS_PER_TICK=4;
    private static final TicketType TICKET=new TicketType(Long.MAX_VALUE,TicketType.FLAG_LOADING);
    private static final Map<ServerChunkCache,Map<Long,Integer>> OWNERS=new WeakHashMap<>();
    static void initialize(){net.minecraft.core.Registry.register(net.minecraft.core.registries.BuiltInRegistries.TICKET_TYPE,net.minecraft.resources.Identifier.fromNamespaceAndPath("betterlitematica","paste"),TICKET);}
    private void acquire(ChunkPos pos){var owners=OWNERS.computeIfAbsent(manager,key->new HashMap<>());long key=pos.pack();int count=owners.getOrDefault(key,0);if(count==0)manager.addTicketWithRadius(TICKET,pos,0);owners.put(key,count+1);}
    private void relinquish(ChunkPos pos){var owners=OWNERS.get(manager);long key=pos.pack();if(owners==null||!owners.containsKey(key))throw new IllegalStateException("粘贴区块票据失配");int count=owners.get(key);if(count==1){manager.removeTicketWithRadius(TICKET,pos,0);owners.remove(key);if(owners.isEmpty())OWNERS.remove(manager);}else owners.put(key,count-1);}
    private static final class Entry {
        final ChunkPos pos;final CompletableFuture<ChunkResult<ChunkAccess>> future;
        int users;LevelChunk chunk;
        Entry(ChunkPos pos,CompletableFuture<ChunkResult<ChunkAccess>> future){this.pos=pos;this.future=future;}
    }
    private final ServerChunkCache manager;
    private final LinkedHashMap<Long,Entry> entries=new LinkedHashMap<>(LIMIT,.75f,true);
    private int requests;
    PasteChunks(ServerLevel world){manager=world.getChunkSource();}
    void beginTick(){requests=REQUESTS_PER_TICK;}
    boolean retain(PlacementBounds bounds){
        int missing=0,loading=0;
        for(int z=bounds.min().z()>>4;z<=bounds.max().z()>>4;z++)for(int x=bounds.min().x()>>4;x<=bounds.max().x()>>4;x++)if(!entries.containsKey(ChunkPos.pack(x,z))){missing++;if(manager.getChunkNow(x,z)==null)loading++;}
        if(loading>requests)return false;
        while(entries.size()+missing>LIMIT){
            boolean freed=false;var it=entries.entrySet().iterator();
            while(it.hasNext()){var candidate=it.next();var pos=candidate.getValue().pos;boolean needed=pos.x()>=(bounds.min().x()>>4)&&pos.x()<=(bounds.max().x()>>4)&&pos.z()>=(bounds.min().z()>>4)&&pos.z()<=(bounds.max().z()>>4);if(candidate.getValue().users==0&&!needed){remove(candidate.getKey(),candidate.getValue());it.remove();freed=true;break;}}
            if(!freed)return false;
        }
        for(int z=bounds.min().z()>>4;z<=bounds.max().z()>>4;z++)for(int x=bounds.min().x()>>4;x<=bounds.max().x()>>4;x++){
            long key=ChunkPos.pack(x,z);var entry=entries.get(key);
            if(entry==null){
                var pos=new ChunkPos(x,z);acquire(pos);
                try{var loaded=manager.getChunkNow(x,z);entry=new Entry(pos,loaded==null?manager.getChunkFuture(x,z,ChunkStatus.FULL,true):CompletableFuture.completedFuture(ChunkResult.of(loaded)));entry.chunk=loaded;entries.put(key,entry);if(loaded==null)requests--;}
                catch(RuntimeException failure){relinquish(pos);throw failure;}
            }
            entry.users++;
        }
        return true;
    }
    LevelChunk[] ready(PlacementBounds bounds){
        int nx=(bounds.max().x()>>4)-(bounds.min().x()>>4)+1,nz=(bounds.max().z()>>4)-(bounds.min().z()>>4)+1;
        LevelChunk[] chunks=new LevelChunk[nx*nz];int index=0;
        for(int z=bounds.min().z()>>4;z<=bounds.max().z()>>4;z++)for(int x=bounds.min().x()>>4;x<=bounds.max().x()>>4;x++){
            var entry=entries.get(ChunkPos.pack(x,z));if(entry==null)throw new IllegalStateException("粘贴区块已释放");
            if(entry.chunk==null){var value=entry.future.getNow(null);if(value==null)return null;var chunk=value.orElseThrow(()->new IllegalStateException("无法准备粘贴区块："+entry.pos));if(!(chunk instanceof LevelChunk ready))throw new IllegalStateException("粘贴区块尚未完整加载");entry.chunk=ready;}
            chunks[index++]=entry.chunk;
        }
        return chunks;
    }
    void release(PlacementBounds bounds){
        for(int z=bounds.min().z()>>4;z<=bounds.max().z()>>4;z++)for(int x=bounds.min().x()>>4;x<=bounds.max().x()>>4;x++){
            var entry=entries.get(ChunkPos.pack(x,z));if(entry==null||entry.users==0)throw new IllegalStateException("粘贴区块引用失配");entry.users--;
        }
    }
    int retained(){return entries.size();}
    private void remove(long key,Entry entry){relinquish(entry.pos);}
    @Override public void close(){for(var entry:entries.entrySet())remove(entry.getKey(),entry.getValue());entries.clear();}
}
