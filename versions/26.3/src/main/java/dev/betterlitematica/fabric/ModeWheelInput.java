package dev.betterlitematica.fabric;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;


/** Physical held-key screen ownership; no queued open may survive a screen, focus or world transition. */
final class ModeWheelInput {
    private final Minecraft client;
    private final ProjectionController controller;
    private final Lifecycle lifecycle=new Lifecycle();
    private boolean mouseRelease;
    private int boundKey=NativeInput.GLFW_KEY_TAB;
    ModeWheelInput(Minecraft client,ProjectionController controller){this.client=client;this.controller=controller;}
    static int bindingCode(String chord){
        String key=InputBindings.normalize(chord);if(key.isEmpty())return NativeInput.GLFW_KEY_UNKNOWN;
        if(key.contains("+")||InputBindings.code(key)<0||java.util.Set.of("CTRL","ALT","SHIFT","SUPER","ESCAPE").contains(key))throw new IllegalArgumentException("轮盘请选择单个非修饰键");
        return InputBindings.code(key);
    }
    static boolean reservesKey(Minecraft client,int key){return key>=0&&key==BetterLitematicaClient.wheelKeyCode()&&client.level!=null&&client.player!=null;}
    static boolean reservesTab(Minecraft client){return reservesKey(client,NativeInput.GLFW_KEY_TAB);}
    private boolean available(){return boundKey>=0&&client.level!=null&&client.player!=null;}
    private boolean retained(){return ClientUi.screen(client) instanceof ModeWheelScreen wheel&&wheel.retained();}
    private void synchronizeBinding(){int next=bindingCode(controller.options().keys.getOrDefault("wheel","TAB"));if(next!=boundKey){clear();boundKey=next;}}

