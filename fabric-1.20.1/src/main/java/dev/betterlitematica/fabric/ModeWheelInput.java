package dev.betterlitematica.fabric;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import org.lwjgl.glfw.GLFW;

/** Physical held-key screen ownership; no queued open may survive a screen, focus or world transition. */
final class ModeWheelInput {
    private final MinecraftClient client;
    private final ProjectionController controller;
    private final Lifecycle lifecycle=new Lifecycle();
    private boolean mouseRelease;
    ModeWheelInput(MinecraftClient client,ProjectionController controller){this.client=client;this.controller=controller;}
    static boolean reservesTab(MinecraftClient client){return client.world!=null&&client.player!=null;}
    boolean key(long window,int key,int scan,int action){
        if(window!=client.getWindow().getHandle()||key!=GLFW.GLFW_KEY_TAB)return false;
        boolean down=action!=GLFW.GLFW_RELEASE;
        var decision=lifecycle.update(client.world,client.getNetworkHandler(),client.isWindowFocused(),client.player!=null,client.currentScreen,down,action==GLFW.GLFW_PRESS);
        apply(decision);
        if(decision.consume()||reservesTab(client))clearTab();
        return decision.consume()||reservesTab(client)&&client.currentScreen==null;
    }
    void tick(){
        boolean held=InputUtil.isKeyPressed(client.getWindow().getHandle(),GLFW.GLFW_KEY_TAB);
        apply(lifecycle.update(client.world,client.getNetworkHandler(),client.isWindowFocused(),client.player!=null,client.currentScreen,held,false));
        if(reservesTab(client))clearTab();
        if(mouseRelease&&!mouseDown())mouseRelease=false;
        if(blocksWorldInput())clearMouseBindings();
    }
    boolean mouse(long window,int button,int action){
        if(window!=client.getWindow().getHandle())return false;
        if(client.currentScreen instanceof ModeWheelScreen){if(action==GLFW.GLFW_PRESS)mouseRelease=true;return false;}
        if(lifecycle.owned()!=null){mouseRelease|=mouseDown();clearMouseBindings();}
        boolean consume=mouseRelease&&client.currentScreen==null;
        if(consume)clearMouseBindings();
        if(action==GLFW.GLFW_RELEASE&&!mouseDown())mouseRelease=false;
        return consume;
    }
    boolean blocksWorldInput(){return client.currentScreen instanceof ModeWheelScreen||lifecycle.claimed()||mouseRelease;}
    void clear(){
        var owned=lifecycle.owned();lifecycle.reset();
        if(owned!=null&&client.currentScreen==owned)((Screen)owned).close();
        mouseRelease|=mouseDown();clearMouseBindings();clearTab();
    }
    private void apply(Lifecycle.Decision decision){
        if(decision.close()!=null){mouseRelease|=mouseDown();clearMouseBindings();if(client.currentScreen==decision.close())((Screen)decision.close()).close();}
        if(decision.open()){
            clearMouseBindings();mouseRelease|=mouseDown();var screen=new ModeWheelScreen(controller);lifecycle.bind(screen);client.setScreen(screen);clearTab();
        }
    }
    private boolean mouseDown(){long window=client.getWindow().getHandle();return GLFW.glfwGetMouseButton(window,GLFW.GLFW_MOUSE_BUTTON_LEFT)==GLFW.GLFW_PRESS||GLFW.glfwGetMouseButton(window,GLFW.GLFW_MOUSE_BUTTON_RIGHT)==GLFW.GLFW_PRESS||GLFW.glfwGetMouseButton(window,GLFW.GLFW_MOUSE_BUTTON_MIDDLE)==GLFW.GLFW_PRESS;}
    private void clearMouseBindings(){clear(client.options.attackKey);clear(client.options.useKey);clear(client.options.pickItemKey);}
    private static void clear(KeyBinding binding){binding.setPressed(false);while(binding.wasPressed()){};}
    private void clearTab(){
        int scan=GLFW.glfwGetKeyScancode(GLFW.GLFW_KEY_TAB);
        KeyBinding.setKeyPressed(InputUtil.Type.KEYSYM.createFromCode(GLFW.GLFW_KEY_TAB),false);
        if(scan>=0)KeyBinding.setKeyPressed(InputUtil.Type.SCANCODE.createFromCode(scan),false);
        if(client.options.playerListKey.matchesKey(GLFW.GLFW_KEY_TAB,scan))clear(client.options.playerListKey);
    }
    /** Package-visible production state machine for deterministic lifecycle regressions. */
    static final class Lifecycle {
        record Decision(boolean consume,boolean open,Object close){}
        private Object world,connection,owned;
        private boolean initialized,down,blocked=true,claimed;
        Object owned(){return owned;}
        boolean claimed(){return claimed;}
        void bind(Object screen){owned=screen;}
        void reset(){world=null;connection=null;owned=null;initialized=false;down=false;blocked=true;claimed=false;}
        Decision update(Object nextWorld,Object nextConnection,boolean focused,boolean player,Object screen,boolean physicalDown,boolean pressEvent){
            boolean consume=claimed;Object close=null;
            if(!initialized||world!=nextWorld||connection!=nextConnection){
                if(owned!=null&&owned==screen)close=owned;
                owned=null;world=nextWorld;connection=nextConnection;initialized=true;blocked=physicalDown;down=physicalDown;
                if(!physicalDown)claimed=false;
                return new Decision(consume,false,close);
            }
            if(!focused||nextWorld==null||!player){
                if(owned!=null&&owned==screen)close=owned;owned=null;blocked=true;down=physicalDown;
                return new Decision(consume,false,close);
            }
            if(owned!=null&&screen!=owned){owned=null;blocked=physicalDown;}
            if(!physicalDown){
                if(owned!=null&&owned==screen)close=owned;
                owned=null;down=false;blocked=false;claimed=false;
                return new Decision(consume,false,close);
            }
            boolean rising=!down;down=true;
            if(screen!=null&&screen!=owned){blocked=true;return new Decision(consume,false,null);}
            if(pressEvent&&rising&&!blocked&&screen==null){claimed=true;return new Decision(true,true,null);}
            return new Decision(consume,false,null);
        }
    }
}
