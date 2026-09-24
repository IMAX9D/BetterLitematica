package dev.betterlitematica.fabric;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.widget.ClickableWidget;
import net.minecraft.client.gui.screen.narration.NarrationMessageBuilder;
import net.minecraft.text.Text;

/** Read-only name with full-width hover scrolling; never acts as a button or keyboard focus target. */
final class OverlayLabel extends ClickableWidget {
    private long hoverStart;
    OverlayLabel(int x,int width,String text){super(x,0,width,20,Text.literal(text));active=false;}
    @Override public boolean mouseClicked(double x,double y,int button){return false;}
    @Override public void renderButton(DrawContext context,int mouseX,int mouseY,float delta){
        var ui=IndependentUi.INSTANCE;String text=getMessage().getString();long now=System.nanoTime();
        if(!hoveredAt(mouseX,mouseY))hoverStart=0;else if(hoverStart==0)hoverStart=now;
        double offset=hoverStart==0?0:BrowserGrid.marqueeOffset(Math.max(0,ui.measure(text)-width),(now-hoverStart)/1e9);
        ui.clip(getX(),getY(),getX()+width,getY()+height);try{ui.rawText(text,getX()-offset,getY()+(height-ui.lineHeight())/2,UiTheme.TEXT);}finally{ui.unclip();}
    }
    boolean hoveredAt(double x,double y){return visible&&x>=getX()&&x<getX()+width&&y>=getY()&&y<getY()+height;}
    @Override protected void appendClickableNarrations(NarrationMessageBuilder builder){appendDefaultNarrations(builder);}
}
