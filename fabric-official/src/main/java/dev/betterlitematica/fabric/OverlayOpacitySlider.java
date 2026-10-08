package dev.betterlitematica.fabric;

import java.util.function.DoubleConsumer;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.network.chat.Component;

/** Native pointer/keyboard model, independent physical-pixel painting. */
final class OverlayOpacitySlider extends UiSlider {
    private final DoubleConsumer changed;
    private final UiMotion motion=new UiMotion();
    private Runnable begin=()->{},end=()->{};
    void previewCallbacks(Runnable begin,Runnable end){this.begin=begin;this.end=end;}
    @Override public boolean mouseClicked(double x,double y,int button){
        boolean handled=super.mouseClicked(x,y,button);if(handled){motion.press();begin.run();}return handled;
    }
    @Override public boolean mouseReleased(double x,double y,int button){
        try{return super.mouseReleased(x,y,button);}finally{if(button==0)end.run();}
    }
    OverlayOpacitySlider(int x,int width,float opacity,DoubleConsumer changed){
        super(x,0,width,30,Component.empty(),Math.max(0,Math.min(1,(opacity-0.05)/0.95)));
        this.changed=changed;updateMessage();
    }
    float opacity(){return Math.round((0.05+value*0.95)*100)/100f;}
    @Override protected void updateMessage(){setMessage(Component.literal("不透明度："+Math.round(opacity()*100)+"%"));}
    @Override protected void applyValue(){if(changed!=null)changed.accept(opacity());}
    @Override public void renderWidget(GuiGraphicsExtractor context,int mouseX,int mouseY,float delta){
        var ui=IndependentUi.INSTANCE;
        String label=getMessage().getString();int split=label.indexOf('：');
        if(split>0){String name=label.substring(0,split),value=label.substring(split+1);ui.text(name,getX(),getY(),width*.6,UiTheme.SECONDARY);double vw=ui.measure(value)+1;ui.text(value,getX()+width-vw,getY(),vw,UiTheme.TEXT);}
        else ui.text(label,getX(),getY(),width,UiTheme.TEXT);
        double x=getX()+5+(width-10)*value,y=getY()+23;
        double hover=motion.hover(isHovered()||isFocused()),radius=5+hover*.6-motion.pressed()*.6;
        ui.roundRect(getX()+5,y-1.5,getX()+width-5,y+1.5,1.5,UiTheme.TRACK);
        ui.roundRect(getX()+5,y-1.5,x,y+1.5,1.5,UiTheme.ACCENT);
        ui.ring(x-radius,y-radius,x+radius,y+radius,radius,isFocused()?1:hover*.6);
        ui.shadow(x-radius,y-radius,x+radius,y+radius,radius);
        ui.roundRect(x-radius,y-radius,x+radius,y+radius,radius,UiTheme.DARK?UiTheme.TEXT:UiTheme.INPUT);
        ui.roundFrame(x-radius,y-radius,x+radius,y+radius,radius,UiMotion.mix(UiTheme.BORDER_STRONG,UiTheme.ACCENT,hover));
    }
}
