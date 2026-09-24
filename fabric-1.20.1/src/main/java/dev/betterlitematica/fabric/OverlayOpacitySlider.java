package dev.betterlitematica.fabric;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.widget.SliderWidget;
import net.minecraft.text.Text;
import java.util.function.DoubleConsumer;

/** Native pointer/keyboard model, independent physical-pixel painting. */
final class OverlayOpacitySlider extends SliderWidget {
    private final DoubleConsumer changed;
    private Runnable begin=()->{},end=()->{};
    void previewCallbacks(Runnable begin,Runnable end){this.begin=begin;this.end=end;}
    @Override public boolean mouseClicked(double x,double y,int button){
        boolean handled=super.mouseClicked(x,y,button);if(handled)begin.run();return handled;
    }
    @Override public boolean mouseReleased(double x,double y,int button){
        try{return super.mouseReleased(x,y,button);}finally{if(button==0)end.run();}
    }
    OverlayOpacitySlider(int x,int width,float opacity,DoubleConsumer changed){
        super(x,0,width,30,Text.empty(),Math.max(0,Math.min(1,(opacity-0.05)/0.95)));
        this.changed=changed;updateMessage();
    }
    float opacity(){return Math.round((0.05+value*0.95)*100)/100f;}
    @Override protected void updateMessage(){setMessage(Text.literal("不透明度："+Math.round(opacity()*100)+"%"));}
    @Override protected void applyValue(){if(changed!=null)changed.accept(opacity());}
    @Override public void renderButton(DrawContext context,int mouseX,int mouseY,float delta){
        var ui=IndependentUi.INSTANCE;
        ui.text(getMessage().getString(),getX(),getY(),width,UiTheme.TEXT);
        double x=getX()+4+(width-8)*value,y=getY()+23;
        ui.rect(getX()+4,y-2,getX()+width-4,y+2,UiTheme.DIVIDER);
        ui.rect(getX()+4,y-2,x,y+2,UiTheme.ACCENT);
        ui.rect(x-4,y-6,x+4,y+6,isHovered()||isFocused()?UiTheme.FOCUS:UiTheme.ACCENT);
    }
}
