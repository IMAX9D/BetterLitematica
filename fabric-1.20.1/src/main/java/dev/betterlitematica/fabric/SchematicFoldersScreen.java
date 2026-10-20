package dev.betterlitematica.fabric;

import dev.betterlitematica.runtime.SchematicFolders;
import dev.betterlitematica.runtime.UiGlyphArt.Kind;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/** Folder links are instance settings; removing a link never deletes its files. */
final class SchematicFoldersScreen extends MenuScreen {
    private final BlueprintBrowserScreen browser;
    private String confirming="",status="",removingSource="";
    private long confirmingSince;
    private boolean resetConfirmed;
    private CompletableFuture<Void> pending;
    private CompletableFuture<Optional<String>> selecting;
    private CompletableFuture<SchematicFolders.Folder> loading;
    SchematicFoldersScreen(BlueprintBrowserScreen browser,ProjectionController controller){super("投影文件夹","",browser,controller,true);this.browser=browser;}
    @Override protected void buildMenu(){
        int y=4,icon=20;
        for(var folder:controller.schematicFolders()){
            var open=rowAt(folder.path().toString(),left,y,innerWidth-icon-6,browser.inFolder(folder.source()),false,()->{browser.openFolder(folder.source());client.setScreen(browser);});
            open.active=!busy();hint(open,"打开此文件夹 · "+folder.path());
            if(!folder.id().isEmpty()){
                boolean confirmed=confirming.equals(folder.id());
                var remove=iconAt(Kind.TRASH,confirmed?"再次点击移除":"移除文件夹",left+innerWidth-icon,y,icon,()->{
                    if(!confirmed){confirming=folder.id();confirmingSince=System.nanoTime();refresh();return;}
                    removingSource=folder.source();pending=controller.removeFolder(folder.id());refresh();
                },!busy(),confirmed?Look.STANDARD:Look.GHOST,confirmed?UiTheme.ERROR:0);
                hint(remove,(confirmed?"再次点击移除；":"")+"仅取消加载此目录，不删除文件；请先卸载其中的投影");
            }
            y+=26;
        }
        var load=fixedAction("加载文件夹",0,104,this::chooseFolder);load.active=!busy();
        hint(load,"使用系统文件管理器选择投影目录，重启后仍会保留");
        if(!controller.folderProblem().isEmpty()){
            fixed("重试读取",112,76,()->{pending=controller.reloadFolders(false);status="";resetConfirmed=false;refresh();}).active=!busy();
            fixed(resetConfirmed?"确认重置":"重置挂载",196,88,()->{if(!resetConfirmed){resetConfirmed=true;refresh();return;}pending=controller.reloadFolders(true);status="";removingSource="";resetConfirmed=false;refresh();}).active=!busy();
        }
    }
    private boolean busy(){return pending!=null||selecting!=null||loading!=null;}
    private void chooseFolder(){
        String initial=controller.schematicFolders().stream().filter(f->browser.inFolder(f.source())).findFirst().orElse(controller.schematicFolders().get(0)).path().toString();
        selecting=NativeSchematicDialogs.folder(client,initial);confirming="";status="";refresh();
    }
    @Override protected void updateMenu(){
        if(!confirming.isEmpty()&&!busy()&&System.nanoTime()-confirmingSince>3_000_000_000L){confirming="";refresh();}
        if(selecting!=null&&selecting.isDone()){
            try{var path=selecting.join();if(path.isPresent())loading=controller.mountFolder(path.get());}
            catch(RuntimeException e){status=reason(e,"无法打开系统文件夹选择窗口，请重试");}
            selecting=null;refresh();
        }
        if(loading!=null&&loading.isDone()){
            try{var folder=loading.join();loading=null;browser.openFolder(folder.source());client.setScreen(browser);return;}
            catch(RuntimeException e){status=reason(e,"无法加载文件夹，请检查访问权限");}
            loading=null;refresh();
        }
        if(pending!=null&&pending.isDone()){
            try{pending.join();if(removingSource.isEmpty()||browser.inFolder(removingSource))browser.openFolder("");status=removingSource.isEmpty()?"文件夹配置已更新":"已移除文件夹";removingSource="";}
            catch(RuntimeException e){status=reason(e,"移除文件夹失败");}
            pending=null;confirming="";refresh();
        }
    }
    static String reason(Throwable error,String fallback){while(error.getCause()!=null)error=error.getCause();String message=error.getMessage();return message!=null&&message.codePoints().anyMatch(c->c>=0x4e00&&c<=0x9fff)?message:fallback;}
    @Override public void close(){if(!busy())super.close();}
    @Override protected String statusLine(){return selecting!=null?"请选择文件夹…":loading!=null?"正在加载…":pending!=null?"正在保存…":!status.isEmpty()?status:controller.folderProblem();}
}
