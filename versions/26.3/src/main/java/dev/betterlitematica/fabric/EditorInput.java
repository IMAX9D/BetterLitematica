package dev.betterlitematica.fabric;


import com.mojang.blaze3d.platform.InputConstants;
import java.util.HashSet;
import java.util.Set;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;

/** Physical state survives vanilla clearing key bindings when a menu opens. */
public final class EditorInput {
    private static final Set<Integer> scans=new HashSet<>();
    private EditorInput(){}
    public static void key(long window,int scan,int action){
        if(window!=Minecraft.getInstance().getWindow().handle())return;
        if(action==NativeInput.GLFW_RELEASE)scans.remove(scan);else scans.add(scan);
    }
    static boolean down(Minecraft client,KeyMapping binding){
        var key=InputConstants.getKey(binding.saveString());long window=client.getWindow().handle();
        if(key.getValue()<0)return false;
        return switch(key.getType()){
            case MOUSE->NativeInput.glfwGetMouseButton(window,NativeInput.legacyMouse(key.getValue()))==NativeInput.GLFW_PRESS;
            case KEYBOARD->InputConstants.isKeyDown(key.getValue());

        };
    }
}
