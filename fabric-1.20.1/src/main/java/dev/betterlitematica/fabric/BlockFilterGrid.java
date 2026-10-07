package dev.betterlitematica.fabric;

import dev.betterlitematica.runtime.UiGlyphArt;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.item.ItemStack;
import net.minecraft.text.Text;
import java.util.*;
import java.util.function.Consumer;

/** Registry-only virtual viewport; item rendering is limited to visible cells. */
final class BlockFilterGrid extends ButtonWidget {
    record Entry(String id,String name,ItemStack icon) {}
    private static final int COLUMNS=4,ROW=40,GAP=5;
    private final Set<String> selected;
    private final Consumer<String> toggle;
    private List<Entry> rows=List.of();
    private final Map<String,UiMotion> motions=new LinkedHashMap<>();
    private double scroll;
    private boolean dragging;
    private int cursor=-1;
    BlockFilterGrid(int x,int width,int height,Set<String> selected,Consumer<String> toggle){super(x,0,width,height,Text.literal("方块"),b->{},DEFAULT_NARRATION_SUPPLIER);this.selected=selected;this.toggle=toggle;}
    void rows(List<Entry> entries,boolean reset){String id=cursor>=0&&cursor<rows.size()?rows.get(cursor).id():null;rows=List.copyOf(entries);if(reset){scroll=0;cursor=-1;}else{cursor=-1;if(id!=null)for(int i=0;i<rows.size();i++)if(rows.get(i).id().equals(id)){cursor=i;break;}}clamp();}
    double scrollOffset(){return scroll;}
    private int cardWidth(){return (width-10-(COLUMNS-1)*GAP)/COLUMNS;}
    private int content(){return ((rows.size()+COLUMNS-1)/COLUMNS)*ROW;}
    private int maxScroll(){return Math.max(0,content()-height);}
    private void clamp(){scroll=Math.max(0,Math.min(scroll,maxScroll()));}
    private int hit(double x,double y){if(!active||!visible||!isMouseOver(x,y))return -1;double dx=x-getX(),dy=y-getY()+scroll;int w=cardWidth(),col=(int)(dx/(w+GAP)),row=(int)(dy/ROW);if(col>=COLUMNS||dx-col*(w+GAP)>=w||dy-row*ROW>=ROW-GAP)return -1;int index=row*COLUMNS+col;return index<rows.size()?index:-1;}
    @Override public boolean mouseScrolled(double x,double y,double amount){if(!active||!visible||!isMouseOver(x,y))return false;scroll-=amount*ROW;clamp();return true;}
    @Override public boolean mouseClicked(double x,double y,int button){if(!active||!visible||button!=0||!isMouseOver(x,y))return false;setFocused(true);dragging=maxScroll()>0&&x>=getX()+width-8;if(dragging)dragTo(y);else{int i=hit(x,y);if(i>=0){cursor=i;motion(rows.get(i).id()).press();toggle.accept(rows.get(i).id());}}return true;}
    private void dragTo(double y){double thumb=Math.max(18,height*(double)height/Math.max(1,content()));scroll=(y-getY()-thumb/2)/Math.max(1,height-thumb)*maxScroll();clamp();}
    @Override public boolean mouseDragged(double x,double y,int button,double dx,double dy){if(!active||!dragging||button!=0)return false;dragTo(y);return true;}
    @Override public boolean mouseReleased(double x,double y,int button){boolean was=dragging;dragging=false;return was;}
    @Override public boolean keyPressed(int key,int scan,int modifiers){if(!active||!visible)return false;
        if(key==267||key==266){scroll+=(key==267?1:-1)*height;clamp();cursor=Math.min(rows.size()-1,(int)(scroll/ROW)*COLUMNS);return true;}
        if(rows.isEmpty())return false;
        int next=cursor<0?Math.min(rows.size()-1,(int)(scroll/ROW)*COLUMNS):cursor;
        if(key==262)next++;else if(key==263)next--;else if(key==264)next+=COLUMNS;else if(key==265)next-=COLUMNS;else if(key==268)next=0;else if(key==269)next=rows.size()-1;else if(key==32||key==257||key==335){cursor=next;toggle.accept(rows.get(next).id());return true;}else return false;
        cursor=Math.max(0,Math.min(rows.size()-1,next));int top=cursor/COLUMNS*ROW;if(top<scroll)scroll=top;else if(top+ROW>scroll+height)scroll=top+ROW-height;clamp();return true;
    }
    private UiMotion motion(String id){if(motions.size()>128)motions.clear();return motions.computeIfAbsent(id,k->new UiMotion());}
    @Override public void renderButton(DrawContext ctx,int mouseX,int mouseY,float delta){var ui=IndependentUi.INSTANCE;int hover=hit(mouseX,mouseY),w=cardWidth();
        ui.clip(getX(),getY(),getX()+width,getY()+height);
        try{if(rows.isEmpty())ui.text("无匹配方块",getX()+4,getY()+6,width-8,UiTheme.MUTED);
            int first=(int)(scroll/ROW),last=Math.min((rows.size()+COLUMNS-1)/COLUMNS,(int)Math.ceil((scroll+height)/ROW));
            for(int row=first;row<last;row++)for(int col=0;col<COLUMNS;col++){int index=row*COLUMNS+col;if(index>=rows.size())break;var entry=rows.get(index);int x=getX()+col*(w+GAP);double y=getY()+row*ROW-scroll;boolean chosen=selected.contains(entry.id());double hot=motion(entry.id()).hover(index==hover);
                // Inactive (filter off): every card reads as disabled so nothing invites a click.
                ui.roundRect(x,y,x+w,y+ROW-GAP,UiTheme.BUTTON_RADIUS,!active?UiTheme.DISABLED:UiMotion.mix(chosen?UiTheme.SELECTED:UiTheme.SURFACE,UiTheme.HOVER,hot*.45));
                ui.roundFrame(x,y,x+w,y+ROW-GAP,UiTheme.BUTTON_RADIUS,!active?UiTheme.DIVIDER:isFocused()&&cursor==index?UiTheme.ACCENT:chosen?UiTheme.FOCUS:UiTheme.BORDER);
                if(entry.icon().isEmpty())ui.glyph(UiGlyphArt.Kind.BRAND,x+7,y+9,17,UiTheme.MUTED);else ui.item(entry.icon(),x+6,y+7,21);
                ui.text(entry.name(),x+32,y+(ROW-GAP-ui.lineHeight())/2,w-52,active?UiTheme.TEXT:UiTheme.DISABLED_TEXT);
                if(chosen)ui.glyph(UiGlyphArt.Kind.CHECK,x+w-16,y+12,11,active?UiTheme.ACCENT:UiTheme.DISABLED_TEXT);
            }
        }finally{ui.unclip();}
        if(maxScroll()>0){double thumb=Math.max(18,height*(double)height/content()),y=getY()+(height-thumb)*scroll/maxScroll();ui.roundRect(getX()+width-4,getY(),getX()+width-2,getY()+height,1,UiTheme.TRACK);ui.roundRect(getX()+width-4,y,getX()+width-2,y+thumb,1,UiTheme.THUMB);}
        if(isFocused())ui.roundFrame(getX()-1,getY()-1,getX()+width-7,getY()+height+1,UiTheme.CARD_RADIUS,UiMotion.alpha(UiTheme.ACCENT,.5));
        if(hover>=0){var e=rows.get(hover);double tw=Math.min(width,Math.max(120,Math.max(ui.measure(e.name()),ui.measure(e.id()))+20));double th=2*ui.lineHeight()+14,x=Math.max(getX(),Math.min(mouseX+10,getX()+width-tw)),y=Math.max(getY(),Math.min(mouseY+14,getY()+height-th));ctx.getMatrices().push();ctx.getMatrices().translate(0,0,400);try{MenuScreen.tooltip(ui,x,y,tw,th);ui.text(e.name(),x+8,y+5,tw-16,UiTheme.TEXT);ui.text(e.id(),x+8,y+7+ui.lineHeight(),tw-16,UiTheme.SECONDARY);}finally{ctx.getMatrices().pop();}}
    }
}
