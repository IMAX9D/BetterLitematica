package dev.betterlitematica.fabric;
import dev.betterlitematica.runtime.UiGlyphArt.Kind;
/** Hub: loaded projections on the left, a launcher of tools on the right. Only the list scrolls. */
final class ProjectionScreen extends MenuScreen {
    private static final int LIST=300,GUTTER=24,ROW=26,TILE=34,TILE_STEP=40;
    private int ticks;
    private String snapshot="";
    private java.util.UUID removing;private long removingSince;
    ProjectionScreen(ProjectionController c){super("BetterLitematica","",null,c,true);}
    @Override protected int preferredHeight(){return Math.max(306,130+ROW*Math.max(Math.min(controller.placements().size(),8),6));}
    @Override protected int scrollRight(){return left+LIST;}
    @Override protected int scrollTop(){return bodyTop+ROW;}
    @Override protected boolean scrolls(int x,int y){return x<scrollRight()&&y>=ROW;}
    @Override protected void buildMenu(){
        int right=left+LIST+GUTTER,rightWidth=innerWidth-LIST-GUTTER;
        var entries=controller.placements();
        caption(entries.isEmpty()?"投影":"投影  ·  "+entries.size(),left,2,LIST);
        caption("工具",right,2,rightWidth);
        if(entries.isEmpty())caption("尚未加载投影",left+2,ROW+12,LIST);
        int icon=20,actions=3*icon+2*2,nameWidth=LIST-actions-6;
        for(int i=0;i<entries.size();i++){
            var entry=entries.get(i);
            boolean selected=entry.id().equals(controller.selectedId()),live=entry.enabled()&&entry.renderBlocks();
            int y=ROW+i*ROW,ax=left+nameWidth+6;
            rowAt(entry.name(),left,y,nameWidth,selected,live,()->{controller.select(selected?null:entry.id());refresh();});
            iconAt(Kind.TUNE,"配置",ax,y,icon,()->{controller.select(entry.id());ClientUi.setScreen(minecraft,new PlacementConfigScreen(this,controller));},true,Look.GHOST,0);
            iconAt(live?Kind.EYE:Kind.EYE_OFF,!entry.enabled()?"启用并显示":live?"隐藏方块":"显示方块",ax+icon+2,y,icon,()->{controller.toggleDisplay(entry.id());refresh();},true,Look.GHOST,live?0:UiTheme.MUTED);
            // Removing loses position, rotation and filters, so it takes a second click on the same, now red, control.
            boolean confirming=entry.id().equals(removing);
            iconAt(Kind.TRASH,confirming?"再次点击移除":"移除投影",ax+2*(icon+2),y,icon,()->{
                if(confirming){removing=null;controller.unload(entry.id());}else{removing=entry.id();removingSince=System.nanoTime();}
                refresh();
            },true,confirming?Look.STANDARD:Look.GHOST,confirming?UiTheme.ERROR:0);
        }
        int tw=(rightWidth-8)/2;
        tile(Kind.LOAD,"加载投影",right,tw,0,0,()->ClientUi.setScreen(minecraft,new BlueprintBrowserScreen(this,controller)),true);
        tile(Kind.PRINTER,"打印机",right,tw,1,0,()->ClientUi.setScreen(minecraft,new PrinterScreen(this,controller)),false);
        tile(Kind.SELECTION,"选区与保存",right,tw,0,1,()->ClientUi.setScreen(minecraft,new SelectionScreen(this,controller)),false);
        tile(Kind.PASTE,"创造粘贴",right,tw,1,1,()->ClientUi.setScreen(minecraft,new CreativeScreen(this,controller)),false);
        tile(Kind.TASKS,"任务管理",right,tw,0,2,()->ClientUi.setScreen(minecraft,new TaskScreen(this,controller)),false);
        tile(Kind.VERSIONS,"项目版本",right,tw,1,2,()->ClientUi.setScreen(minecraft,new ProjectScreen(this,controller)),false);
        tile(Kind.SETTINGS,"设置与快捷键",right,tw,0,3,()->ClientUi.setScreen(minecraft,new OptionsScreen(this,controller)),false);
        if(controller.hasDraftRecovery())tile(Kind.RESTORE,"恢复编辑",right,tw,1,3,()->ClientUi.setScreen(minecraft,new DraftRecoveryScreen(this,controller)),true);
        snapshot=snapshot();
    }
    private void tile(Kind glyph,String label,int x,int w,int column,int row,Runnable action,boolean emphasis){
        tileAt(glyph,label,x+column*(w+8),ROW+row*TILE_STEP,w,TILE,action,emphasis);
    }
    private String snapshot(){
        var state=new StringBuilder().append(controller.selectedId()).append(controller.hasDraftRecovery());
        for(var entry:controller.placements())state.append(entry).append(controller.entryStatus(entry.id()));
        return state.toString();
    }
    @Override protected void updateMenu(){
        if(removing!=null&&System.nanoTime()-removingSince>3_000_000_000L){removing=null;refresh();return;}
        if(++ticks%10==0&&!snapshot.equals(snapshot()))refresh();
    }
}
