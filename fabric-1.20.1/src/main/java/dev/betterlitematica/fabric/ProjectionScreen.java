package dev.betterlitematica.fabric;

final class ProjectionScreen extends MenuScreen {
    private int ticks;
    private String snapshot="";

    ProjectionScreen(ProjectionController c){super("BetterLitematica","",null,c,true);}
    @Override protected int preferredHeight(){return Math.max(246,114+26*Math.max(Math.min(controller.placements().size(),8),controller.hasDraftRecovery()?6:5));}

    @Override protected int scrollRight(){return left+304;}
    @Override protected int scrollTop(){return bodyTop+26;}
    @Override protected boolean scrolls(int x,int y){return x<scrollRight()&&y>=26;}

    @Override protected void buildMenu(){
        int listWidth=304,right=left+listWidth+20,rightWidth=innerWidth-listWidth-20;
        var entries=controller.placements();
        caption("当前投影 · "+entries.size(),left,0,listWidth);
        caption("功能与工具",right,0,rightWidth);
        if(entries.isEmpty()){
            caption("尚未加载投影",left,40,listWidth);

        }
        for(int i=0;i<entries.size();i++){
            var entry=entries.get(i);
            boolean selected=entry.id().equals(controller.selectedId());
            int y=26+i*26;
            buttonAt(entry.name(),left,y,listWidth-146,()->{
                controller.select(selected?null:entry.id());refresh();
            },true,selected);
            buttonAt("配置",left+listWidth-142,y,40,()->{controller.select(entry.id());client.setScreen(new PlacementConfigScreen(this,controller));},true,false);
            buttonAt(!entry.enabled()?"已停用":entry.renderBlocks()?"显示：是":"显示：否",left+listWidth-98,y,54,()->{controller.toggleDisplay(entry.id());refresh();},true,entry.enabled()&&entry.renderBlocks());
            buttonAt("删除",left+listWidth-40,y,40,()->{controller.unload(entry.id());refresh();},true,false);
        }
        option("加载投影",right,rightWidth,0,0,()->client.setScreen(new BlueprintBrowserScreen(this,controller)),true);
        option("打印机",right,rightWidth,1,0,()->client.setScreen(new PrinterScreen(this,controller)),true);
        option("选区与保存",right,rightWidth,0,1,()->client.setScreen(new SelectionScreen(this,controller)),false);
        option("创造粘贴",right,rightWidth,1,1,()->client.setScreen(new CreativeScreen(this,controller)),false);
        option("任务管理",right,rightWidth,0,2,()->client.setScreen(new TaskScreen(this,controller)),false);
        option("项目版本",right,rightWidth,1,2,()->client.setScreen(new ProjectScreen(this,controller)),false);
        option("设置与快捷键",right,rightWidth,0,3,()->client.setScreen(new OptionsScreen(this,controller)),false);

        if(controller.hasDraftRecovery())option("恢复编辑",right,rightWidth,1,3,()->client.setScreen(new DraftRecoveryScreen(this,controller)),true);
        snapshot=snapshot();
    }

    private net.minecraft.client.gui.widget.ButtonWidget option(String text,int x,int width,int column,int row,Runnable action,boolean primary){
        int w=(width-8)/2;
        return buttonAt(text,x+column*(w+8),26+row*26,w,action,true,primary);
    }

    private String snapshot(){
        var state=new StringBuilder().append(controller.selectedId()).append(controller.hasDraftRecovery());
        for(var entry:controller.placements())state.append(entry).append(controller.entryStatus(entry.id()));
        return state.toString();
    }

    @Override protected void updateMenu(){if(++ticks%10==0&&!snapshot.equals(snapshot()))refresh();}
}
