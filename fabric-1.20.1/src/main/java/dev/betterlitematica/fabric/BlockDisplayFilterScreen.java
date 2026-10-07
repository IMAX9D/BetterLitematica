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
    private final Set<String> selected=new HashSet<>();
    private BlockDisplayFilter.Mode mode;
    private String query="",error="";
    private String[] terms=new String[0];
    private boolean selectedOnly;
    private BlockFilterGrid grid;
    private OverlayLabel count;
    private ButtonWidget selectResults,clear,save;
    BlockDisplayFilterScreen(Screen parent,ProjectionController controller,UUID id){super("方块显示","",parent,controller,true);this.id=id;epoch=controller.sessionEpoch();var placement=controller.placement(id);var filter=placement==null?BlockDisplayFilter.OFF:placement.displayFilter();mode=filter.mode();selected.addAll(filter.blockIds());registry=Registries.BLOCK.stream().map(block->new BlockFilterGrid.Entry(Registries.BLOCK.getId(block).toString(),block.getName().getString(),new ItemStack(block.asItem()))).sorted(Comparator.comparing(BlockFilterGrid.Entry::id)).toList();}
    @Override protected String backLabel(){return "放弃";}
    @Override protected void runAction(Runnable action){try{error="";action.run();}catch(RuntimeException ex){error=ex.getMessage()==null?"操作失败":ex.getMessage();}}
    @Override protected String displayedStatus(){return error;}
    @Override protected int statusColor(){return UiTheme.ERROR;}
    @Override protected void buildMenu(){var placement=controller.placement(id);if(placement==null){label("投影已移除",0);return;}
        addBody(new OverlayLabel(left,innerWidth,placement.name()),0);
        var modes=BlockDisplayFilter.Mode.values();String[] names={"关闭","黑名单","白名单"};for(int i=0;i<modes.length;i++){var value=modes[i];tabAt(names[i],cellX(i,3),24,cellWidth(3),()->{mode=value;refresh();},mode==value);}
        var search=fieldAt("搜索",query,left,54,innerWidth-134,128);
        buttonAt(selectedOnly?"已选":"全部",left+innerWidth-126,68,126,()->{selectedOnly=!selectedOnly;refresh();refilter(true);},true,false);
        selectResults=buttonAt("全选搜索结果",left,98,128,this::selectResults,true,false);
        clear=ghostAt("清空",left+136,98,64,()->{selected.clear();refilter(false);},!selected.isEmpty());
        count=new OverlayLabel(left+210,innerWidth-210,"");addBody(count,98);
        if(grid==null)grid=new BlockFilterGrid(left,innerWidth,Math.max(40,bodyBottom-bodyTop-126),selected,this::toggle);
        addBody(grid,126);refilter(false);
        search.setChangedListener(text->{query=text;String normalized=text.strip().toLowerCase(Locale.ROOT);terms=normalized.isEmpty()?new String[0]:normalized.split("\\s+");refilter(true);});
        save=fixed("保存",innerWidth-160,76,this::save);
    }
    private boolean matches(BlockFilterGrid.Entry e){if(terms.length==0)return true;String value=e.id()+" "+e.name().toLowerCase(Locale.ROOT);for(String part:terms)if(!value.contains(part))return false;return true;}
    private void refilter(boolean reset){if(grid==null)return;grid.rows(registry.stream().filter(this::matches).filter(e->!selectedOnly||selected.contains(e.id())).toList(),reset);count.setMessage(Text.literal("已选 "+selected.size()));selectResults.active=registry.stream().anyMatch(e->matches(e)&&!selected.contains(e.id()));clear.active=!selected.isEmpty();error="";}
    private void toggle(String blockId){if(!selected.remove(blockId))selected.add(blockId);refilter(false);}
    private void selectResults(){for(var entry:registry)if(matches(entry))selected.add(entry.id());refilter(false);}
    private void save(){if(epoch!=controller.sessionEpoch()||controller.placement(id)==null)throw new IllegalStateException("投影已移除");controller.placementDisplayFilter(id,new BlockDisplayFilter(mode,selected));super.close();}
    @Override protected void updateMenu(){if(controller.placement(id)==null){error="投影已移除";if(grid!=null)grid.active=false;if(save!=null)save.active=false;if(selectResults!=null)selectResults.active=false;if(clear!=null)clear.active=false;}}
    @Override public boolean keyPressed(int key,int scan,int modifiers){if(getFocused()==grid&&grid!=null&&grid.keyPressed(key,scan,modifiers))return true;return super.keyPressed(key,scan,modifiers);}
    @Override public boolean mouseReleased(double x,double y,int button){boolean owned=grid!=null&&grid.mouseReleased(0,0,button);return super.mouseReleased(x,y,button)||owned;}
}
