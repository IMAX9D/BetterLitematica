package dev.betterlitematica.fabric;
import net.minecraft.client.gui.screen.Screen;
import java.util.UUID;
final class PlacementListScreen extends MenuScreen {
    private OverlayList list;private String snapshot="";
    PlacementListScreen(Screen parent,ProjectionController controller){super("投影摆放","",parent,controller,true);}
    @Override protected void buildMenu(){
        boolean selected=controller.selectedPlacement()!=null;button("配置",0,4,0,()->client.setScreen(new PlacementConfigScreen(this,controller)),selected,true);button("复制",1,4,0,()->{controller.duplicate();refresh();},selected,false);button("重载",2,4,0,controller::reload,selected,false);button("移除",3,4,0,()->{controller.unload();refresh();},selected,false);
        if(list==null)list=new OverlayList(left,innerWidth,Math.max(30,bodyBottom-bodyTop-38),id->{controller.select(UUID.fromString(id).equals(controller.selectedId())?null:UUID.fromString(id));refresh();});addBody(list,38);rows();
    }
    private void rows(){list.rows(controller.placements().stream().map(p->new OverlayList.Row(p.id().toString(),p.name(),p.enabled()&&p.renderBlocks())).toList(),controller.selectedId()==null?null:controller.selectedId().toString());snapshot=controller.placements().toString()+controller.selectedId();}
    @Override protected void updateMenu(){if(!snapshot.equals(controller.placements().toString()+controller.selectedId()))refresh();}
}
