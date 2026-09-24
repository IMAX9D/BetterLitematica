package dev.betterlitematica.fabric;
import dev.betterlitematica.runtime.SessionIo;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.TextFieldWidget;
import java.util.*;
import java.util.concurrent.*;
final class BlueprintBrowserScreen extends MenuScreen {
    private final boolean resourceOnly;
    private String directory="",query="",status="正在读取…";private CompletableFuture<SessionIo.Listing> pending;private List<SessionIo.FileEntry> files=List.of();private TextFieldWidget search;private BrowserGrid grid;private boolean managing,reloadOnInit;private long queryDue;
    BlueprintBrowserScreen(Screen p,ProjectionController c){this(p,c,false);}
    BlueprintBrowserScreen(Screen p,ProjectionController c,boolean resourceOnly){super("加载投影","",p,c,true);this.resourceOnly=resourceOnly;read();}
    void fileCreated(String path){if(reloadOnInit)return;read();}
    private void read(){cancelRead();files=List.of();status="正在读取…";pending=controller.files(directory,query);rows();}
    private void cancelRead(){if(pending!=null)pending.cancel(true);pending=null;queryDue=0;}
    private void searchChanged(String value){if(query.equals(value))return;query=value;cancelRead();files=List.of();status="正在读取…";queryDue=System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(200);rows();}
    @Override protected void buildMenu(){
        if(reloadOnInit){reloadOnInit=false;read();}
        search=fieldAt("搜索 · schematics/"+directory,query,left,0,innerWidth-154,120);
        buttonAt("上级目录",left+innerWidth-148,14,72,()->{int slash=directory.lastIndexOf('/');directory=slash<0?"":directory.substring(0,slash);query="";read();refresh();},!directory.isEmpty(),false);
        buttonAt("刷新",left+innerWidth-70,14,70,()->{read();rows();},true,false);
        if(grid==null)grid=new BrowserGrid(left,innerWidth,Math.max(30,bodyBottom-bodyTop-46),file->controller.action(()->{
            if(file.directory()){directory=file.path();query="";read();refresh();}
            else if(managing)client.setScreen(new SchematicFileScreen(this,controller,file.path()));
            else if(resourceOnly){controller.loadResource(file.path());close();}
            else{controller.load(file.path());client.setScreen(new PlacementConfigScreen(this,controller));}
        }));
        addBody(grid,46);
        search.setChangedListener(this::searchChanged);
        rows();
        fixed(managing?"加载模式":"文件管理",0,80,()->{managing=!managing;refresh();});
        fixed("已加载",176,80,()->client.setScreen(new ResourceScreen(this,controller)));
        fixed("新建目录",88,80,()->client.setScreen(new NewDirectoryScreen(this,controller,directory,()->{read();rows();})));
    }
    private void rows(){
        if(grid==null)return;
        grid.entries(files,pending!=null||queryDue!=0?"读取中…":"无匹配文件");
    }
    @Override protected void updateMenu(){if(queryDue!=0&&System.nanoTime()>=queryDue)read();if(pending!=null&&pending.isDone()){try{var listing=pending.join();files=listing.entries();status=files.size()+" 项"+(listing.truncated()?" · 结果超过 "+SessionIo.MAX_LISTING_RESULTS+" 项，请缩小搜索":"");}catch(CompletionException|CancellationException e){status="读取失败："+e.getMessage();}pending=null;rows();}}
    @Override protected String statusLine(){return status;}
    @Override public void removed(){cancelRead();reloadOnInit=true;super.removed();}
    @Override public void close(){cancelRead();super.close();}
}
