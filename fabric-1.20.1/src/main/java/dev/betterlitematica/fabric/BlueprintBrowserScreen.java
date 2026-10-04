package dev.betterlitematica.fabric;
import dev.betterlitematica.runtime.SessionIo;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import java.util.*;
import java.util.concurrent.*;

final class BlueprintBrowserScreen extends MenuScreen {
    private final boolean resourceOnly;
    private String directory="",query="",status="正在读取…",selectedPath="",selectedName="",previewPath="";
    private CompletableFuture<SessionIo.Listing> pending;
    private CompletableFuture<FilePreviews.Preview> previewPending;
    private List<SessionIo.FileEntry> files=List.of();private TextFieldWidget search;private BrowserGrid grid;
    private BlueprintPreviewPanel preview;private ButtonWidget load,manage;private boolean reloadOnInit;private long queryDue;
    BlueprintBrowserScreen(Screen p,ProjectionController c){this(p,c,false);}
    BlueprintBrowserScreen(Screen p,ProjectionController c,boolean resourceOnly){super("加载投影","",p,c,true);this.resourceOnly=resourceOnly;read(false);}
    void fileCreated(String path){if(reloadOnInit)return;read(true);}
    private void read(boolean preserve){cancelRead();if(!preserve){files=List.of();select(null);}status="正在读取…";pending=controller.files(directory,query);rows();}
    private void cancelRead(){if(pending!=null)pending.cancel(true);pending=null;queryDue=0;}
    private void cancelPreview(){if(previewPending!=null)previewPending.cancel(true);previewPending=null;previewPath="";if(preview!=null)preview.close();}
    private void searchChanged(String value){if(query.equals(value))return;query=value;cancelRead();select(null);files=List.of();status="正在读取…";queryDue=System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(200);rows();}
    private void select(SessionIo.FileEntry file){
        String next=file==null?"":file.path();if(next.equals(selectedPath))return;
        cancelPreview();selectedPath=next;selectedName=file==null?"":file.name();
        if(preview!=null)preview.selected(selectedName);if(grid!=null)grid.selected(selectedPath);updateActions();requestPreview();
    }
    private void requestPreview(){
        if(preview==null||selectedPath.isEmpty()||previewPending!=null)return;
        preview.selected(selectedName);previewPath=selectedPath;
        try{previewPending=controller.interactivePreview(selectedPath);}catch(RuntimeException e){preview.failed();}
    }
    private void click(SessionIo.FileEntry file){
        if(file.directory()){directory=file.path();query="";read(false);refresh();}
        else select(file);
    }
    private void loadSelected(){
        if(selectedPath.isEmpty())return;
        if(resourceOnly){controller.loadResource(selectedPath);close();}
        else{controller.load(selectedPath);client.setScreen(new PlacementConfigScreen(this,controller));}
    }
    @Override protected void buildMenu(){
        if(reloadOnInit){reloadOnInit=false;read(true);}
        search=fieldAt("搜索 · schematics/"+directory,query,left,0,innerWidth-154,120);
        buttonAt("上级目录",left+innerWidth-148,14,72,()->{int slash=directory.lastIndexOf('/');directory=slash<0?"":directory.substring(0,slash);query="";read(false);refresh();},!directory.isEmpty(),false);
        buttonAt("刷新",left+innerWidth-70,14,70,()->{cancelPreview();read(true);requestPreview();},true,false);
        int gridWidth=(innerWidth-14)*38/100,bodyHeight=Math.max(30,bodyBottom-bodyTop-46);
        if(grid==null)grid=new BrowserGrid(left,gridWidth,bodyHeight,file->controller.action(()->click(file)));
        addBody(grid,46);
        if(preview==null){preview=new BlueprintPreviewPanel(controller,left+gridWidth+14,innerWidth-gridWidth-14,bodyHeight);preview.selected(selectedName);requestPreview();}
        addBody(preview,46);
        search.setChangedListener(this::searchChanged);rows();
        manage=fixed("文件管理",0,80,()->client.setScreen(new SchematicFileScreen(this,controller,selectedPath)));
        fixed("新建目录",88,80,()->client.setScreen(new NewDirectoryScreen(this,controller,directory,()->{read(true);rows();})));
        fixed("已加载",176,80,()->client.setScreen(new ResourceScreen(this,controller)));
        load=fixed("加载",innerWidth-164,80,this::loadSelected);updateActions();
    }
    private void updateActions(){boolean chosen=!selectedPath.isEmpty();if(load!=null)load.active=chosen;if(manage!=null)manage.active=chosen;}
    private void rows(){if(grid!=null){grid.entries(files,pending!=null||queryDue!=0?"读取中…":"无匹配文件");grid.selected(selectedPath);}}
    @Override protected void updateMenu(){
        if(queryDue!=0&&System.nanoTime()>=queryDue)read(false);
        if(pending!=null&&pending.isDone()){
            try{var listing=pending.join();files=listing.entries();status=files.size()+" 项"+(listing.truncated()?" · 结果超过 "+SessionIo.MAX_LISTING_RESULTS+" 项，请缩小搜索":"");
                if(!selectedPath.isEmpty()&&files.stream().noneMatch(f->!f.directory()&&f.path().equals(selectedPath)))select(null);
            }catch(CompletionException|CancellationException e){status="读取失败";}
            pending=null;rows();
        }
        if(previewPending!=null&&previewPending.isDone()){
            var completed=previewPending;String path=previewPath;previewPending=null;previewPath="";
            try{var images=completed.join();if(preview!=null&&path.equals(selectedPath))preview.images(images);}
            catch(RuntimeException e){if(preview!=null&&path.equals(selectedPath))preview.failed();}
        }
    }
    @Override protected String statusLine(){return status;}
    // Vanilla releases the hovered child, which can differ from the child that owns a drag.
    @Override public boolean mouseReleased(double x,double y,int button){boolean released=preview!=null&&preview.releaseDrag(button);return super.mouseReleased(x,y,button)||released;}
    @Override public void removed(){cancelRead();cancelPreview();preview=null;reloadOnInit=true;super.removed();}
    @Override public void close(){cancelRead();cancelPreview();super.close();}
}
