package dev.betterlitematica.fabric;
import java.util.*;
import net.minecraft.client.gui.screens.Screen;
final class ResourceScreen extends MenuScreen {
    private String selected="",deleting="";private OverlayList list;private List<ProjectionController.ResourceView> seen=List.of();private List<dev.betterlitematica.core.ProjectionCompatibility> seenCompatibility=List.of();
    private List<dev.betterlitematica.core.ProjectionCompatibility> compatibility(){return controller.resources().stream().map(r->controller.resourceCompatibility(r.source())).toList();}
    ResourceScreen(Screen parent,ProjectionController controller){super("已加载投影","",parent,controller,true);}
    void sourceChanged(String previous,String next){if(selected.equals(previous)){selected=next;deleting="";}}
    @Override protected void buildMenu(){seen=controller.resources();seenCompatibility=compatibility();var resource=seen.stream().filter(r->r.source().equals(selected)).findFirst().orElse(null);
        if(list==null)list=new OverlayList(left,innerWidth,bodyBottom-bodyTop-52,id->{selected=id;deleting="";refresh();});list.rows(seen.stream().map(r->new OverlayList.Row(r.source(),r.name()+" · "+r.placements()+" 个摆放"+(r.status().isEmpty()?"":" · "+r.status()),r.status().isEmpty(),controller.resourceCompatibility(r.source()))).toList(),selected);addBody(list,0);
        int y=bodyBottom-bodyTop-40;buttonAt("载入文件",cellX(0,5),y,cellWidth(5),()->ClientUi.setScreen(minecraft,new BlueprintBrowserScreen(this,controller,true)),true,false);
        buttonAt("创建摆放",cellX(1,5),y,cellWidth(5),()->{controller.load(selected);ClientUi.setScreen(minecraft,new PlacementConfigScreen(this,controller));},resource!=null&&resource.status().isEmpty(),true);
        buttonAt("另存",cellX(2,5),y,cellWidth(5),()->ClientUi.setScreen(minecraft,new SchematicFileScreen(this,controller,selected)),resource!=null,false);
        buttonAt("重载",cellX(3,5),y,cellWidth(5),()->{controller.reloadResource(selected);refresh();},resource!=null,false);
        buttonAt(deleting.equals(selected)&&!selected.isEmpty()?"确认卸载":"卸载",cellX(4,5),y,cellWidth(5),()->{if(resource!=null&&resource.placements()>0&&!deleting.equals(selected)){deleting=selected;refresh();return;}controller.unloadResource(selected);selected="";deleting="";refresh();},resource!=null,false);
    }
    @Override protected void updateMenu(){if(!seen.equals(controller.resources())||!seenCompatibility.equals(compatibility()))refresh();}
    @Override protected String statusLine(){return deleting.equals(selected)&&!selected.isEmpty()?seen.stream().filter(r->r.source().equals(selected)).map(r->"将移除 "+r.placements()+" 个摆放").findFirst().orElse(""):dev.betterlitematica.runtime.TemporarySources.temporary(selected)?"临时投影":selected;}
}
