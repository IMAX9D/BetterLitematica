package dev.betterlitematica.fabric;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.*;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.*;
import net.minecraft.network.chat.Component;

/** Keep widget coordinates and editor policy independent of native input event records. */
final class UiEvents {
    static final KeyEvent NONE=new KeyEvent(0,0,0);
    private UiEvents() {}
    static MouseButtonEvent mouse(double x,double y,int button){return new MouseButtonEvent(x,y,new MouseButtonInfo(button,0));}
    static KeyEvent key(int key,int scan,int modifiers){return new KeyEvent(key,scan,modifiers);}
    static boolean shiftDown(){return Minecraft.getInstance().hasShiftDown();}
    static boolean controlDown(){return Minecraft.getInstance().hasControlDown();}
    static boolean altDown(){return Minecraft.getInstance().hasAltDown();}
}

abstract class UiScreen extends Screen {
    UiScreen(Component title){super(title);}
    @Override public void extractRenderState(GuiGraphicsExtractor graphics,int x,int y,float delta){render(graphics,x,y,delta);}
    public void render(GuiGraphicsExtractor graphics,int x,int y,float delta){super.extractRenderState(graphics,x,y,delta);}
    @Override public boolean keyPressed(KeyEvent event){return keyPressed(event.key(),event.scancode(),event.modifiers());}
    public boolean keyPressed(int key,int scan,int modifiers){return super.keyPressed(UiEvents.key(key,scan,modifiers));}
    @Override public boolean charTyped(CharacterEvent event){return charTyped(event.codepoint(),0);} public boolean charTyped(int point,int modifiers){return super.charTyped(new CharacterEvent(point));}
    @Override public boolean keyReleased(KeyEvent event){return keyReleased(event.key(),event.scancode(),event.modifiers());}
    public boolean keyReleased(int key,int scan,int modifiers){return super.keyReleased(UiEvents.key(key,scan,modifiers));}
    @Override public boolean mouseClicked(MouseButtonEvent event,boolean doubleClick){return mouseClicked(event.x(),event.y(),event.button());}
    public boolean mouseClicked(double x,double y,int button){return super.mouseClicked(UiEvents.mouse(x,y,button),false);}
    @Override public boolean mouseReleased(MouseButtonEvent event){return mouseReleased(event.x(),event.y(),event.button());}
    public boolean mouseReleased(double x,double y,int button){return super.mouseReleased(UiEvents.mouse(x,y,button));}
    @Override public boolean mouseDragged(MouseButtonEvent event,double dx,double dy){return mouseDragged(event.x(),event.y(),event.button(),dx,dy);}
    public boolean mouseDragged(double x,double y,int button,double dx,double dy){return super.mouseDragged(UiEvents.mouse(x,y,button),dx,dy);}
    @Override public boolean mouseScrolled(double x,double y,double horizontal,double vertical){return mouseScrolled(x,y,vertical);}
    public boolean mouseScrolled(double x,double y,double amount){return super.mouseScrolled(x,y,0,amount);}
}

abstract class UiWidget extends AbstractWidget {
    UiWidget(int x,int y,int width,int height,Component message){super(x,y,width,height,message);}
    @Override protected void extractWidgetRenderState(GuiGraphicsExtractor graphics,int x,int y,float delta){renderWidget(graphics,x,y,delta);}
    protected abstract void renderWidget(GuiGraphicsExtractor graphics,int x,int y,float delta);
    @Override public void onClick(MouseButtonEvent event,boolean doubleClick){onClick(event.x(),event.y());}
    public void onClick(double x,double y){super.onClick(UiEvents.mouse(x,y,0),false);}
    @Override protected void onDrag(MouseButtonEvent event,double dx,double dy){onDrag(event.x(),event.y(),dx,dy);}
    protected void onDrag(double x,double y,double dx,double dy){super.onDrag(UiEvents.mouse(x,y,0),dx,dy);}
    @Override public boolean keyPressed(KeyEvent event){return keyPressed(event.key(),event.scancode(),event.modifiers());}
    public boolean keyPressed(int key,int scan,int modifiers){return super.keyPressed(UiEvents.key(key,scan,modifiers));}
    @Override public boolean mouseClicked(MouseButtonEvent event,boolean doubleClick){return mouseClicked(event.x(),event.y(),event.button());}
    public boolean mouseClicked(double x,double y,int button){return super.mouseClicked(UiEvents.mouse(x,y,button),false);}
    @Override public boolean mouseReleased(MouseButtonEvent event){return mouseReleased(event.x(),event.y(),event.button());}
    public boolean mouseReleased(double x,double y,int button){return super.mouseReleased(UiEvents.mouse(x,y,button));}
    @Override public boolean mouseDragged(MouseButtonEvent event,double dx,double dy){return mouseDragged(event.x(),event.y(),event.button(),dx,dy);}
    public boolean mouseDragged(double x,double y,int button,double dx,double dy){return super.mouseDragged(UiEvents.mouse(x,y,button),dx,dy);}
    @Override public boolean mouseScrolled(double x,double y,double horizontal,double vertical){return mouseScrolled(x,y,vertical);}
    public boolean mouseScrolled(double x,double y,double amount){return super.mouseScrolled(x,y,0,amount);}
}

