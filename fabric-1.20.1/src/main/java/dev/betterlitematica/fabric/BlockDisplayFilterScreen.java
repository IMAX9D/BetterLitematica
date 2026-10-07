package dev.betterlitematica.fabric;

import dev.betterlitematica.core.BlockDisplayFilter;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.text.Text;
import java.util.*;

/** A placement-bound draft; registry filtering never requests blueprint data. */
final class BlockDisplayFilterScreen extends MenuScreen {
    private final UUID id;
    private final long epoch;
    private final List<BlockFilterGrid.Entry> registry;
    private final Set<String> blacklist=new HashSet<>(),whitelist=new HashSet<>();
    private final BlockDisplayFilter saved;
    private Set<String> selected=blacklist;
    private BlockDisplayFilter.Mode mode;
    private String query="",error="";
    private String[] terms=new String[0];
    private boolean selectedOnly,discardArmed;
    private BlockFilterGrid grid;
    private OverlayLabel count;
    private ButtonWidget selectResults,clear,save;
    BlockDisplayFilterScreen(Screen parent,ProjectionController controller,UUID id){super("方块显示","",parent,controller,true);this.id=id;epoch=controller.sessionEpoch();var placement=controller.placement(id);saved=placement==null?BlockDisplayFilter.OFF:placement.displayFilter();mode=saved.mode();blacklist.addAll(saved.blacklist());whitelist.addAll(saved.whitelist());selected=mode==BlockDisplayFilter.Mode.WHITELIST?whitelist:blacklist;registry=Registries.BLOCK.stream().map(block->new BlockFilterGrid.Entry(Registries.BLOCK.getId(block).toString(),block.getName().getString(),new ItemStack(block.asItem()))).sorted(Comparator.comparing(BlockFilterGrid.Entry::id)).toList();}
    private boolean off(){return mode==BlockDisplayFilter.Mode.OFF;}
    private boolean dirty(){return mode!=saved.mode()||!blacklist.equals(saved.blacklist())||!whitelist.equals(saved.whitelist());}
    @Override protected String backLabel(){return discardArmed?"确认放弃":dirty()?"放弃":"返回";}
    @Override protected void runAction(Runnable action){try{error="";action.run();}catch(RuntimeException ex){error=ex.getMessage()==null?"操作失败":ex.getMessage();}}
    @Override protected String displayedStatus(){
        if(!error.isEmpty())return error;
        if(discardArmed)return "更改尚未保存";
        if(mode==BlockDisplayFilter.Mode.WHITELIST&&whitelist.isEmpty())return "白名单为空：投影方块将全部隐藏";
        return off()?"过滤已关闭，投影方块全部显示":"";
    }
    @Override protected int statusColor(){return !error.isEmpty()?UiTheme.ERROR:discardArmed||mode==BlockDisplayFilter.Mode.WHITELIST&&whitelist.isEmpty()?UiTheme.WARNING:UiTheme.MUTED;}
    @Override protected void buildMenu(){var placement=controller.placement(id);if(placement==null){label("投影已移除",0);return;}
        addBody(new OverlayLabel(left,innerWidth,placement.name()),0);
        var modes=BlockDisplayFilter.Mode.values();String[] names={"关闭","黑名单","白名单"};for(int i=0;i<modes.length;i++){var value=modes[i];tabAt(names[i],cellX(i,3),24,cellWidth(3),()->{if(mode==value)return;mode=value;selected=mode==BlockDisplayFilter.Mode.WHITELIST?whitelist:blacklist;grid=null;discardArmed=false;refresh();},mode==value);}
        var search=fieldAt("搜索",query,left,54,innerWidth-134,128);search.setEditable(!off());
        int half=(126-6)/2;
        tabAt("全部",left+innerWidth-126,68,half,()->{if(!selectedOnly)return;selectedOnly=false;refresh();refilter(true);},!selectedOnly).active=!off();
        tabAt("已选",left+innerWidth-half,68,half,()->{if(selectedOnly)return;selectedOnly=true;refresh();refilter(true);},selectedOnly).active=!off();
        selectResults=buttonAt("全选搜索结果",left,98,128,this::selectResults,true,false);
        clear=ghostAt("清空",left+136,98,64,()->{selected.clear();changed();},!selected.isEmpty());
        count=new OverlayLabel(left+210,innerWidth-210,"");addBody(count,98);
        boolean fresh=grid==null;
        if(fresh)grid=new BlockFilterGrid(left,innerWidth,Math.max(40,bodyBottom-bodyTop-126),selected,this::toggle);
        addBody(grid,126);refilter(fresh);
        search.setChangedListener(text->{query=text;String normalized=text.strip().toLowerCase(Locale.ROOT);terms=normalized.isEmpty()?new String[0]:normalized.split("\\s+");refilter(true);});
        save=fixedAction("保存",innerWidth-168,84,this::save);
    }
    private boolean matches(BlockFilterGrid.Entry e){if(terms.length==0)return true;String value=e.id()+" "+e.name().toLowerCase(Locale.ROOT);for(String part:terms)if(!value.contains(part))return false;return true;}
    /** Ordering is fixed when the view changes (search, mode, scope) so ticking a block never moves cards under the pointer. */
    private void refilter(boolean reset){
        if(grid==null)return;
        if(reset)grid.rows(registry.stream().filter(this::matches).filter(e->!selectedOnly||selected.contains(e.id())).sorted(Comparator.comparing(e->!selected.contains(e.id()))).toList(),true);
        grid.active=!off();
        count.setMessage(Text.literal(off()?"":"已选 "+selected.size()));
        selectResults.active=!off()&&registry.stream().anyMatch(e->matches(e)&&!selected.contains(e.id()));clear.active=!off()&&!selected.isEmpty();error="";
    }
    private void changed(){discardArmed=false;refilter(false);}
    private void toggle(String blockId){if(!selected.remove(blockId))selected.add(blockId);changed();}
    private void selectResults(){for(var entry:registry)if(matches(entry))selected.add(entry.id());changed();}
    private void save(){if(epoch!=controller.sessionEpoch()||controller.placement(id)==null)throw new IllegalStateException("投影已移除");controller.placementDisplayFilter(id,new BlockDisplayFilter(mode,blacklist,whitelist));super.close();}
    /** Leaving with unsaved edits takes a second, deliberate press; Esc follows the same path. */
    @Override public void close(){
        if(controller.placement(id)==null||!dirty()||discardArmed){super.close();return;}
        discardArmed=true;refresh();
    }
    @Override protected void updateMenu(){if(controller.placement(id)==null){error="投影已移除";if(grid!=null)grid.active=false;if(save!=null)save.active=false;if(selectResults!=null)selectResults.active=false;if(clear!=null)clear.active=false;}}
    @Override public boolean keyPressed(int key,int scan,int modifiers){if(getFocused()==grid&&grid!=null&&grid.keyPressed(key,scan,modifiers))return true;return super.keyPressed(key,scan,modifiers);}
    @Override public boolean mouseReleased(double x,double y,int button){boolean owned=grid!=null&&grid.mouseReleased(0,0,button);return super.mouseReleased(x,y,button)||owned;}
}
