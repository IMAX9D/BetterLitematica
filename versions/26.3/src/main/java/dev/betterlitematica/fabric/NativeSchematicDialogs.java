package dev.betterlitematica.fabric;

import java.io.IOException;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.client.Minecraft;
import org.lwjgl.sdl.SDL_DialogFileCallback;
import org.lwjgl.sdl.SDL_DialogFileFilter;
import static org.lwjgl.sdl.SDLDialog.*;
import static org.lwjgl.sdl.SDLProperties.*;
import static org.lwjgl.system.MemoryUtil.*;

/** Uses Minecraft's SDL native dialog (Explorer on Windows), without an extra native library. */
final class NativeSchematicDialogs {
    private static final AtomicReference<CompletableFuture<Optional<String>>> ACTIVE=new AtomicReference<>();
    // Native filter strings share the callback lifetime; an asynchronous dialog must not borrow a stack buffer.
    private static final java.nio.ByteBuffer FILTER_NAME=memUTF8("投影文件"),FILTER_PATTERN=memUTF8("litematic;schem;schematic;nbt");
    private static final SDL_DialogFileFilter FILTER=SDL_DialogFileFilter.calloc().set(FILTER_NAME,FILTER_PATTERN);
    // One callback lives for the client lifetime. SDL may invoke it on its dialog thread.
    private static final SDL_DialogFileCallback CALLBACK=SDL_DialogFileCallback.create((userdata,files,filter)->{
        var result=ACTIVE.getAndSet(null);if(result==null)return;
        try{
            if(files==NULL){result.completeExceptionally(failed());return;}
            long first=memGetAddress(files);
            // SDL frees its UTF-8 strings on return; copy before publishing to the client thread.
            result.complete(first==NULL?Optional.empty():Optional.of(memUTF8(first)));
        }catch(RuntimeException e){result.completeExceptionally(failed());}
    });
    private NativeSchematicDialogs(){}
    static CompletableFuture<Optional<String>> folder(Minecraft client,String initial){return choose(client,initial,true);}
    static CompletableFuture<Optional<String>> file(Minecraft client,String initial){return choose(client,initial,false);}
    private static CompletableFuture<Optional<String>> choose(Minecraft client,String initial,boolean folder){
        var result=new CompletableFuture<Optional<String>>();
        if(!ACTIVE.compareAndSet(null,result))return CompletableFuture.failedFuture(new IOException("系统选择窗口已打开"));
        int properties=0;
        try{
            properties=SDL_CreateProperties();
            if(properties==0||!SDL_SetPointerProperty(properties,SDL_PROP_FILE_DIALOG_WINDOW_POINTER,client.getWindow().handle())
                ||!SDL_SetStringProperty(properties,SDL_PROP_FILE_DIALOG_TITLE_STRING,folder?"加载投影文件夹":"加载投影文件")
                ||!SDL_SetStringProperty(properties,SDL_PROP_FILE_DIALOG_ACCEPT_STRING,folder?"加载文件夹":"加载文件")
                ||!SDL_SetStringProperty(properties,SDL_PROP_FILE_DIALOG_CANCEL_STRING,"取消")
                ||!SDL_SetStringProperty(properties,SDL_PROP_FILE_DIALOG_LOCATION_STRING,initial)
                ||!SDL_SetBooleanProperty(properties,SDL_PROP_FILE_DIALOG_MANY_BOOLEAN,false))throw failed();
            if(!folder&&(!SDL_SetPointerProperty(properties,SDL_PROP_FILE_DIALOG_FILTERS_POINTER,FILTER.address())
                ||!SDL_SetNumberProperty(properties,SDL_PROP_FILE_DIALOG_NFILTERS_NUMBER,1)))throw failed();
            // Called by a menu action on Minecraft's main thread. SDL copies the properties.
            SDL_ShowFileDialogWithProperties(folder?SDL_FILEDIALOG_OPENFOLDER:SDL_FILEDIALOG_OPENFILE,CALLBACK,NULL,properties);
        }catch(IOException|RuntimeException|LinkageError e){ACTIVE.compareAndSet(result,null);result.completeExceptionally(failed());}
        finally{if(properties!=0)SDL_DestroyProperties(properties);}
        return result;
    }
    private static IOException failed(){return new IOException("无法打开系统选择窗口，请重试");}
}
