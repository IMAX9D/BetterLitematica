package dev.betterlitematica.fabric;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.widget.SliderWidget;
import net.minecraft.text.Text;
import java.util.function.*;
import org.lwjgl.glfw.GLFW;

/** Vanilla interaction/focus, independent overlay pixels and system-font labels. */
final class ColorPickerSlider extends SliderWidget {
    private final String label;private final int steps;private final DoubleConsumer changed;private final DoubleToIntFunction color;
    private final UiMotion motion=new UiMotion();
    ColorPickerSlider(int x,int width,String label,int steps,double value,DoubleConsumer changed,DoubleToIntFunction color){
        super(x,0,width,38,Text.empty(),ColorPickerColor.clamp(value));this.label=label;this.steps=steps;this.changed=changed;this.color=color;updateMessage();
    }
    @Override protected void updateMessage(){if(label!=null)setMessage(Text.literal(label+"："+Math.round(value*steps)+(steps==100?"%":" / 255")));}
    @Override protected void applyValue(){if(changed!=null)changed.accept(value);}
    @Override public boolean keyPressed(int key,int scan,int modifiers){
        if(key==GLFW.GLFW_KEY_LEFT||key==GLFW.GLFW_KEY_RIGHT||key==GLFW.GLFW_KEY_HOME||key==GLFW.GLFW_KEY_END){value=key==GLFW.GLFW_KEY_HOME?0:key==GLFW.GLFW_KEY_END?1:ColorPickerColor.clamp(value+(key==GLFW.GLFW_KEY_RIGHT?1d:-1d)/steps);applyValue();updateMessage();return true;}
        return super.keyPressed(key,scan,modifiers);
    }
    @Override public void renderButton(DrawContext context,int mouseX,int mouseY,float delta){
        var ui=IndependentUi.INSTANCE;ui.text(getMessage().getString(),getX(),getY(),width,UiTheme.TEXT);
        double left=getX()+4,right=getX()+width-4,y=getY()+23;
        for(int i=0;i<32;i++){double a=left+(right-left)*i/32,b=left+(right-left)*(i+1)/32;ui.rect(a,y-4,b,y+4,(i&1)==0?UiTheme.INPUT:UiTheme.TRACK);ui.rect(a,y-4,b,y+4,color.applyAsInt(i/31d));}
        ui.roundFrame(left,y-4,right,y+4,3,UiTheme.BORDER_STRONG);double x=left+(right-left)*value,hover=motion.hover(isFocused()||isHovered());
        ui.ring(x-4,y-7,x+4,y+7,4,isFocused()?1:hover*.6);ui.shadow(x-4,y-7,x+4,y+7,4);ui.roundRect(x-4,y-7,x+4,y+7,4,UiTheme.DARK?UiTheme.TEXT:UiTheme.INPUT);ui.roundFrame(x-4,y-7,x+4,y+7,4,UiMotion.mix(UiTheme.BORDER_STRONG,UiTheme.ACCENT,hover));
    }
}
