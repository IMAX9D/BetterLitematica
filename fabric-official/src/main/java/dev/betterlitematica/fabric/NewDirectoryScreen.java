package dev.betterlitematica.fabric;
import java.util.concurrent.CompletableFuture;
import net.minecraft.client.gui.screens.Screen;
final class NewDirectoryScreen extends MenuScreen {
    private final String directory;private final Runnable completed;private String name="",status="";private CompletableFuture<String> writing;
    private net.minecraft.client.gui.components.EditBox field;
    private net.minecraft.client.gui.components.Button create;
    NewDirectoryScreen(Screen parent,ProjectionController controller,String directory,Runnable completed){super("新建目录","",parent,controller,false);this.directory=directory;this.completed=completed;}
    @Override protected int preferredHeight(){return 200;}
    @Override protected void buildMenu(){label("schematics/"+directory,0);field=fieldAt("名称",name,left,30,innerWidth,80);field.setEditable(writing==null);create=buttonAt("创建",left,76,innerWidth,this::create,writing==null&&!name.isBlank(),true);field.setResponder(v->{name=v;status="";create.active=writing==null&&!v.isBlank();});if(writing==null)setFocused(field);}
    private void create(){if(writing!=null||name.isBlank())return;writing=controller.createDirectory(directory,name.strip());status="";refresh();}
    @Override protected void updateMenu(){if(writing!=null&&writing.isDone()){try{writing.join();writing=null;completed.run();onClose();return;}catch(RuntimeException e){while(e.getCause() instanceof RuntimeException cause)e=cause;status="创建失败："+e.getMessage();}writing=null;refresh();}}
    @Override protected String statusLine(){return writing!=null?"创建中…":status;}
    @Override public boolean keyPressed(int key,int scan,int modifiers){if((key==dev.betterlitematica.fabric.NativeInput.GLFW_KEY_ENTER||key==dev.betterlitematica.fabric.NativeInput.GLFW_KEY_KP_ENTER)&&getFocused()==field){runAction(this::create);return true;}return super.keyPressed(key,scan,modifiers);}
    @Override public void removed(){if(writing!=null)writing.cancel(true);super.removed();}
}
