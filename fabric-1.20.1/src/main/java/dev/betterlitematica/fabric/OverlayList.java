package dev.betterlitematica.fabric;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;
import java.util.*;
import java.util.function.Consumer;

/** Virtual scrolling list with one identity per row and keyboard selection. */
final class OverlayList extends ButtonWidget {
    record Row(String id,String name,boolean enabled){}
    private List<Row> rows=List.of();private String selected;private final Consumer<String> choose;private double scroll;private boolean dragging;private int hovered=-1;private long since;
    private static final int ROW=26;
    private final Map<String,UiMotion> motion=new LinkedHashMap<>();
    private UiMotion motion(String id){if(motion.size()>128)motion.clear();return motion.computeIfAbsent(id,key->new UiMotion());}
    OverlayList(int x,int width,int height,Consumer<String> choose){super(x,0,width,height,Text.literal("列表"),b->{},DEFAULT_NARRATION_SUPPLIER);this.choose=choose;}
    void rows(List<Row> values,String selection){rows=List.copyOf(values);selected=selection;scroll=Math.min(scroll,maxScroll());}
    double scrollOffset(){return scroll;}void scrollOffset(double value){scroll=Math.max(0,Math.min(maxScroll(),value));}
    private int maxScroll(){return Math.max(0,rows.size()*ROW-height);}
    private int hit(double x,double y){if(!isMouseOver(x,y)||x>=getX()+width-8)return -1;int row=(int)((y-getY()+scroll)/ROW);return row<rows.size()?row:-1;}
    @Override public boolean mouseScrolled(double x,double y,double amount){if(!isMouseOver(x,y))return false;scroll=Math.max(0,Math.min(maxScroll(),scroll-amount*ROW));return true;}
    @Override public boolean mouseClicked(double x,double y,int button){if(!active||button!=0||!isMouseOver(x,y))return false;setFocused(true);dragging=x>=getX()+width-8&&maxScroll()>0;if(dragging)drag(y);else{int i=hit(x,y);if(i>=0){selected=rows.get(i).id();motion(selected).press();choose.accept(selected);}}return true;}
    private void drag(double y){double thumb=Math.max(18,height*(double)height/Math.max(1,rows.size()*ROW));scroll=Math.max(0,Math.min(maxScroll(),(y-getY()-thumb/2)/Math.max(1,height-thumb)*maxScroll()));}
    @Override public boolean mouseDragged(double x,double y,int button,double dx,double dy){if(!dragging||button!=0)return false;drag(y);return true;}
    @Override public boolean mouseReleased(double x,double y,int button){boolean previous=dragging;dragging=false;return previous;}
    @Override public boolean keyPressed(int key,int scan,int modifiers){int i=0;for(int j=0;j<rows.size();j++)if(Objects.equals(selected,rows.get(j).id()))i=j;
        if(!active||rows.isEmpty())return false;switch(key){case 264->i++;case 265->i--;case 267->i+=Math.max(1,height/ROW);case 266->i-=Math.max(1,height/ROW);case 268->i=0;case 269->i=rows.size()-1;default->{return false;}}
        i=Math.max(0,Math.min(rows.size()-1,i));selected=rows.get(i).id();scroll=Math.max(0,Math.min(maxScroll(),Math.min(i*ROW,Math.max(scroll,(i+1)*ROW-height))));choose.accept(selected);return true;
    }
    @Override public void renderButton(DrawContext ctx,int mx,int my,float delta){var ui=IndependentUi.INSTANCE;int h=hit(mx,my);if(h!=hovered){hovered=h;since=System.nanoTime();}
        ui.clip(getX(),getY(),getX()+width,getY()+height);try{int start=(int)(scroll/ROW),end=Math.min(rows.size(),(int)Math.ceil((scroll+height)/ROW));for(int i=start;i<end;i++){var row=rows.get(i);double y=getY()+i*ROW-scroll;boolean chosen=Objects.equals(row.id(),selected);var animation=motion(row.id());int base=chosen?UiTheme.SELECTED:UiTheme.SURFACE;int fill=UiMotion.mix(base,UiTheme.HOVER,animation.hover(i==h&&active));fill=UiMotion.mix(fill,UiTheme.SELECTED,animation.pressed()*.35);ui.roundRect(getX(),y,getX()+width-9,y+ROW-4,6,fill);
                if(chosen&&isFocused())ui.roundFrame(getX(),y,getX()+width-9,y+ROW-4,6,UiTheme.FOCUS);
                ui.roundRect(getX()+5,y+9,getX()+8,y+12,1.5,row.enabled()?UiTheme.ACCENT:UiTheme.DISABLED_TEXT);double x=getX()+13,space=width-28;ui.clip(x,y+1,getX()+width-12,y+ROW-5);try{if(i==h&&ui.measure(row.name())>space)ui.rawText(row.name(),x-BrowserGrid.marqueeOffset(ui.measure(row.name())-space,(System.nanoTime()-since)/1e9),y+4,UiTheme.TEXT);else ui.text(row.name(),x,y+4,space,UiTheme.TEXT);}finally{ui.unclip();}}
        }finally{ui.unclip();}if(maxScroll()>0){double thumb=Math.max(18,height*(double)height/(rows.size()*ROW)),y=getY()+(height-thumb)*scroll/maxScroll();ui.roundRect(getX()+width-5,getY(),getX()+width-2,getY()+height,1.5,UiTheme.TRACK);ui.roundRect(getX()+width-5,y,getX()+width-2,y+thumb,1.5,UiTheme.ACCENT);}
    }
}
