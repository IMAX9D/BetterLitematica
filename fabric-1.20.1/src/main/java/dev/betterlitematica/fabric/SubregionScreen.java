package dev.betterlitematica.fabric;

import dev.betterlitematica.core.*;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.TextFieldWidget;
import java.util.*;

final class SubregionScreen extends MenuScreen {
    private final UUID id;private String selected;private final CoordinateDrafts drafts=new CoordinateDrafts();private TextFieldWidget px,py,pz;private OverlayList list;private boolean awaiting;
    SubregionScreen(Screen parent,ProjectionController controller,UUID id){super("子区域","",parent,controller,true);this.id=id;}
    private void change(java.util.function.UnaryOperator<RegionPlacement> action){change(action,false);}
    private void change(java.util.function.UnaryOperator<RegionPlacement> action,boolean coordinates){if(coordinates)drafts.replaceAfter(selected,()->controller.region(id,selected,action));else controller.region(id,selected,action);refresh();}
    @Override protected void buildMenu(){var placement=controller.placement(id);if(placement==null){label("投影已移除",0);return;}var regions=controller.regions(id);if(regions.isEmpty()){awaiting=true;label("投影加载中",0);return;}awaiting=false;
        var region=regions.stream().filter(r->r.name().equals(selected)).findFirst().orElse(regions.get(0));selected=region.name();var sub=placement.region(region);boolean movable=!placement.locked()&&!sub.locked();int w=(innerWidth-20)/2,x=left+w+20;
        double offset=list==null?0:list.scrollOffset();boolean focused=list!=null&&list.isFocused();if(list==null)list=new OverlayList(left,w,bodyBottom-bodyTop,name->{selected=name;refresh();setFocused(list);});list.rows(regions.stream().map(r->new OverlayList.Row(r.name(),r.name(),placement.region(r).enabled())).toList(),selected);list.scrollOffset(offset);addBody(list,0);if(focused)setFocused(list);
        caption("相对位置",x,0,w);
        buttonAt("移到玩家位置",x,22,w,()->change(p->p.moved(placement.transform().inverse(new Vec3i(client.player.getBlockX(),client.player.getBlockY(),client.player.getBlockZ()))),true),movable,false);
        px=coordinate("X",0,sub.position(),x,54,w);py=coordinate("Y",1,sub.position(),x,84,w);pz=coordinate("Z",2,sub.position(),x,114,w);px.setEditable(movable);py.setEditable(movable);pz.setEditable(movable);
        buttonAt("应用坐标",x,146,w,()->change(p->p.moved(drafts.position(selected)),true),movable,true);
        int half=(w-8)/2;
        buttonAt("旋转 "+sub.quarterTurns()*90+"°",x,174,half,()->change(p->new RegionPlacement(p.position(),p.quarterTurns()+1,p.mirrorX(),p.mirrorZ(),p.enabled(),p.locked())),movable,false);
        int mirror=(sub.mirrorX()?1:0)|(sub.mirrorZ()?2:0),next=(mirror+1)%4;
        buttonAt("镜像 "+new String[]{"无","X","Z","XZ"}[mirror],x+half+8,174,half,()->change(p->new RegionPlacement(p.position(),p.quarterTurns(),(next&1)!=0,(next&2)!=0,p.enabled(),p.locked())),movable,false);
        buttonAt(sub.enabled()?"启用：是":"启用：否",x,202,half,()->change(p->p.enabled(!p.enabled())),true,false);
        buttonAt(sub.locked()?"已锁定":"未锁定",x+half+8,202,half,()->change(p->new RegionPlacement(p.position(),p.quarterTurns(),p.mirrorX(),p.mirrorZ(),p.enabled(),!p.locked())),true,false);
        buttonAt("重置",x,236,w,()->{drafts.replaceAfter(selected,()->controller.resetRegion(id,selected));refresh();},movable,false);
    }
    @Override protected void updateMenu(){if(awaiting&&!controller.regions(id).isEmpty())refresh();}
    private TextFieldWidget coordinate(String label,int axis,Vec3i value,int x,int y,int width){caption(label,x,y+2,18);var field=new OverlayTextField(x+28,0,width-34,label);field.setMaxLength(12);String region=selected;field.setText(drafts.value(region,axis,value));field.setChangedListener(text->drafts.set(region,axis,text));addBody(field,y);return field;}
    /** Raw text survives widget rebuilds; only successful coordinate actions replace that region's draft. */
    static final class CoordinateDrafts {
        private final Map<String,String[]> values=new HashMap<>();
        String value(String region,int axis,Vec3i committed){return values.computeIfAbsent(region,key->new String[]{Integer.toString(committed.x()),Integer.toString(committed.y()),Integer.toString(committed.z())})[axis];}
        void set(String region,int axis,String text){values.get(region)[axis]=text;}
        Vec3i position(String region){var value=values.get(region);return new Vec3i(Integer.parseInt(value[0]),Integer.parseInt(value[1]),Integer.parseInt(value[2]));}
        void replaceAfter(String region,Runnable action){action.run();values.remove(region);}
    }
}
