package dev.betterlitematica.fabric;
import dev.betterlitematica.runtime.SessionIo;
import java.util.*;
import java.util.concurrent.*;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.client.gui.screen.Screen;

final class BlueprintBrowserScreen extends MenuScreen {
    private final boolean resourceOnly;
    private String directory="",query="",status="正在读取…",selectedPath="",selectedName="",previewPath="",displayedPath="";
    private CompletableFuture<SessionIo.Listing> pending;
    private CompletableFuture<FilePreviews.Preview> previewPending;
    private CompletableFuture<Optional<String>> selectingFile;
    private CompletableFuture<String> copyingFile;
    private List<SessionIo.FileEntry> files=List.of();private TextFieldWidget search;private BrowserGrid grid;
    private BlueprintPreviewPanel preview;private ButtonWidget load,manage;private boolean reloadOnInit;private long queryDue;
    BlueprintBrowserScreen(Screen p,ProjectionController c){this(p,c,false);}
    BlueprintBrowserScreen(Screen p,ProjectionController c,boolean resourceOnly){super("加载投影","",p,c,true);this.resourceOnly=resourceOnly;read(false);}
    void openFolder(String path){directory=path;query="";read(false);}
    boolean inFolder(String source){return source.isEmpty()?!dev.betterlitematica.runtime.SchematicFolders.external(directory):directory.equals(source)||directory.startsWith(source+"/");}
    void fileCreated(String path){if(reloadOnInit)return;read(true);}
    private void read(boolean preserve){cancelRead();if(!preserve){files=List.of();select(null);}status="正在读取…";pending=controller.files(directory,query);rows();}
    private void cancelRead(){if(pending!=null)pending.cancel(true);pending=null;queryDue=0;}
    private void cancelPreview(){if(previewPending!=null)previewPending.cancel(true);previewPending=null;previewPath="";displayedPath="";if(preview!=null)preview.close();}
    private void searchChanged(String value){if(query.equals(value))return;query=value;cancelRead();select(null);files=List.of();status="正在读取…";queryDue=System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(200);rows();}
    private void select(SessionIo.FileEntry file){
        String next=file==null?"":file.path();if(next.equals(selectedPath))return;
        selectedPath=next;selectedName=file==null?"":file.name();
        if(grid!=null)grid.selected(selectedPath);updateActions();requestPreview();
    }
    private void requestPreview(){
        showPreview(selectedPath,selectedName);
    }
    private void showPreview(String path,String name){
        if(preview==null||path.equals(displayedPath))return;
        cancelPreview();displayedPath=path;preview.selected(name);
        if(path.isEmpty())return;previewPath=path;
        try{previewPending=controller.interactivePreview(path);}catch(RuntimeException e){preview.failed();}
    }
    private void click(SessionIo.FileEntry file){
        if(file.directory()){directory=file.path();query="";read(false);refresh();}
        else{select(file);loadSelected();}
    }
    private void highlight(SessionIo.FileEntry file){
        if(file.directory()){select(null);if(grid!=null)grid.selected(file.path());}
        else select(file);
    }
    private void loadSelected(){
        if(selectedPath.isEmpty())return;
        if(resourceOnly){controller.loadResource(selectedPath);close();}
        else{controller.load(selectedPath);client.setScreen(new PlacementConfigScreen(this,controller));}
    }
    private boolean importingFile(){return selectingFile!=null||copyingFile!=null;}
    private void chooseFile(){selectingFile=NativeSchematicDialogs.file(client,controller.schematicFolderPath(directory));refresh();}
    @Override protected void buildMenu(){
        if(reloadOnInit){reloadOnInit=false;read(true);}
        var folders=buttonAt("文件夹",left+innerWidth-226,4,72,()->client.setScreen(new SchematicFoldersScreen(this,controller)),!importingFile(),false);
        hint(folders,"选择读取投影的目录 · "+controller.folderLabel(directory));
        search=searchAt("搜索文件名",query,left,4,innerWidth-262,120);search.setEditable(!importingFile());
        var importFile=iconAt(dev.betterlitematica.runtime.UiGlyphArt.Kind.LOAD,"加载文件",left+innerWidth-254,4,20,this::chooseFile,!importingFile(),Look.GHOST,0);
        hint(importFile,"从系统文件管理器加载投影，并复制到本实例投影目录");
        buttonAt("上级目录",left+innerWidth-148,4,72,()->{directory=controller.parentFolder(directory);query="";read(false);refresh();},!importingFile()&&!controller.parentFolder(directory).equals(directory),false);
        buttonAt("刷新",left+innerWidth-70,4,70,()->{cancelPreview();read(true);requestPreview();},!importingFile(),false);
        int gridWidth=(innerWidth-14)*38/100,bodyHeight=Math.max(30,bodyBottom-bodyTop-36);
        if(grid==null)grid=new BrowserGrid(left,gridWidth,bodyHeight,this::highlight,file->controller.action(()->click(file)));
        grid.active=!importingFile();addBody(grid,36);
        if(preview==null){preview=new BlueprintPreviewPanel(controller,left+gridWidth+14,innerWidth-gridWidth-14,bodyHeight);preview.selected(selectedName);requestPreview();}
        preview.layout(left+gridWidth+14,innerWidth-gridWidth-14,bodyHeight);addBody(preview,36);
        hint(preview,"左键旋转 · 滚轮缩放 · 中键平移 · 双击复位");
        search.setChangedListener(this::searchChanged);rows();
        manage=fixed("文件管理",0,80,()->client.setScreen(new SchematicFileScreen(this,controller,selectedPath)));
        fixed("新建目录",88,80,()->client.setScreen(new NewDirectoryScreen(this,controller,directory,()->{read(true);rows();}))).active=!importingFile();
        fixed("已加载",176,80,()->client.setScreen(new ResourceScreen(this,controller))).active=!importingFile();
        load=fixedAction("加载",innerWidth-164,80,this::loadSelected);updateActions();
    }
    private void updateActions(){boolean chosen=!selectedPath.isEmpty()&&!importingFile();if(load!=null)load.active=chosen;if(manage!=null)manage.active=chosen;}
    private void rows(){if(grid!=null){grid.entries(files,pending!=null||queryDue!=0?"读取中…":"无匹配文件");grid.selected(selectedPath);}}
    @Override protected void updateMenu(){
        if(selectingFile!=null&&selectingFile.isDone()){
            try{var path=selectingFile.join();if(path.isPresent())copyingFile=controller.importSchematicFile(path.get());}
            catch(RuntimeException e){status=SchematicFoldersScreen.reason(e,"无法打开系统文件选择窗口，请重试");}
            selectingFile=null;refresh();
        }
        if(copyingFile!=null&&copyingFile.isDone()){
            try{
                String path=copyingFile.join();copyingFile=null;openFolder("");
                select(new SessionIo.FileEntry(path,path,false));loadSelected();return;
            }catch(RuntimeException e){status=SchematicFoldersScreen.reason(e,"无法复制投影文件，请检查读取和写入权限");}
            copyingFile=null;refresh();
        }
        if(queryDue!=0&&System.nanoTime()>=queryDue)read(false);
        if(pending!=null&&pending.isDone()){
            try{var listing=pending.join();files=listing.entries();status=files.size()+" 项"+(listing.truncated()?" · 结果超过 "+SessionIo.MAX_LISTING_RESULTS+" 项，请缩小搜索":"");
                if(!selectedPath.isEmpty()&&files.stream().noneMatch(f->!f.directory()&&f.path().equals(selectedPath)))select(null);
            }catch(CompletionException|CancellationException e){status="文件夹无法读取，请检查挂载路径或连接";}
            pending=null;rows();
        }
        var hovered=grid==null?null:grid.previewHovered();
        showPreview(hovered==null?selectedPath:hovered.path(),hovered==null?selectedName:hovered.name());
        if(previewPending!=null&&previewPending.isDone()){
            var completed=previewPending;String path=previewPath;previewPending=null;previewPath="";
            try{var images=completed.join();if(preview!=null&&path.equals(displayedPath))preview.images(images);}
            catch(RuntimeException e){if(preview!=null&&path.equals(displayedPath))preview.failed();}
        }
    }
    @Override protected String statusLine(){return selectingFile!=null?"请选择投影文件…":copyingFile!=null?"正在复制投影…":status;}
    @Override public boolean keyPressed(net.minecraft.client.input.KeyInput keyInput){int key=keyInput.key(),scan=keyInput.scancode(),modifiers=keyInput.modifiers();
        if(key==org.lwjgl.glfw.GLFW.GLFW_KEY_DOWN&&getFocused()==search&&grid!=null&&!files.isEmpty()){setFocused(grid);return grid.keyPressed(keyInput);}
        return super.keyPressed(keyInput);
    }
    @Override public void removed(){if(selectingFile!=null)selectingFile.cancel(false);if(copyingFile!=null)copyingFile.cancel(true);selectingFile=null;copyingFile=null;cancelRead();cancelPreview();preview=null;reloadOnInit=true;super.removed();}
    @Override public void close(){if(selectingFile!=null)return;cancelRead();cancelPreview();super.close();}
}
