package dev.betterlitematica.fabric;
import dev.betterlitematica.core.*;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;
import java.util.*;
import java.util.function.Consumer;
/** Virtual verifier rows with explicit ignore/restore actions. */
final class VerificationList extends ButtonWidget {
    record Row(VerificationReport.Key key,long count,boolean ignored){}
    private static final int ROW=44;private List<Row> rows=List.of();private double scroll;private boolean dragging;private String emptyMessage="尚未校验";
    private final Consumer<VerificationReport.Key> ignore;
    private final Map<String,String> names=new HashMap<>();
    private final Map<String,net.minecraft.item.ItemStack> icons=new LinkedHashMap<>();
    private static net.minecraft.block.Block block(String state){int start=state.indexOf('{'),end=state.indexOf('}');String id=start>=0&&end>start?state.substring(start+1,end):state.split("\\[",2)[0];var key=net.minecraft.util.Identifier.tryParse(id);return key!=null&&net.minecraft.registry.Registries.BLOCK.containsId(key)?net.minecraft.registry.Registries.BLOCK.get(key):null;}
    static net.minecraft.item.ItemStack icon(String state){var block=block(state);if(block==null)return net.minecraft.item.ItemStack.EMPTY;if(block==net.minecraft.block.Blocks.WATER)return new net.minecraft.item.ItemStack(net.minecraft.item.Items.WATER_BUCKET);if(block==net.minecraft.block.Blocks.LAVA)return new net.minecraft.item.ItemStack(net.minecraft.item.Items.LAVA_BUCKET);return new net.minecraft.item.ItemStack(block.asItem());}
    private void drawIcon(IndependentUi ui,String state,double x,double y){
        var stack=icons.get(state);if(stack==null){if(icons.size()>=256)icons.clear();stack=icon(state);icons.put(state,stack);}
        if(!stack.isEmpty())ui.item(stack,x,y,26);
        else{var block=block(state);ui.frame(x+3,y+3,x+23,y+23,UiTheme.DIVIDER);ui.centered(block!=null&&block.getDefaultState().isAir()?"—":"?",x,y,26,26,UiTheme.SECONDARY);}
    }
    VerificationList(int x,int width,int height,Consumer<VerificationReport.Key> ignore){super(x,0,width,height,Text.literal("校验结果"),b->{},DEFAULT_NARRATION_SUPPLIER);this.ignore=ignore;}
    void rows(List<Row> value,boolean reset){rows=List.copyOf(value);if(reset)scroll=0;clamp();}
    void emptyMessage(String value){emptyMessage=value;}
    static String type(Comparison type){return switch(type){case MATCH->"一致";case MISSING->"缺失";case EXTRA->"多余";case WRONG_BLOCK->"方块错误";case WRONG_STATE->"状态错误";case UNKNOWN->"未知";};}
    private String name(String state){return names.computeIfAbsent(state,s->{int start=s.indexOf('{'),end=s.indexOf('}');String id=start>=0&&end>start?s.substring(start+1,end):s.split("\\[",2)[0];var key=net.minecraft.util.Identifier.tryParse(id);return key!=null&&net.minecraft.registry.Registries.BLOCK.containsId(key)?net.minecraft.registry.Registries.BLOCK.get(key).getName().getString():s.isEmpty()?"未知":s;});}
    private int max(){return Math.max(0,rows.size()*ROW-height);}private void clamp(){scroll=Math.max(0,Math.min(max(),scroll));}
    private int hit(double x,double y){if(!isMouseOver(x,y)||x>=getX()+width-8)return -1;int i=(int)((y-getY()+scroll)/ROW);return i>=0&&i<rows.size()?i:-1;}
    @Override public boolean mouseScrolled(double x,double y,double amount){if(!isMouseOver(x,y))return false;scroll-=amount*ROW;clamp();return true;}
    @Override public boolean mouseClicked(double x,double y,int button){if(!active||button!=0||!isMouseOver(x,y))return false;setFocused(true);if(x>=getX()+width-8&&max()>0){dragging=true;drag(y);return true;}int i=hit(x,y);if(i>=0){double relative=y-(getY()+i*ROW-scroll);if(relative<9||relative>=31)return true;var row=rows.get(i);if(x>=getX()+width-62&&x<getX()+width-14)ignore.accept(row.key());}return true;}
    private void drag(double y){double thumb=Math.max(18,height*(double)height/Math.max(1,rows.size()*ROW));scroll=(y-getY()-thumb/2)/Math.max(1,height-thumb)*max();clamp();}
    @Override public boolean mouseDragged(double x,double y,int button,double dx,double dy){if(!dragging||button!=0)return false;drag(y);return true;}
    @Override public boolean mouseReleased(double x,double y,int button){boolean old=dragging;dragging=false;return old;}
    @Override public boolean keyPressed(int key,int scan,int mods){switch(key){case 264->scroll+=ROW;case 265->scroll-=ROW;case 267->scroll+=height;case 266->scroll-=height;case 268->scroll=0;case 269->scroll=max();default->{return false;}}clamp();return true;}
    @Override public void renderButton(DrawContext ctx,int mx,int my,float delta){var ui=IndependentUi.INSTANCE;int hovered=hit(mx,my);ui.clip(getX(),getY(),getX()+width,getY()+height);try{
        if(rows.isEmpty())ui.text(emptyMessage,getX()+6,getY()+6,width-12,UiTheme.SECONDARY);
        int first=(int)(scroll/ROW),last=Math.min(rows.size(),(int)Math.ceil((scroll+height)/ROW));for(int i=first;i<last;i++){var row=rows.get(i);double y=getY()+i*ROW-scroll;ui.rect(getX(),y,getX()+width-10,y+ROW-4,i==hovered?UiTheme.HOVER:UiTheme.SURFACE);
            drawIcon(ui,row.key().expected(),getX()+6,y+7);ui.text("→",getX()+34,y+14,14,UiTheme.SECONDARY);drawIcon(ui,row.key().actual(),getX()+48,y+7);
            ui.text(type(row.key().type())+"  × "+row.count(),getX()+84,y+4,width-159,UiTheme.TEXT);ui.text(name(row.key().expected())+" → "+name(row.key().actual()),getX()+84,y+22,width-159,UiTheme.SECONDARY);
            ui.rect(getX()+width-62,y+9,getX()+width-14,y+31,UiTheme.SURFACE);ui.centered(row.ignored()?"恢复":"忽略",getX()+width-62,y+9,48,22,UiTheme.TEXT);
        }
    }finally{ui.unclip();}if(max()>0){double thumb=Math.max(18,height*(double)height/(rows.size()*ROW)),y=getY()+(height-thumb)*scroll/max();ui.rect(getX()+width-5,getY(),getX()+width-2,getY()+height,UiTheme.TRACK);ui.rect(getX()+width-5,y,getX()+width-2,y+thumb,UiTheme.ACCENT);}
        if(hovered>=0&&mx<getX()+width-68){var row=rows.get(hovered);int y=Math.max(getY(),Math.min(getY()+height-36,my+14));ctx.getMatrices().push();ctx.getMatrices().translate(0,0,400);try{ui.rect(getX(),y,getX()+width-70,y+34,UiTheme.TOOLTIP);ui.text(row.key().expected(),getX()+5,y+3,width-80,UiTheme.TEXT);ui.text(row.key().actual(),getX()+5,y+19,width-80,UiTheme.SECONDARY);}finally{ctx.getMatrices().pop();}}
    }
}
