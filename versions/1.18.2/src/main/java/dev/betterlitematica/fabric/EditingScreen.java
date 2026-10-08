package dev.betterlitematica.fabric;

import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.*;
import java.util.UUID;

final class EditingScreen extends MenuScreen {
    private final UUID target;private boolean bulk,typeOnly;
    private TextFieldWidget block,from,to,output;private ButtonWidget enter,save,discard,replace,sourceMode,extentMode,directionMode,regionMode;private final java.util.Set<Integer> pressed=new java.util.HashSet<>();private HistoryButton undo,redo;
    EditingScreen(Screen parent,ProjectionController controller){this(parent,controller,controller.selectedId());}
    EditingScreen(Screen parent,ProjectionController controller,UUID target){super("投影编辑","",parent,controller,false);this.target=target;controller.editor().open(target);controller.editor().pause();}
    @Override protected void buildMenu(){
        var e=controller.editor();var placement=controller.placement(target);
        addBody(new OverlayLabel(left,innerWidth,placement==null?"投影已移除":placement.name()),0);
        buttonAt("逐格",left,28,cellWidth(2),()->{remember();bulk=false;refresh();},true,!bulk);
        buttonAt("批量",cellX(1,2),28,cellWidth(2),()->{remember();bulk=true;refresh();},true,bulk);
        if(!bulk){
            block=fieldAt("方块",block==null?e.block:block.getText(),left,62,innerWidth,1024);block.setEditable(!e.useHeld);block.active=!e.useHeld;
            sourceMode=buttonAt(e.useHeld?"手持方块":"指定方块",left,110,cellWidth(2),()->{remember();e.useHeld=!e.useHeld;refresh();},!e.busy(),false);
            extentMode=buttonAt(e.straight?"范围：直线":"范围：单格",cellX(1,2),110,cellWidth(2),()->{remember();e.straight=!e.straight;refresh();},!e.busy(),false);
            directionMode=buttonAt("方向："+new String[]{"命中面","+X","−X","+Y","−Y","+Z","−Z"}[e.direction],left,140,innerWidth,()->{remember();e.direction=(e.direction+1)%7;refresh();},e.straight&&!e.busy(),false);
            var regions=controller.regions(target);String region=e.region<0?"自动":regions.get(e.region).name();
            regionMode=buttonAt("子区域："+region,left,170,innerWidth,()->{remember();e.region++;if(e.region>=regions.size())e.region=-1;refresh();},!e.busy(),false);
        }else{
            from=fieldAt("查找",from==null?"minecraft:stone":from.getText(),left,62,innerWidth,1024);
            to=fieldAt("替换",to==null?"minecraft:glass":to.getText(),left,112,innerWidth,1024);
            buttonAt(typeOnly?"匹配：方块类型":"匹配：完整状态",left,164,innerWidth,()->{remember();typeOnly=!typeOnly;refresh();},true,false);
            replace=buttonAt("替换并导出",left,204,innerWidth,()->{remember();controller.editReplace(target,from.getText(),to.getText(),output.getText(),typeOnly);},!e.busy(),true);
        }
        output=fieldAt("新文件名",output==null?e.filename:output.getText(),left,246,innerWidth,100);
        enter=fixed("进入编辑",0,88,()->{var fresh=!e.owns(target);e.open(target);if(fresh)output.setText(e.filename);remember();e.resume(target);});
        undo=fixedControl(new HistoryButton(false,()->controller.action(e::undo),()->controller.options().keys.getOrDefault("editUndo","")),96);
        redo=fixedControl(new HistoryButton(true,()->controller.action(e::redo),()->controller.options().keys.getOrDefault("editRedo","")),124);
        save=fixed("另存",152,60,()->{remember();e.save(output.getText());});
        discard=fixed("放弃",220,60,()->{remember();if(e.dirty())client.setScreen(new DiscardScreen(this,controller));else {e.discard();client.setScreen(parent);}});
        hint(enter,client.options.attackKey.getBoundKeyLocalizedText().getString()+"：删除\n"+client.options.useKey.getBoundKeyLocalizedText().getString()+"：放置\nShift + "+client.options.useKey.getBoundKeyLocalizedText().getString()+"：替换\n"+client.options.pickItemKey.getBoundKeyLocalizedText().getString()+"：取样\nEsc：返回");
        updateMenu();
    }
    private void remember(){var e=controller.editor();if(block!=null)e.block=block.getText();if(output!=null)e.filename=output.getText();}
    @Override protected void updateMenu(){
        var e=controller.editor();var job=controller.editingJob(target);boolean exporting=job!=null&&!job.isDone(),owned=e.owns(target),available=controller.placement(target)!=null;
        if(enter==null)return;if(sourceMode!=null){sourceMode.active=extentMode.active=regionMode.active=!e.busy();directionMode.active=!e.busy()&&e.straight;}enter.active=available&&!e.busy()&&!exporting;enter.setMessage(new net.minecraft.text.LiteralText(owned?"继续编辑":"进入编辑"));undo.active=owned&&e.canUndo();redo.active=owned&&e.canRedo();save.active=owned&&!e.busy()&&!exporting;discard.active=owned&&!e.busy();if(replace!=null)replace.active=available&&!e.busy()&&!exporting;
    }
    private boolean shortcut(int key){
        if(getFocused() instanceof TextFieldWidget)return false;
        for(String action:java.util.stream.Stream.of("editUndo","editRedo").sorted(java.util.Comparator.comparingInt((String value)->controller.options().keys.getOrDefault(value,"").split("\\+").length).reversed()).toList())if(InputBindings.matches(client,controller.options().keys.getOrDefault(action,""),key)){if(pressed.add(key))controller.action(()->{if(action.equals("editRedo"))controller.editor().redo();else controller.editor().undo();});return true;}return false;
    }
    @Override public boolean keyPressed(int key,int scan,int modifiers){return shortcut(key)||super.keyPressed(key,scan,modifiers);}
    @Override public boolean keyReleased(int key,int scan,int modifiers){pressed.remove(key);return super.keyReleased(key,scan,modifiers);}
    @Override public boolean mouseClicked(double x,double y,int button){return !controlHit(x,y)&&shortcut(-button-1)||super.mouseClicked(x,y,button);}
    @Override public boolean mouseReleased(double x,double y,int button){pressed.remove(-button-1);return super.mouseReleased(x,y,button);}
    @Override public void close(){remember();controller.editor().pause();super.close();}
    @Override protected String statusLine(){
        var job=controller.editingJob(target);if(bulk&&job!=null){if(!job.isDone())return "导出中";try{return job.join();}catch(RuntimeException e){return "导出失败："+(e.getCause()==null?e.getMessage():e.getCause().getMessage());}}
        var editor=controller.editor();return !editor.status.isEmpty()?editor.status:editor.dirty()?"未另存":"";
    }
    private static final class DiscardScreen extends MenuScreen {
        DiscardScreen(Screen parent,ProjectionController controller){super("放弃修改？","",parent,controller,false);}
        @Override protected int preferredHeight(){return 120;}
        @Override protected void buildMenu(){fixed("放弃",0,76,()->{controller.editor().discard();client.setScreen(parent instanceof EditingScreen editing?editing.parent:null);});}
        @Override protected String backLabel(){return "取消";}
        @Override protected String statusLine(){return "";}
    }
}
