package dev.betterlitematica.fabric;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import org.lwjgl.glfw.GLFW;
import java.util.HashSet;
import java.util.Set;

/** Physical state survives vanilla clearing key bindings when a menu opens. */
public final class EditorInput {
    private static final Set<Integer> scans=new HashSet<>();
    private EditorInput(){}
    public static void key(long window,int scan,int action){
        if(window!=MinecraftClient.getInstance().getWindow().getHandle())return;
        if(action==GLFW.GLFW_RELEASE)scans.remove(scan);else scans.add(scan);
    }
    static boolean down(MinecraftClient client,KeyBinding binding){
        var key=InputUtil.fromTranslationKey(binding.getBoundKeyTranslationKey());long window=client.getWindow().getHandle();
        if(key.getCode()<0)return false;
        return switch(key.getCategory()){
            case MOUSE->GLFW.glfwGetMouseButton(window,key.getCode())==GLFW.GLFW_PRESS;
            case KEYSYM->InputUtil.isKeyPressed(client.getWindow(),key.getCode());
            case SCANCODE->scans.contains(key.getCode());
        };
    }
}
