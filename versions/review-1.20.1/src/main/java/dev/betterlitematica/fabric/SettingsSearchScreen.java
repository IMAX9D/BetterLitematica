package dev.betterlitematica.fabric;
import java.util.*;
import net.minecraft.client.gui.screen.Screen;

/** Search is built from the actual page controls, so new settings cannot silently disappear. */
final class SettingsSearchScreen extends MenuScreen {
    enum Area {PRINTER("打印机"),BEDROCK("破基岩"),OPTIONS("设置");final String label;Area(String label){this.label=label;}}
    record Entry(Area area,int page,String section,SettingId id,String label,String description){}
    private List<Entry> index;private OverlayList list;private String query="";
    SettingsSearchScreen(Screen parent,ProjectionController controller){super("搜索全部设置","打印机、显示与交互设置",parent,controller,true);}
    private MenuScreen page(Area area,int page,boolean reuse){
        return switch(area){
            case PRINTER->(reuse&&parent instanceof PrinterScreen p?p:new PrinterScreen(this,controller)).page(page);
            case BEDROCK->(reuse&&parent instanceof BedrockScreen p?p:new BedrockScreen(this,controller)).page(page);
            case OPTIONS->(reuse&&parent instanceof OptionsScreen p?p:new OptionsScreen(this,controller)).page(page);
        };
    }
    private void collect(Area area,String[] sections){
        for(int i=0;i<sections.length;i++){var screen=page(area,i,false);screen.init(client,width,height);
            for(var entry:screen.settingControls())index.add(new Entry(area,i,sections[i],entry.id(),entry.label(),entry.hint()));
        }
    }
    @Override protected void buildMenu(){
        if(index==null){index=new ArrayList<>();collect(Area.PRINTER,new String[]{"施工","通用","策略","过滤","性能","高亮"});collect(Area.BEDROCK,new String[]{"常规","规则","方向","区域"});collect(Area.OPTIONS,new String[]{"交互","显示"});var found=java.util.EnumSet.noneOf(SettingId.class);for(var entry:index)if(!found.add(entry.id()))throw new IllegalStateException("设置索引重复："+entry.id());for(var id:SettingId.values())if(!found.contains(id))throw new IllegalStateException("设置未加入搜索："+id);}
        var field=fieldAt("名称、说明或拼音",query,left,0,innerWidth,128);field.setChangedListener(v->{query=v;rows();});
        list=new OverlayList(left,innerWidth,Math.max(80,bodyBottom-bodyTop-54),id->{var e=index.get(Integer.parseInt(id));var target=page(e.area(),e.page(),true);client.setScreen(target);target.focusSetting(e.id());});addBody(list,46);rows();
    }
    private void rows(){if(list!=null){var rows=new ArrayList<OverlayList.Row>();for(int i=0;i<index.size();i++){var e=index.get(i);if(SearchText.matches(e.label()+" "+e.area().label+" "+e.section()+" "+e.description(),query))rows.add(new OverlayList.Row(Integer.toString(i),e.label()+" · "+e.area().label+" / "+e.section(),true));}list.rows(rows,null);}}
}
