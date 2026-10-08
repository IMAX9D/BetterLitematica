package dev.betterlitematica.fabric;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.item.ItemStack;
import net.minecraft.text.Text;
import java.util.*;

/** Virtual grid: only the rows intersecting the viewport are painted. */
final class MaterialGrid extends ButtonWidget {
    private static final int COLUMNS=5,ROW=72,GAP=6;
    private List<PlacementAnalysis.Material> rows=List.of();
    private List<ItemStack> icons=List.of();
    private double scroll;private boolean dragging;
    MaterialGrid(int x,int width,int height){super(x,0,width,height,Text.literal("材料"),b->{},DEFAULT_NARRATION_SUPPLIER);}
    void rows(List<PlacementAnalysis.Material> next,boolean reset){
        if(!rows.equals(next)){rows=List.copyOf(next);icons=rows.stream().map(row->new ItemStack(row.item())).toList();}
        if(reset)scroll=0;clamp();
    }
    double scrollOffset(){return scroll;}
    private int content(){return ((rows.size()+COLUMNS-1)/COLUMNS)*ROW;}
    private int maxScroll(){return Math.max(0,content()-height);}
    private void clamp(){scroll=Math.max(0,Math.min(scroll,maxScroll()));}
    @Override public boolean mouseScrolled(double x,double y,double horizontal,double amount){if(!isMouseOver(x,y))return false;scroll-=amount*36;clamp();return true;}
    @Override public boolean mouseClicked(double x,double y,int button){if(button!=0||!isMouseOver(x,y))return false;setFocused(true);dragging=maxScroll()>0&&x>=getX()+width-8;if(dragging)dragTo(y);return true;}
    private void dragTo(double y){double thumb=Math.max(18,height*(double)height/Math.max(1,content()));scroll=(y-getY()-thumb/2)/Math.max(1,height-thumb)*maxScroll();clamp();}
    @Override public boolean mouseDragged(double x,double y,int button,double dx,double dy){if(!dragging||button!=0)return false;dragTo(y);return true;}
    @Override public boolean mouseReleased(double x,double y,int button){boolean was=dragging;dragging=false;return was;}
    @Override public boolean keyPressed(int key,int scan,int modifiers){
        if(key==264)scroll+=36;else if(key==265)scroll-=36;else if(key==267)scroll+=height;else if(key==266)scroll-=height;else if(key==268)scroll=0;else if(key==269)scroll=maxScroll();else return false;
        clamp();return true;
    }
    private final Map<Integer,UiMotion> motion=new LinkedHashMap<>();
    private UiMotion motion(int index){if(motion.size()>128)motion.clear();return motion.computeIfAbsent(index,key->new UiMotion());}
    @Override public void renderButton(DrawContext ctx,int mouseX,int mouseY,float delta){
        var ui=IndependentUi.INSTANCE;int cardWidth=(width-10-(COLUMNS-1)*GAP)/COLUMNS,hover=-1;
        ui.clip(getX(),getY(),getX()+width,getY()+height);
        try{
            if(rows.isEmpty())ui.text("无匹配材料",getX()+4,getY()+6,width-8,UiTheme.SECONDARY);
            int first=Math.max(0,(int)(scroll/ROW)),last=Math.min((rows.size()+COLUMNS-1)/COLUMNS,(int)Math.ceil((scroll+height)/ROW));
            for(int row=first;row<last;row++)for(int col=0;col<COLUMNS;col++){
                int index=row*COLUMNS+col;if(index>=rows.size())break;var material=rows.get(index);
                int x=getX()+col*(cardWidth+GAP);double y=getY()+row*ROW-scroll;boolean over=mouseX>=x&&mouseX<x+cardWidth&&mouseY>=Math.max(getY(),y)&&mouseY<Math.min(getY()+height,y+ROW-GAP);
                if(over)hover=index;
                double h=motion(index).hover(over),bottom=y+ROW-GAP;
                ui.roundRect(x,y,x+cardWidth,bottom,UiTheme.CARD_RADIUS,UiMotion.mix(UiTheme.SURFACE,UiTheme.HOVER,h));
                ui.roundFrame(x,y,x+cardWidth,bottom,UiTheme.CARD_RADIUS,UiMotion.mix(UiTheme.BORDER,UiTheme.BORDER_STRONG,h));ui.sheen(x,y+ui.pixel(),x+cardWidth,UiTheme.CARD_RADIUS);
                ui.item(icons.get(index),x+6,y+6,20);
                ui.text(material.item().getName().getString(),x+31,y+9,cardWidth-36,UiTheme.TEXT);
                long missing=Math.max(0,material.total()-material.available());
                // Progress rail: how much of this material is already on hand.
                double progress=material.total()<=0?1:Math.min(1,material.available()/(double)material.total()),rail=y+32;
                ui.roundRect(x+6,rail,x+cardWidth-6,rail+2,1,UiTheme.TRACK);
                if(progress>0)ui.roundRect(x+6,rail,x+6+(cardWidth-12)*progress,rail+2,1,missing>0?UiTheme.WARNING:UiTheme.SUCCESS);
                String have="有 "+material.available();double hw=Math.min(cardWidth*.5,ui.measure(have)+1);
                String need=Long.toString(material.total());ui.text(need,x+6,y+38,cardWidth-18-hw,UiTheme.TEXT);
                ui.text(missing>0?"缺 "+missing:"已备齐",x+6,y+51,cardWidth-12,missing>0?UiTheme.WARNING:UiTheme.SUCCESS);
                ui.text(have,x+cardWidth-6-hw,y+38,hw,UiTheme.MUTED);
            }
        }finally{ui.unclip();}
        if(maxScroll()>0){double thumb=Math.max(18,height*(double)height/content()),y=getY()+(height-thumb)*scroll/maxScroll();ui.roundRect(getX()+width-4,getY(),getX()+width-2,getY()+height,1,UiMotion.alpha(UiTheme.TRACK,.8));ui.roundRect(getX()+width-4,y,getX()+width-2,y+thumb,1,UiTheme.THUMB);}
        if(hover>=0){var m=rows.get(hover);int x=Math.min(getX()+width-244,Math.max(getX(),mouseX+10)),y=Math.min(getY()+height-62,Math.max(getY(),mouseY+12));
            ctx.getMatrices().push();ctx.getMatrices().translate(0,0,400);
            try{MenuScreen.tooltip(ui,x,y,240,60);ui.text(m.item().getName().getString(),x+6,y+4,228,UiTheme.TEXT);
            ui.text("总 "+m.total()+" · 有 "+m.available(),x+6,y+22,228,UiTheme.SECONDARY);
            int stack=Math.max(1,m.item().getMaxCount());ui.text(m.total()/stack+" 组 "+m.total()%stack+" 个 · "+(long)Math.ceil(m.total()/(stack*27.0))+" 盒",x+6,y+40,228,UiTheme.SECONDARY);
            }finally{ctx.getMatrices().pop();}
        }
        if(isFocused())ui.roundFrame(getX()-2,getY()-2,getX()+width-7,getY()+height+2,UiTheme.CARD_RADIUS,UiMotion.alpha(UiTheme.ACCENT,.5));
    }
}
