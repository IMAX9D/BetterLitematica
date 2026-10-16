package dev.betterlitematica.fabric;
import dev.betterlitematica.runtime.WindowsSchematicDialog;
import java.io.IOException;
import java.util.Optional;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import net.minecraft.client.Minecraft;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.util.tinyfd.TinyFileDialogs;
final class NativeSchematicDialogs {
    private static final AtomicBoolean ACTIVE=new AtomicBoolean();
    private static final ExecutorService WORKER=Executors.newSingleThreadExecutor(r->{var thread=new Thread(r,"betterlitematica-native-dialog");thread.setDaemon(true);return thread;});
    private NativeSchematicDialogs(){}
    static CompletableFuture<Optional<String>> folder(Minecraft client,String initial){return choose(client,initial,true);}
    static CompletableFuture<Optional<String>> file(Minecraft client,String initial){return choose(client,initial,false);}
    private static CompletableFuture<Optional<String>> choose(Minecraft client,String initial,boolean folder){
        if(!ACTIVE.compareAndSet(false,true))return CompletableFuture.failedFuture(new IOException("系统选择窗口已打开"));
        boolean windows=org.lwjgl.system.Platform.get()==org.lwjgl.system.Platform.WINDOWS;long owner;
        try{owner=windows?org.lwjgl.glfw.GLFWNativeWin32.glfwGetWin32Window(client.getWindow().handle()):0;}
        catch(RuntimeException|LinkageError error){ACTIVE.set(false);return CompletableFuture.failedFuture(new IOException("无法打开系统选择窗口，请重试"));}
        var result=new CompletableFuture<Optional<String>>();
        WORKER.execute(()->{try{
            if(result.isCancelled())return;
            if(windows){result.complete(Optional.ofNullable(WindowsSchematicDialog.choose(owner,folder,initial)));return;}
            if(folder){result.complete(Optional.ofNullable(TinyFileDialogs.tinyfd_selectFolderDialog("加载投影文件夹",initial)));return;}
            try(var stack=MemoryStack.stackPush()){
                var patterns=stack.mallocPointer(4);for(String extension:new String[]{"*.litematic","*.schem","*.schematic","*.nbt"})patterns.put(stack.UTF8(extension));patterns.flip();
                result.complete(Optional.ofNullable(TinyFileDialogs.tinyfd_openFileDialog("加载投影文件",initial,patterns,"投影文件",false)));
            }
        }catch(IOException|RuntimeException|LinkageError error){result.completeExceptionally(new IOException("无法打开系统选择窗口，请重试",error));}
        finally{ACTIVE.set(false);}});
        return result;
    }
}
