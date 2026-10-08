package dev.betterlitematica.fabric;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;

/** List row: left-aligned name, a state dot and an accent rail when selected. */
final class RowButton extends ButtonWidget {
    private final boolean selected,live;private final UiMotion motion;private long hoverStart;
    RowButton(int x,int width,String name,boolean selected,boolean live,UiMotion motion,Runnable action){
        super(x,0,width,20,Text.literal(name),b->action.run(),DEFAULT_NARRATION_SUPPLIER);this.selected=selected;this.live=live;this.motion=motion;
    }
    @Override public void renderWidget(DrawContext ctx,int mouseX,int mouseY,float delta){
        var ui=IndependentUi.INSTANCE;boolean over=active&&(isHovered()||isFocused());double hover=motion.hover(over),press=motion.pressed();
        double x=getX(),y=getY(),r=x+width,b=y+height,radius=UiTheme.BUTTON_RADIUS;
        int fill=selected?UiMotion.mix(UiTheme.SELECTED,UiTheme.PRESSED,hover*.6):UiMotion.alpha(UiTheme.HOVER,hover);
        fill=UiMotion.mix(fill,UiTheme.PRESSED,press*.7);
        if(isFocused())ui.ring(x,y,r,b,radius,1);
        if((fill>>>24)!=0)ui.roundRect(x,y,r,b,radius,fill);
        if(selected)ui.roundRect(x+1,y+5,x+3,b-5,1,UiTheme.ACCENT);
        double dot=5,dy=y+(height-dot)/2;
        if(live)ui.roundRect(x+10,dy,x+10+dot,dy+dot,dot/2,selected?UiTheme.ACCENT:UiTheme.SUCCESS);
        else ui.roundFrame(x+10,dy,x+10+dot,dy+dot,dot/2,UiTheme.MUTED);
        String name=getMessage().getString();double tx=x+22,space=r-tx-8,ty=y+(height-ui.lineHeight())/2-.5;
        int color=selected?UiTheme.TEXT:UiMotion.mix(UiTheme.SECONDARY,UiTheme.TEXT,hover);
        if(over&&ui.measure(name)>space){
            if(hoverStart==0)hoverStart=System.nanoTime();
            ui.clip(tx,y,r-6,b);try{ui.rawText(name,tx-BrowserGrid.marqueeOffset(ui.measure(name)-space,(System.nanoTime()-hoverStart)/1e9),ty,color);}finally{ui.unclip();}
        }else{hoverStart=0;ui.text(name,tx,ty,space,color);}
    }
    @Override public void onPress(){motion.press();super.onPress();}
}
