package dev.betterlitematica.fabric;
import dev.betterlitematica.core.ReplaceRule;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.*;
import net.minecraft.text.Text;
import java.util.UUID;

final class CreativeScreen extends MenuScreen {
    private final UUID target;private UUID job;
    private ReplaceRule rule=ReplaceRule.NONE;private boolean entities=true,nbt=true,export;
    private String name="projection-"+System.currentTimeMillis(),count,interval,volume;private boolean merge;
    private ButtonWidget start,pause,cancel;private boolean seenActive;
    CreativeScreen(Screen parent,ProjectionController controller){super("创造粘贴","",parent,controller,false);target=controller.selectedId();var existing=controller.creativeJob(target);if(existing!=null)job=existing.id();var s=controller.options().commands;if(existing!=null&&controller.creativeActive(job)){export=existing.export();rule=existing.rule();entities=existing.entities();nbt=existing.nbt();if(existing.output()!=null)name=existing.output();s=existing.settings();}count=Integer.toString(s.perTick);interval=Integer.toString(s.interval);volume=Integer.toString(s.fillVolume);merge=s.merge;}
    @Override protected void buildMenu(){
        boolean busy=controller.creativeActive(job);seenActive=busy;var placement=controller.placement(target);addBody(new OverlayLabel(left,innerWidth,placement==null?"投影已移除":placement.name()),0);
        buttonAt("粘贴",cellX(0,2),24,cellWidth(2),()->{export=false;refresh();},!busy,!export);
        buttonAt("导出命令",cellX(1,2),24,cellWidth(2),()->{export=true;refresh();},!busy,export);
        buttonAt("实体："+(entities?"包含":"忽略"),cellX(0,2),56,cellWidth(2),()->{entities=!entities;refresh();},!busy,entities);
        buttonAt("方块数据："+(nbt?"包含":"忽略"),cellX(1,2),56,cellWidth(2),()->{nbt=!nbt;refresh();},!busy,nbt);
        buttonAt(switch(rule){case NONE->"仅填充空气";case NON_AIR->"忽略投影空气";case ALL->"替换全部";},left,84,innerWidth,()->{rule=ReplaceRule.values()[(rule.ordinal()+1)%3];refresh();},!busy,false);
        if(export){var field=fieldAt("文件名",name,left,118,innerWidth,100);field.setChangedListener(v->name=v);field.setEditable(!busy);}
        if(export||client.getServer()==null){
            int y=export?168:118;
            if(!export){var field=fieldAt("每批命令",count,cellX(0,2),y,cellWidth(2),3);field.setChangedListener(v->count=v);field.setEditable(!busy);var delay=fieldAt("间隔 / tick",interval,cellX(1,2),y,cellWidth(2),3);delay.setChangedListener(v->interval=v);delay.setEditable(!busy);y+=52;}
            final int row=y;buttonAt("合并填充："+(merge?"开":"关"),cellX(0,2),row+14,cellWidth(2),()->{merge=!merge;refresh();},!busy,merge);var fill=fieldAt("合并格数上限",volume,cellX(1,2),row,cellWidth(2),5);fill.setChangedListener(v->volume=v);fill.setEditable(!busy);
        }
        start=fixed(export?"导出":"开始",0,64,this::start);
        pause=fixed("暂停",72,64,()->controller.pauseCreative(job));
        cancel=fixed("取消",144,64,()->controller.cancelCreative(job));
        updateMenu();
    }
    private void start(){if(export||client.getServer()==null){var settings=controller.options().commands.copy();if(!export){settings.perTick=Integer.parseInt(count);settings.interval=Integer.parseInt(interval);}if(merge)settings.fillVolume=Integer.parseInt(volume);settings.merge=merge;settings.validate();controller.options().commands=settings;controller.saveOptions();}job=export?controller.commands(target,name,rule,nbt,entities):controller.paste(target,rule,entities,nbt);refresh();}
    @Override protected void updateMenu(){if(start==null)return;boolean active=controller.creativeActive(job);if(active!=seenActive){refresh();return;}start.active=controller.placement(target)!=null&&!controller.worldWriteBusy()&&(export||client.getServer()!=null||controller.canSendCommands());pause.active=active;pause.setMessage(Text.literal(controller.creativePaused(job)?"继续":"暂停"));cancel.active=active;}
    @Override protected String statusLine(){String status=controller.creativeStatus(job);if(!status.isEmpty())return status;return !export&&client.getServer()==null&&!controller.canSendCommands()?"需要创造模式与服务器命令权限":"";}
}