    boolean key(long window,int key,int scan,int action){
        synchronizeBinding();
        if(window!=client.getWindow().handle()||boundKey<0||key!=boundKey)return false;
        boolean down=action!=NativeInput.GLFW_RELEASE;
        var decision=lifecycle.update(client.level,client.getConnection(),client.isWindowActive(),client.player!=null,ClientUi.screen(client),down,action==NativeInput.GLFW_PRESS,retained());
        apply(decision);
        if(decision.consume()||available())clearTab();
        return decision.consume()||available()&&ClientUi.screen(client)==null;
    }
    void tick(){
        synchronizeBinding();
        boolean held=boundKey>=0&&NativeInput.isKeyDown(client.getWindow(),boundKey);
        apply(lifecycle.update(client.level,client.getConnection(),client.isWindowActive(),client.player!=null,ClientUi.screen(client),held,false,retained()));
        if(available())clearTab();
        if(mouseRelease&&!mouseDown())mouseRelease=false;
        if(blocksWorldInput())clearMouseBindings();
    }
    boolean mouse(long window,int button,int action){
        if(window!=client.getWindow().handle())return false;
        if(ClientUi.screen(client) instanceof ModeWheelScreen){if(action==NativeInput.GLFW_PRESS)mouseRelease=true;return false;}
        if(lifecycle.owned()!=null){mouseRelease|=mouseDown();clearMouseBindings();}
        boolean consume=mouseRelease&&ClientUi.screen(client)==null;
        if(consume)clearMouseBindings();
        if(action==NativeInput.GLFW_RELEASE&&!mouseDown())mouseRelease=false;
        return consume;
    }
    boolean blocksWorldInput(){return ClientUi.screen(client) instanceof ModeWheelScreen||lifecycle.claimed()||mouseRelease;}
    boolean sharesPlayerListHold(){
        boolean sameKey=boundKey>=0&&boundKey==BetterLitematicaClient.wheelKeyCode()&&client.options.keyPlayerList.matches(UiEvents.key(boundKey,0,0));
        return lifecycle.sharesHold(client.level,client.getConnection(),client.isWindowActive(),client.player!=null,ClientUi.screen(client),sameKey);
    }
    void clear(){
        var owned=lifecycle.owned();lifecycle.reset();
        if(owned!=null&&ClientUi.screen(client)==owned)((Screen)owned).onClose();
        mouseRelease|=mouseDown();clearMouseBindings();clearTab();
    }
    private void apply(Lifecycle.Decision decision){
        if(decision.close()!=null){mouseRelease|=mouseDown();clearMouseBindings();if(ClientUi.screen(client)==decision.close())((Screen)decision.close()).onClose();}
        if(decision.open()){
            clearMouseBindings();mouseRelease|=mouseDown();var screen=new ModeWheelScreen(controller);lifecycle.bind(screen);ClientUi.setScreen(client,screen);clearTab();
        }
    }
    private boolean mouseDown(){long window=client.getWindow().handle();return NativeInput.glfwGetMouseButton(window,NativeInput.GLFW_MOUSE_BUTTON_LEFT)==NativeInput.GLFW_PRESS||NativeInput.glfwGetMouseButton(window,NativeInput.GLFW_MOUSE_BUTTON_RIGHT)==NativeInput.GLFW_PRESS||NativeInput.glfwGetMouseButton(window,NativeInput.GLFW_MOUSE_BUTTON_MIDDLE)==NativeInput.GLFW_PRESS;}
    private void clearMouseBindings(){clear(client.options.keyAttack);clear(client.options.keyUse);clear(client.options.keyPickItem);}
    private static void clear(KeyMapping binding){binding.setDown(false);while(binding.consumeClick()){};}
    private void clearTab(){
        if(boundKey<0)return;
        int scan=NativeInput.glfwGetKeyScancode(boundKey);
        KeyMapping.set(InputConstants.Type.KEYBOARD.getOrCreate(NativeInput.nativeKey(boundKey)),false);

        if(client.options.keyPlayerList.matches(UiEvents.key(boundKey,0,0)))clear(client.options.keyPlayerList);
    }
    /** Package-visible production state machine for deterministic lifecycle regressions. */
    static final class Lifecycle {
        record Decision(boolean consume,boolean open,Object close){}
        private Object world,connection,owned;
        private boolean initialized,down,blocked=true,claimed;
        Object owned(){return owned;}
        boolean claimed(){return claimed;}
        boolean sharesHold(Object nextWorld,Object nextConnection,boolean focused,boolean player,Object screen,boolean sameKey){
            return sameKey&&focused&&player&&nextWorld!=null&&world==nextWorld&&connection==nextConnection&&claimed&&down&&owned!=null&&screen==owned;
        }
        void bind(Object screen){owned=screen;}
        void reset(){world=null;connection=null;owned=null;initialized=false;down=false;blocked=true;claimed=false;}
        Decision update(Object nextWorld,Object nextConnection,boolean focused,boolean player,Object screen,boolean physicalDown,boolean pressEvent){
            return update(nextWorld,nextConnection,focused,player,screen,physicalDown,pressEvent,false);
        }
        Decision update(Object nextWorld,Object nextConnection,boolean focused,boolean player,Object screen,boolean physicalDown,boolean pressEvent,boolean retained){
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
                if(retained&&owned!=null&&owned==screen){down=false;blocked=false;claimed=true;return new Decision(true,false,null);}
                if(owned!=null&&owned==screen)close=owned;
                owned=null;down=false;blocked=false;claimed=false;
                return new Decision(consume,false,close);
            }
            boolean rising=!down;down=true;
            if(retained&&owned!=null&&owned==screen&&rising&&pressEvent){close=owned;owned=null;blocked=true;claimed=true;return new Decision(true,false,close);}
            if(screen!=null&&screen!=owned){blocked=true;return new Decision(consume,false,null);}
            if(pressEvent&&rising&&!blocked&&screen==null){claimed=true;return new Decision(true,true,null);}
            return new Decision(consume,false,null);
        }
    }
}
