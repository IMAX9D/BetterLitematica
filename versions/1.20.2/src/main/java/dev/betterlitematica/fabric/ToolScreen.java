package dev.betterlitematica.fabric;

import dev.betterlitematica.core.BlockStateSpec;
import dev.betterlitematica.core.ReplaceRule;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;
import java.util.*;

/** Tool preferences and an explicit execution action, separate from printer modes. */
final class ToolScreen extends MenuScreen {
    private String item,primary,secondary,distance,error="";
    private boolean executeRequiresTool,expand,deleteEntities,deletePlacement,pasteEntities,pasteNbt;
    private ReplaceRule pasteRule;
    private OverlayList modes;private OverlayLabel targetLabel;
    private TextFieldWidget itemField,primaryField,secondaryField,distanceField;
    private ButtonWidget execute,pause,cancel;
    private boolean creativeModes;private ToolMode shownMode;private long appliedUntil;
    ToolScreen(Screen parent,ProjectionController controller){
        super("工具","",parent,controller,true);var settings=controller.tool().settings();item=controller.options().toolItem;primary=settings.primary;secondary=settings.secondary;distance=Integer.toString(settings.distance);
        executeRequiresTool=settings.executeRequiresTool;expand=settings.expandSelection;deleteEntities=settings.deleteEntities;deletePlacement=settings.deletePlacement;pasteEntities=settings.pasteEntities;pasteNbt=settings.pasteNbt;pasteRule=settings.pasteRule;
    }
    @Override protected void buildMenu(){
        var mode=controller.tool().mode();shownMode=mode;creativeModes=client.player!=null&&client.player.isCreative();int lw=144,right=left+lw+18,rw=innerWidth-lw-18;
        if(modes==null)modes=new OverlayList(left,lw,bodyBottom-bodyTop,name->{runAction(()->{controller.tool().mode(ToolMode.valueOf(name));error="";});refresh();});
        modes.rows(ToolMode.selectable(creativeModes).stream().map(m->new OverlayList.Row(m.name(),m.label(),m==mode)).toList(),mode.name());addBody(modes,0);
        targetLabel=new OverlayLabel(right,rw,targetName());addBody(targetLabel,0);
        itemField=fieldAt("工具物品",item,right,26,rw,4096);itemField.setChangedListener(v->{item=v;edited();});hint(itemField,"物品 ID，可附加 NBT；留空使用空手");
        int half=(rw-8)/2;
        buttonAt(expand?"选点：扩展":"选点：角点",right,88,half,()->{expand=!expand;error="";refresh();},mode.selection(),expand);
        distanceField=fieldAt("距离",distance,right+half+8,74,half,3);distanceField.setChangedListener(v->{distance=v;edited();});
        // Keep both raw state fields reachable so an invalid draft can always be corrected before saving.
        primaryField=fieldAt(mode==ToolMode.REPLACE?"替换为":"主方块",primary,right,124,half,512);primaryField.setChangedListener(v->{primary=v;edited();});
        secondaryField=fieldAt(mode==ToolMode.REPLACE?"匹配方块":"副方块",secondary,right+half+8,124,half,512);secondaryField.setChangedListener(v->{secondary=v;edited();});
        hint(primaryField,"方块 ID 与状态，例如 minecraft:oak_log[axis=x]");hint(secondaryField,"替换时匹配此方块状态");
        switch(mode){
            case SELECTION->{
                buttonAt("克隆选区",right,178,half,()->{save();controller.tool().cloneSelection();},hasSelection(),false);
                buttonAt("重置原点",right+half+8,178,half,()->controller.tool().resetSelectionOrigin(),hasSelection(),false);
            }
            case DELETE->{
                buttonAt(deletePlacement?"范围：投影":"范围：选区",right,178,half,()->{deletePlacement=!deletePlacement;error="";refresh();},true,deletePlacement);
                buttonAt(deleteEntities?"实体：删除":"实体：保留",right+half+8,178,half,()->{deleteEntities=!deleteEntities;error="";refresh();},true,deleteEntities);
            }
            case PASTE->{
                buttonAt(pasteEntities?"实体：包含":"实体：忽略",right,178,half,()->{pasteEntities=!pasteEntities;error="";refresh();},true,pasteEntities);
                buttonAt(pasteNbt?"方块数据：包含":"方块数据：忽略",right+half+8,178,half,()->{pasteNbt=!pasteNbt;error="";refresh();},true,pasteNbt);
                buttonAt(ruleLabel(pasteRule),right,208,rw,()->{pasteRule=ReplaceRule.values()[(pasteRule.ordinal()+1)%ReplaceRule.values().length];error="";refresh();},true,false);
            }
            case MOVE->buttonAt(pasteEntities?"实体：包含":"实体：忽略",right,178,half,()->{pasteEntities=!pasteEntities;error="";refresh();},true,pasteEntities);
            default->{}
        }
        buttonAt(executeRequiresTool?"执行需持工具：开":"执行需持工具：关",right,242,rw,()->{executeRequiresTool=!executeRequiresTool;refresh();},true,false);
        fixed("保存",0,64,()->{save();appliedUntil=System.nanoTime()+2_500_000_000L;});execute=fixed("执行",72,64,()->{save();controller.tool().execute();});
        pause=cancel=null;
        if(mode==ToolMode.PASTE){pause=fixed("暂停",144,64,()->controller.tool().pausePaste());cancel=fixed("取消",216,64,()->controller.tool().cancelPaste());}
        updateMenu();
    }
    private static String ruleLabel(ReplaceRule rule){return switch(rule){case NONE->"仅填充空气";case NON_AIR->"忽略投影空气";case ALL->"替换全部";};}
    private boolean hasSelection(){return !controller.selection().boxes().isEmpty();}
    private boolean usesSelection(){var mode=controller.tool().mode();return mode.selection()&&!(mode==ToolMode.DELETE&&deletePlacement);}
    private String targetName(){if(usesSelection()){var value=controller.selection();return value.selected().isEmpty()?"未选择选区":value.selected();}var placement=controller.placement(controller.selectedId());if(placement==null)return "未选择投影";if(controller.tool().mode()==ToolMode.DELETE)return placement.name();String toolTarget=controller.tool().targetName();return toolTarget.isEmpty()?placement.name():toolTarget;}
    private void save(){
        String nextItem=item.strip(),nextPrimary=primary.strip(),nextSecondary=secondary.strip();int nextDistance;
        try{ToolItemSpec.validate(nextItem);}catch(RuntimeException failure){focus(itemField);throw new IllegalArgumentException("工具物品："+failure.getMessage(),failure);}
        try{nextDistance=Integer.parseInt(distance);if(nextDistance<1||nextDistance>200)throw new IllegalArgumentException("1–200");}catch(RuntimeException failure){focus(distanceField);throw new IllegalArgumentException("距离：1–200",failure);}
        try{StateResolver1201.checked(BlockStateSpec.parse(nextPrimary));}catch(RuntimeException failure){focus(primaryField);throw new IllegalArgumentException("主方块："+failure.getMessage(),failure);}
        try{StateResolver1201.checked(BlockStateSpec.parse(nextSecondary));}catch(RuntimeException failure){focus(secondaryField);throw new IllegalArgumentException("副方块："+failure.getMessage(),failure);}
        var settings=controller.tool().settings();settings.executeRequiresTool=executeRequiresTool;settings.primary=nextPrimary;settings.secondary=nextSecondary;settings.distance=nextDistance;settings.expandSelection=expand;settings.deleteEntities=deleteEntities;settings.deletePlacement=deletePlacement;settings.pasteEntities=pasteEntities;settings.pasteNbt=pasteNbt;settings.pasteRule=pasteRule;controller.options().toolItem=nextItem;
        controller.saveOptions();error="";
    }
    private void focus(TextFieldWidget field){if(field!=null){focusControl(field);field.setCursorToEnd(false);}}
    private void edited(){error="";appliedUntil=0;}
    @Override protected void runAction(Runnable action){try{appliedUntil=0;action.run();error="";}catch(RuntimeException failure){error=Objects.toString(failure.getMessage(),"操作失败");}finally{updateMenu();}}
    @Override protected String displayedStatus(){return error.isEmpty()?statusLine():error;}
    @Override protected int statusColor(){return error.isEmpty()?UiTheme.MUTED:UiTheme.ERROR;}
    @Override protected String statusLine(){var mode=controller.tool().mode();if(mode.creativeOnly()&&(client==null||client.player==null||!client.player.isCreative()))return "需要创造模式";return System.nanoTime()<appliedUntil?"设置已应用":controller.tool().status();}
    @Override protected void updateMenu(){if(execute==null)return;var mode=controller.tool().mode();boolean creative=client.player!=null&&client.player.isCreative();if(shownMode!=mode||creativeModes!=creative){refresh();return;}boolean executable=mode!=ToolMode.SELECTION&&mode!=ToolMode.PLACEMENT;boolean permitted=!mode.creativeOnly()||creative;execute.active=executable&&permitted&&!controller.worldWriteBusy()&&(usesSelection()?hasSelection():controller.placement(controller.selectedId())!=null);if(targetLabel!=null)targetLabel.setMessage(Text.literal(targetName()));if(pause!=null){pause.active=controller.tool().pasteActive();pause.setMessage(Text.literal(controller.tool().pastePaused()?"继续":"暂停"));cancel.active=pause.active;}}
}
