package dev.betterlitematica.fabric;
import dev.betterlitematica.core.LayerRange;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.TextFieldWidget;
final class LayerScreen extends MenuScreen {
    private LayerRange.Axis axis;private LayerRange.Mode mode;private String first,second;private TextFieldWidget min,max;
    LayerScreen(Screen parent,ProjectionController controller){super("分层","",parent,controller,false);sync(controller.layerRange());}
    static int first(LayerRange value){return value.min()==Integer.MIN_VALUE?value.max()==Integer.MAX_VALUE?0:value.max():value.min();}
    static int second(LayerRange value){return value.max()==Integer.MAX_VALUE?first(value):value.max();}
    private void sync(LayerRange value){axis=value.axis();mode=value.mode();first=Integer.toString(first(value));second=Integer.toString(second(value));}
    private static int coordinate(String text){int value=Integer.parseInt(text);if(value< -30_000_000||value>30_000_000)throw new IllegalArgumentException("层数超出坐标范围");return value;}
    private void apply(){int a=mode==LayerRange.Mode.ALL?0:coordinate(first),b=mode==LayerRange.Mode.RANGE?coordinate(second):a;var value=LayerRange.of(axis,mode,a,b);controller.layer(value.axis(),value.min(),value.max());}
    @Override protected void buildMenu(){
        var modes=LayerRange.Mode.values();String[] labels={"全部","单层","范围","以上","以下"};for(int i=0;i<modes.length;i++){var next=modes[i];buttonAt(labels[i],cellX(i,5),0,cellWidth(5),()->mode(next),true,mode==next);}
        for(int i=0;i<3;i++){var next=LayerRange.Axis.values()[i];button(next.name(),i,3,2,()->{var old=axis;axis=next;try{apply();refresh();}catch(RuntimeException e){axis=old;throw e;}},true,axis==next);}
        min=fieldAt(mode==LayerRange.Mode.RANGE?"最小层":"当前层",first,left,88,cellWidth(2),12);min.setChangedListener(v->first=v);min.setEditable(mode!=LayerRange.Mode.ALL);
        max=fieldAt("最大层",second,cellX(1,2),88,cellWidth(2),12);max.setChangedListener(v->second=v);max.setEditable(mode==LayerRange.Mode.RANGE);
        button("应用",0,2,6,this::apply,true,true);button("玩家位置",1,2,6,()->{apply();controller.layerAtPlayer();sync(controller.layerRange());refresh();});
        button("−",0,2,7,()->shift(-1));button("+",1,2,7,()->shift(1));
        button("跟随玩家："+(controller.options().followLayer?"开":"关"),0,1,9,()->{boolean next=!controller.options().followLayer;if(next){apply();controller.layerAtPlayer();sync(controller.layerRange());}controller.options().followLayer=next;try{controller.saveOptions();}finally{refresh();}},true,controller.options().followLayer);
    }
    private void mode(LayerRange.Mode next){var old=mode;String oldSecond=second;try{if(next==LayerRange.Mode.RANGE&&Integer.parseInt(second)<Integer.parseInt(first))second=first;mode=next;apply();refresh();}catch(RuntimeException e){mode=old;second=oldSecond;throw e;}}
    private void shift(int amount){apply();controller.shiftLayer(amount);sync(controller.layerRange());refresh();}
}