abstract class UiButton extends Button {
    UiButton(int x,int y,int width,int height,Component message,OnPress press,CreateNarration narration){super(x,y,width,height,message,press,narration);}
    @Override protected void extractContents(GuiGraphicsExtractor graphics,int x,int y,float delta){renderWidget(graphics,x,y,delta);}
    protected abstract void renderWidget(GuiGraphicsExtractor graphics,int x,int y,float delta);
    @Override public void onPress(InputWithModifiers input){onPress();}
    public void onPress(){super.onPress(UiEvents.NONE);}
    @Override public boolean keyPressed(KeyEvent event){return keyPressed(event.key(),event.scancode(),event.modifiers());}
    public boolean keyPressed(int key,int scan,int modifiers){return super.keyPressed(UiEvents.key(key,scan,modifiers));}
    @Override public boolean mouseClicked(MouseButtonEvent event,boolean doubleClick){return mouseClicked(event.x(),event.y(),event.button());}
    public boolean mouseClicked(double x,double y,int button){return super.mouseClicked(UiEvents.mouse(x,y,button),false);}
    @Override public boolean mouseReleased(MouseButtonEvent event){return mouseReleased(event.x(),event.y(),event.button());}
    public boolean mouseReleased(double x,double y,int button){return super.mouseReleased(UiEvents.mouse(x,y,button));}
    @Override public boolean mouseDragged(MouseButtonEvent event,double dx,double dy){return mouseDragged(event.x(),event.y(),event.button(),dx,dy);}
    public boolean mouseDragged(double x,double y,int button,double dx,double dy){return super.mouseDragged(UiEvents.mouse(x,y,button),dx,dy);}
    @Override public boolean mouseScrolled(double x,double y,double horizontal,double vertical){return mouseScrolled(x,y,vertical);}
    public boolean mouseScrolled(double x,double y,double amount){return super.mouseScrolled(x,y,0,amount);}
}

abstract class UiSlider extends AbstractSliderButton {
    UiSlider(int x,int y,int width,int height,Component message,double value){super(x,y,width,height,message,value);}
    @Override public void extractWidgetRenderState(GuiGraphicsExtractor graphics,int x,int y,float delta){renderWidget(graphics,x,y,delta);}
    protected abstract void renderWidget(GuiGraphicsExtractor graphics,int x,int y,float delta);
    @Override public boolean keyPressed(KeyEvent event){return keyPressed(event.key(),event.scancode(),event.modifiers());}
    public boolean keyPressed(int key,int scan,int modifiers){return super.keyPressed(UiEvents.key(key,scan,modifiers));}
    @Override public boolean mouseClicked(MouseButtonEvent event,boolean doubleClick){return mouseClicked(event.x(),event.y(),event.button());}
    public boolean mouseClicked(double x,double y,int button){return super.mouseClicked(UiEvents.mouse(x,y,button),false);}
    @Override public boolean mouseReleased(MouseButtonEvent event){return mouseReleased(event.x(),event.y(),event.button());}
    public boolean mouseReleased(double x,double y,int button){return super.mouseReleased(UiEvents.mouse(x,y,button));}
    @Override public void onClick(MouseButtonEvent event,boolean doubleClick){onClick(event.x(),event.y());}
    public void onClick(double x,double y){super.onClick(UiEvents.mouse(x,y,0),false);}
    @Override protected void onDrag(MouseButtonEvent event,double dx,double dy){onDrag(event.x(),event.y(),dx,dy);}
    protected void onDrag(double x,double y,double dx,double dy){super.onDrag(UiEvents.mouse(x,y,0),dx,dy);}
}

abstract class UiTextField extends EditBox {
    UiTextField(net.minecraft.client.gui.Font font,int x,int y,int width,int height,Component message){super(font,x,y,width,height,message);}
    @Override public void extractWidgetRenderState(GuiGraphicsExtractor graphics,int x,int y,float delta){renderWidget(graphics,x,y,delta);}
    public void renderWidget(GuiGraphicsExtractor graphics,int x,int y,float delta){super.extractWidgetRenderState(graphics,x,y,delta);}
    public boolean keyPressed(int key,int scan,int modifiers){return super.keyPressed(UiEvents.key(key,scan,modifiers));}
    public boolean charTyped(int point,int modifiers){return super.charTyped(new CharacterEvent(point));}
    @Override public boolean mouseClicked(MouseButtonEvent event,boolean doubleClick){return mouseClicked(event.x(),event.y(),event.button());}
    public boolean mouseClicked(double x,double y,int button){return super.mouseClicked(UiEvents.mouse(x,y,button),false);}
    @Override public boolean mouseReleased(MouseButtonEvent event){return mouseReleased(event.x(),event.y(),event.button());}
    public boolean mouseReleased(double x,double y,int button){return super.mouseReleased(UiEvents.mouse(x,y,button));}
    @Override public boolean mouseDragged(MouseButtonEvent event,double dx,double dy){return mouseDragged(event.x(),event.y(),event.button(),dx,dy);}
    public boolean mouseDragged(double x,double y,int button,double dx,double dy){return super.mouseDragged(UiEvents.mouse(x,y,button),dx,dy);}
}
