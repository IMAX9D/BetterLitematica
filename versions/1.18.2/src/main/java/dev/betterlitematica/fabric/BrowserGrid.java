package dev.betterlitematica.fabric;
import dev.betterlitematica.runtime.SessionIo;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;
import java.util.*;
import java.util.function.Consumer;

/** A clipped, virtual single-column directory viewport. */
final class BrowserGrid extends ButtonWidget {
    public int getX(){return x;} public int getY(){return y;} public void setX(int value){x=value;} public void setY(int value){y=value;}

    private static final int COLUMNS=1,ROW=30,GAP=5;
    private final Consumer<SessionIo.FileEntry> choose,open;
    private List<SessionIo.FileEntry> entries=List.of();
    private String empty="",selected="";
    private double scroll;
    private boolean dragging;
    private int hovered=-1;
    private long hoverStart;
    BrowserGrid(int x,int width,int height,Consumer<SessionIo.FileEntry> open){this(x,width,height,open,open);}
    BrowserGrid(int x,int width,int height,Consumer<SessionIo.FileEntry> choose,Consumer<SessionIo.FileEntry> open){super(x,0,width,height,new net.minecraft.text.LiteralText("投影文件"),b->{},EMPTY);this.choose=choose;this.open=open;}
    void entries(List<SessionIo.FileEntry> next,String empty){boolean unchanged=entries.equals(next);entries=List.copyOf(next);this.empty=empty;if(!unchanged){scroll=0;hovered=-1;dragging=false;}}
    void selected(String path){selected=path==null?"":path;}
    String selectedPath(){return selected;}
    double scrollOffset(){return scroll;}
    private int cardWidth(){return (width-10-(COLUMNS-1)*GAP)/COLUMNS;}
    private int content(){return ((entries.size()+COLUMNS-1)/COLUMNS)*ROW;}
    private int maxScroll(){return Math.max(0,content()-height);}
    private void clamp(){scroll=Math.max(0,Math.min(scroll,maxScroll()));hovered=-1;}
    private int hit(double x,double y){
        if(!visible||!active||!isMouseOver(x,y))return -1;
        double dx=x-getX(),dy=y-getY()+scroll;int w=cardWidth(),col=(int)(dx/(w+GAP)),row=(int)(dy/ROW);
        if(col>=COLUMNS||dx-col*(w+GAP)>=w||dy-row*ROW>=ROW-GAP)return -1;
        int index=row*COLUMNS+col;return index<entries.size()?index:-1;
    }
    @Override public boolean mouseScrolled(double x,double y,double amount){if(!visible||!active||!isMouseOver(x,y))return false;scroll-=amount*ROW;clamp();return true;}
    @Override public boolean mouseClicked(double x,double y,int button){
        if(!visible||!active||button!=0||!isMouseOver(x,y))return false;setFocused(true);
        dragging=maxScroll()>0&&x>=getX()+width-8;
        if(dragging)dragTo(y);else{int index=hit(x,y);if(index>=0){var entry=entries.get(index);motion(index).press();selected=entry.path();if(entry.directory())open.accept(entry);else choose.accept(entry);}}return true;
    }
    private void dragTo(double y){double thumb=Math.max(18,height*(double)height/Math.max(1,content()));scroll=(y-getY()-thumb/2)/Math.max(1,height-thumb)*maxScroll();clamp();}
    @Override public boolean mouseDragged(double x,double y,int button,double dx,double dy){if(!active||!dragging||button!=0)return false;dragTo(y);return true;}
    @Override public boolean mouseReleased(double x,double y,int button){boolean was=dragging;dragging=false;return was;}
    @Override public boolean keyPressed(int key,int scan,int modifiers){
        if(!visible||!active)return false;
        if(entries.isEmpty())return false;
        int current=-1;for(int i=0;i<entries.size();i++)if(entries.get(i).path().equals(selected)){current=i;break;}
        int next=current<0?Math.min(entries.size()-1,(int)(scroll/ROW)):current;
        if(key==257||key==335){open.accept(entries.get(next));return true;}
        if(key==264){if(current>=0)next++;}else if(key==265){if(current>=0)next--;}
        else if(key==267)next+=Math.max(1,height/ROW);else if(key==266)next-=Math.max(1,height/ROW);
        else if(key==268)next=0;else if(key==269)next=entries.size()-1;else return false;
        next=Math.max(0,Math.min(entries.size()-1,next));int top=next*ROW;
        if(top<scroll)scroll=top;else if(top+ROW>scroll+height)scroll=top+ROW-height;
        clamp();selected=entries.get(next).path();choose.accept(entries.get(next));return true;
    }
    // Pause at both ends, move at a constant speed, then repeat without jumping.
    static double marqueeOffset(double overflow,double seconds){
        if(overflow<=0)return 0;double travel=overflow/28.0,phase=Math.max(0,seconds)%(2*travel+1.6);
        if(phase<0.8)return 0;if(phase<0.8+travel)return (phase-0.8)*28;
        if(phase<1.6+travel)return overflow;return Math.max(0,overflow-(phase-1.6-travel)*28);
    }
    private final Map<Integer,UiMotion> motion=new LinkedHashMap<>();
    private UiMotion motion(int index){if(motion.size()>128)motion.clear();return motion.computeIfAbsent(index,key->new UiMotion());}
    @Override public void renderButton(MatrixStack legacyMatrices,int mouseX,int mouseY,float delta){LegacyGuiContext ctx=new LegacyGuiContext(legacyMatrices);
        var ui=IndependentUi.INSTANCE;int hover=hit(mouseX,mouseY),w=cardWidth();long now=System.nanoTime();
        if(hover!=hovered){hovered=hover;hoverStart=now;}
        ui.clip(getX(),getY(),getX()+width,getY()+height);
        try{
            if(entries.isEmpty())ui.text(empty,getX()+4,getY()+6,width-8,UiTheme.SECONDARY);
            int first=(int)(scroll/ROW),last=Math.min((entries.size()+COLUMNS-1)/COLUMNS,(int)Math.ceil((scroll+height)/ROW));
            for(int row=first;row<last;row++)for(int col=0;col<COLUMNS;col++){
                int index=row*COLUMNS+col;if(index>=entries.size())break;var file=entries.get(index);
                int x=getX()+col*(w+GAP);double y=getY()+row*ROW-scroll;
                boolean chosen=file.path().equals(selected);double over=motion(index).hover(index==hover);
                int fill=chosen?UiMotion.mix(UiTheme.SELECTED,UiTheme.PRESSED,over*.6):UiMotion.alpha(UiTheme.HOVER,over);
                if((fill>>>24)!=0)ui.roundRect(x,y,x+w,y+ROW-GAP,UiTheme.BUTTON_RADIUS,fill);
                if(chosen)ui.roundRect(x+1,y+6,x+3,y+ROW-GAP-6,1,UiTheme.ACCENT);
                // Folder and document glyphs replace repeated type labels.
                ui.glyph(file.directory()?dev.betterlitematica.runtime.UiGlyphArt.Kind.FOLDER:dev.betterlitematica.runtime.UiGlyphArt.Kind.FILE,x+8,y+(ROW-GAP-13)/2.0,13,file.directory()||chosen?UiTheme.ACCENT:UiTheme.MUTED);
                double tx=x+28,ty=y+(ROW-GAP-ui.lineHeight())/2.0-.5,space=w-34;
                if(index==hover&&ui.measure(file.name())>space){
                    ui.clip(tx,y+2,x+w-6,y+ROW-GAP-2);
                    try{ui.rawText(file.name(),tx-marqueeOffset(ui.measure(file.name())-space,(now-hoverStart)/1e9),ty,UiTheme.TEXT);}finally{ui.unclip();}
                }else ui.text(file.name(),tx,ty,space,chosen?UiTheme.TEXT:UiTheme.SECONDARY);
            }
        }finally{ui.unclip();}
        if(maxScroll()>0){double thumb=Math.max(18,height*(double)height/content()),y=getY()+(height-thumb)*scroll/maxScroll();ui.roundRect(getX()+width-4,getY(),getX()+width-2,getY()+height,1,UiMotion.alpha(UiTheme.TRACK,.8));ui.roundRect(getX()+width-4,y,getX()+width-2,y+thumb,1,UiTheme.THUMB);}
        if(isFocused())ui.roundFrame(getX()-2,getY()-2,getX()+width-7,getY()+height+2,UiTheme.CARD_RADIUS,UiMotion.alpha(UiTheme.ACCENT,.5));
    }
}
