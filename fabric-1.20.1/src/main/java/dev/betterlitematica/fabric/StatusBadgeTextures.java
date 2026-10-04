package dev.betterlitematica.fabric;

import dev.betterlitematica.runtime.StatusBadgeArt;
import dev.betterlitematica.runtime.WeightedLru;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.texture.NativeImageBackedTexture;
import net.minecraft.util.Identifier;
import java.util.*;
import java.util.concurrent.*;

/** Main-thread texture ownership; bounded pure-vector rasterization on one background worker. */
final class StatusBadgeTextures implements AutoCloseable {
    record Key(StatusBadgeArt.Kind kind,int pixels,boolean dark) {}
    record Texture(Identifier id,int size,int padding) {long bytes(){return (long)size*size*4;}}
    private final ThreadPoolExecutor worker=new ThreadPoolExecutor(1,1,0,TimeUnit.MILLISECONDS,
        new ArrayBlockingQueue<>(4),r->{var t=new Thread(r,"betterlitematica-hud-icons");t.setDaemon(true);t.setPriority(Thread.MIN_PRIORITY);return t;});
    private final WeightedLru<Key,Texture> textures=new WeightedLru<>(1L<<20,24,Texture::bytes,
        value->MinecraftClient.getInstance().getTextureManager().destroyTexture(value.id()));
    private final Map<Key,Future<StatusBadgeArt.Raster>> pending=new LinkedHashMap<>();
    private final Set<Key> failed=new HashSet<>();
    private int pixels;private boolean dark,closed;

    void prepare(int size,boolean darkTheme){
        if(closed)return;
        if(pixels!=size||dark!=darkTheme){cancel();pixels=size;dark=darkTheme;}
        drain();
        // Once warmed, every state at this resolution is ready for immediate toggles.
        for(var kind:StatusBadgeArt.Kind.values()){
            var key=new Key(kind,size,darkTheme);if(textures.contains(key)||pending.containsKey(key)||failed.contains(key))continue;
            try{pending.put(key,worker.submit(()->StatusBadgeArt.raster(kind,size,darkTheme)));}
            catch(RejectedExecutionException busy){break;}
        }
    }
    Texture get(StatusBadgeArt.Kind kind,int size,boolean darkTheme){return textures.get(new Key(kind,size,darkTheme));}
    private void drain(){
        int budget=2;
        for(var it=pending.entrySet().iterator();it.hasNext()&&budget>0;){
            var entry=it.next();if(!entry.getValue().isDone())continue;it.remove();budget--;
            try{
                var raster=entry.getValue().get();int width=raster.width();
                if(entry.getKey().pixels()!=pixels||entry.getKey().dark()!=dark)continue;
                var image=new NativeImage(width,raster.height(),false);var argb=raster.argb();
                for(int y=0;y<raster.height();y++)for(int x=0;x<width;x++){int c=argb[x+y*width];image.setColor(x,y,(c&0xff00ff00)|((c&255)<<16)|((c>>>16)&255));}
                var nativeTexture=new NativeImageBackedTexture(image);nativeTexture.setFilter(false,false);
                Identifier id;
                try{id=MinecraftClient.getInstance().getTextureManager().registerDynamicTexture("betterlitematica-status",nativeTexture);}
                catch(RuntimeException failure){nativeTexture.close();throw failure;}
                textures.put(entry.getKey(),new Texture(id,width,(width-entry.getKey().pixels())/2));
            }catch(InterruptedException interrupted){Thread.currentThread().interrupt();return;}
            catch(CancellationException ignored){}
            catch(ExecutionException|RuntimeException failure){failed.add(entry.getKey());BetterLitematicaClient.LOGGER.warn("HUD icon unavailable",failure);}
        }
    }
    private void cancel(){for(var task:pending.values())task.cancel(true);pending.clear();failed.clear();worker.purge();}
    void clear(){cancel();pixels=0;textures.close();}
    @Override public void close(){closed=true;clear();worker.shutdownNow();}
}
