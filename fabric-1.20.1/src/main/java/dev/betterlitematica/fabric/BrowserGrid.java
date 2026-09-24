package dev.betterlitematica.fabric;
import dev.betterlitematica.runtime.SessionIo;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;
import java.util.*;
import java.util.function.Consumer;

/** A clipped, virtual three-column directory viewport. */
final class BrowserGrid extends ButtonWidget {
    private static final int COLUMNS=3,ROW=30,GAP=5;
    private final Consumer<SessionIo.FileEntry> open;
    private List<SessionIo.FileEntry> entries=List.of();
    private String empty="";
    private double scroll;
    private boolean dragging;
    private int hovered=-1;
    private long hoverStart;
    BrowserGrid(int x,int width,int height,Consumer<SessionIo.FileEntry> open){super(x,0,width,height,Text.literal("投影文件"),b->{},DEFAULT_NARRATION_SUPPLIER);this.open=open;}
    void entries(List<SessionIo.FileEntry> next,String empty){boolean unchanged=entries.equals(next);entries=List.copyOf(next);this.empty=empty;if(!unchanged){scroll=0;hovered=-1;dragging=false;}}
    double scrollOffset(){return scroll;}
    private int cardWidth(){return (width-10-(COLUMNS-1)*GAP)/COLUMNS;}
    private int content(){return ((entries.size()+COLUMNS-1)/COLUMNS)*ROW;}
    private int maxScroll(){return Math.max(0,content()-height);}
    private void clamp(){scroll=Math.max(0,Math.min(scroll,maxScroll()));hovered=-1;}
    private int hit(double x,double y){
        if(!isMouseOver(x,y))return -1;
        double dx=x-getX(),dy=y-getY()+scroll;int w=cardWidth(),col=(int)(dx/(w+GAP)),row=(int)(dy/ROW);
        if(col>=COLUMNS||dx-col*(w+GAP)>=w||dy-row*ROW>=ROW-GAP)return -1;
        int index=row*COLUMNS+col;return index<entries.size()?index:-1;
    }
    @Override public boolean mouseScrolled(double x,double y,double amount){if(!isMouseOver(x,y))return false;scroll-=amount*ROW;clamp();return true;}
    @Override public boolean mouseClicked(double x,double y,int button){
        if(button!=0||!isMouseOver(x,y))return false;setFocused(true);
        dragging=maxScroll()>0&&x>=getX()+width-8;
        if(dragging)dragTo(y);else{int index=hit(x,y);if(index>=0)open.accept(entries.get(index));}return true;
    }
    private void dragTo(double y){double thumb=Math.max(18,height*(double)height/Math.max(1,content()));scroll=(y-getY()-thumb/2)/Math.max(1,height-thumb)*maxScroll();clamp();}
    @Override public boolean mouseDragged(double x,double y,int button,double dx,double dy){if(!dragging||button!=0)return false;dragTo(y);return true;}
    @Override public boolean mouseReleased(double x,double y,int button){boolean was=dragging;dragging=false;return was;}
    @Override public boolean keyPressed(int key,int scan,int modifiers){
        if(key==264)scroll+=ROW;else if(key==265)scroll-=ROW;else if(key==267)scroll+=height;else if(key==266)scroll-=height;else if(key==268)scroll=0;else if(key==269)scroll=maxScroll();else return false;clamp();return true;
    }
    // Pause at both ends, move at a constant speed, then repeat without jumping.
    static double marqueeOffset(double overflow,double seconds){
        if(overflow<=0)return 0;double travel=overflow/28.0,phase=Math.max(0,seconds)%(2*travel+1.6);
        if(phase<0.8)return 0;if(phase<0.8+travel)return (phase-0.8)*28;
        if(phase<1.6+travel)return overflow;return Math.max(0,overflow-(phase-1.6-travel)*28);
    }
    @Override public void renderButton(DrawContext ctx,int mouseX,int mouseY,float delta){
        var ui=IndependentUi.INSTANCE;int hover=hit(mouseX,mouseY),w=cardWidth();long now=System.nanoTime();
        if(hover!=hovered){hovered=hover;hoverStart=now;}
        ui.clip(getX(),getY(),getX()+width,getY()+height);
        try{
            if(entries.isEmpty())ui.text(empty,getX()+4,getY()+6,width-8,UiTheme.SECONDARY);
            int first=(int)(scroll/ROW),last=Math.min((entries.size()+COLUMNS-1)/COLUMNS,(int)Math.ceil((scroll+height)/ROW));
            for(int row=first;row<last;row++)for(int col=0;col<COLUMNS;col++){
                int index=row*COLUMNS+col;if(index>=entries.size())break;var file=entries.get(index);
                int x=getX()+col*(w+GAP);double y=getY()+row*ROW-scroll;
                ui.rect(x,y,x+w,y+ROW-GAP,index==hover?UiTheme.HOVER:UiTheme.SURFACE);
                // A small folder/document silhouette avoids repeated type labels.
                int color=file.directory()?UiTheme.ACCENT:UiTheme.SECONDARY;
                ui.rect(x+5,y+8,x+13,y+17,color);
                if(file.directory())ui.rect(x+5,y+6,x+9,y+9,color);
                else ui.rect(x+7,y+10,x+11,y+11,UiTheme.SURFACE);
                double tx=x+18,ty=y+(ROW-GAP-ui.lineHeight())/2.0,space=w-24;
                if(index==hover&&ui.measure(file.name())>space){
                    ui.clip(tx,y+2,x+w-6,y+ROW-GAP-2);
                    try{ui.rawText(file.name(),tx-marqueeOffset(ui.measure(file.name())-space,(now-hoverStart)/1e9),ty,UiTheme.TEXT);}finally{ui.unclip();}
                }else ui.text(file.name(),tx,ty,space,UiTheme.TEXT);
            }
        }finally{ui.unclip();}
        if(maxScroll()>0){double thumb=Math.max(18,height*(double)height/content()),y=getY()+(height-thumb)*scroll/maxScroll();ui.rect(getX()+width-5,getY(),getX()+width-2,getY()+height,UiTheme.TRACK);ui.rect(getX()+width-5,y,getX()+width-2,y+thumb,UiTheme.ACCENT);}
    }
}
