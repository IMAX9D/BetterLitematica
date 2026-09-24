package dev.betterlitematica.fabric;
import net.minecraft.client.gui.screen.Screen;
import java.util.concurrent.CompletableFuture;
final class NewDirectoryScreen extends MenuScreen {
    private final String directory;private final Runnable completed;private String name="",status="";private CompletableFuture<String> writing;
    NewDirectoryScreen(Screen parent,ProjectionController controller,String directory,Runnable completed){super("新建目录","",parent,controller,false);this.directory=directory;this.completed=completed;}
    @Override protected int preferredHeight(){return 200;}
    @Override protected void buildMenu(){label("schematics/"+directory,0);var field=fieldAt("名称",name,left,30,innerWidth,80);field.setChangedListener(v->name=v);field.setEditable(writing==null);buttonAt("创建",left,76,innerWidth,()->{writing=controller.createDirectory(directory,name);refresh();},writing==null,true);}
    @Override protected void updateMenu(){if(writing!=null&&writing.isDone()){try{writing.join();writing=null;completed.run();close();return;}catch(RuntimeException e){while(e.getCause() instanceof RuntimeException cause)e=cause;status="创建失败："+e.getMessage();}writing=null;refresh();}}
    @Override protected String statusLine(){return status;}
    @Override public void removed(){if(writing!=null)writing.cancel(true);super.removed();}
}
