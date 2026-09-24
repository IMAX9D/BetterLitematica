package dev.betterlitematica.fabric;
import dev.betterlitematica.runtime.SessionIo;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.TextFieldWidget;
import java.util.*;
import java.util.concurrent.*;
final class BlueprintBrowserScreen extends MenuScreen {
    private final boolean resourceOnly;
    private String directory="",query="",status="正在读取…";private CompletableFuture<SessionIo.Listing> pending;private List<SessionIo.FileEntry> files=List.of();private TextFieldWidget search;private BrowserGrid grid;private boolean managing;
    BlueprintBrowserScreen(Screen p,ProjectionController c){this(p,c,false);}
    BlueprintBrowserScreen(Screen p,ProjectionController c,boolean resourceOnly){super("加载投影","",p,c,true);this.resourceOnly=resourceOnly;read();}
    void fileCreated(String path){read();}
    private void read(){if(pending!=null)pending.cancel(true);files=List.of();status="正在读取…";pending=controller.files(directory);}
    @Override protected void buildMenu(){
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
        search.setChangedListener(v->{query=v;rows();});
        rows();
        fixed(managing?"加载模式":"文件管理",0,80,()->{managing=!managing;refresh();});
        fixed("已加载",176,80,()->client.setScreen(new ResourceScreen(this,controller)));
        fixed("新建目录",88,80,()->client.setScreen(new NewDirectoryScreen(this,controller,directory,()->{read();rows();})));
    }
    private void rows(){
        if(grid==null)return;
        var filtered=files.stream().filter(f->f.name().toLowerCase(Locale.ROOT).contains(query.toLowerCase(Locale.ROOT))).toList();
        grid.entries(filtered,pending!=null?"读取中…":"无匹配文件");
    }
    @Override protected void updateMenu(){if(pending!=null&&pending.isDone()){try{var listing=pending.join();files=listing.entries();status=files.size()+" 项"+(listing.truncated()?" · 列表截断，请细分目录":"");}catch(CompletionException|CancellationException e){status="读取失败："+e.getMessage();}pending=null;rows();}}
    @Override protected String statusLine(){return status;}
    @Override public void close(){if(pending!=null)pending.cancel(true);super.close();}
}
