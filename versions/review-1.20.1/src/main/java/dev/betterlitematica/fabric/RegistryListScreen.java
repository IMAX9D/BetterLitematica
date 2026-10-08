package dev.betterlitematica.fabric;
import java.util.*;
import java.util.function.Consumer;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.registry.Registries;

/** A local selection draft; caller's settings are updated only by Save. */
final class RegistryListScreen extends MenuScreen {
    enum Kind {BLOCK,ITEM,FLUID}
    private final List<OverlayList.Row> candidates;private final Set<String> selected;
    private boolean discardConfirmed;private final Set<String> initial;
    private final Consumer<List<String>> save;private OverlayList list;private String query="";
    RegistryListScreen(Screen parent,ProjectionController controller,String title,Kind kind,List<String> values,Consumer<List<String>> save){
        super(title,"名称、ID、全拼或首字母",parent,controller,true);this.save=save;selected=new LinkedHashSet<>(values);initial=Set.copyOf(values);
        var rows=new ArrayList<OverlayList.Row>();
        if(kind==Kind.BLOCK)Registries.BLOCK.forEach(b->rows.add(new OverlayList.Row(Registries.BLOCK.getId(b).toString(),b.getName().getString(),false)));
        else if(kind==Kind.ITEM)Registries.ITEM.forEach(i->rows.add(new OverlayList.Row(Registries.ITEM.getId(i).toString(),i.getName().getString(),false)));
        else Registries.FLUID.forEach(f->{String id=Registries.FLUID.getId(f).toString();var block=f.getDefaultState().getBlockState().getBlock();rows.add(new OverlayList.Row(id,block.getName().getString()+" · "+id,false));});
        rows.sort(Comparator.comparing(OverlayList.Row::id));candidates=List.copyOf(rows);
    }
    @Override protected boolean showBack(){return false;}
    @Override protected void buildMenu(){
        var field=fieldAt("搜索",query,left,0,innerWidth,128);field.setChangedListener(v->{query=v;rows();});
        list=new OverlayList(left,innerWidth,Math.max(80,bodyBottom-bodyTop-54),id->{if(!selected.remove(id))selected.add(id);discardConfirmed=false;rows();});addBody(list,46);rows();
        fixedAction("保存",0,90,()->{save.accept(List.copyOf(selected));super.close();});fixed(discardConfirmed?"确认放弃":"放弃更改",98,90,this::close);
    }
    private void rows(){if(list!=null)list.rows(candidates.stream().filter(r->SearchText.matches(r.id()+" "+r.name(),query)).sorted(Comparator.comparingInt(r->SearchText.rank(r.name(),r.id(),query))).map(r->new OverlayList.Row(r.id(),r.name()+" · "+r.id(),selected.contains(r.id()))).toList(),null);}
    @Override public boolean keyPressed(int key,int scan,int modifiers){if((key==257||key==335)&&getFocused() instanceof net.minecraft.client.gui.widget.TextFieldWidget){save.accept(List.copyOf(selected));super.close();return true;}return super.keyPressed(key,scan,modifiers);}
    @Override public void close(){if(!selected.equals(initial)&&!discardConfirmed){discardConfirmed=true;refresh();return;}super.close();}
}
