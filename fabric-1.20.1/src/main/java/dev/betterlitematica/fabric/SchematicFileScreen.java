package dev.betterlitematica.fabric;

import dev.betterlitematica.io.SchematicFileInfo;
import net.minecraft.client.gui.screen.Screen;
import java.util.concurrent.*;

final class SchematicFileScreen extends MenuScreen {
    private String source;private String savedSource;private String name="",author="",description="",output="",format="litematic",status="";
    private SchematicFileInfo info;private OverlayPreview image;private int[] pixels;private CompletableFuture<SchematicFileInfo> reading;private CompletableFuture<String> writing;private CompletableFuture<int[]> capture;
    SchematicFileScreen(Screen parent,ProjectionController controller,String source){super("投影文件","",parent,controller,true);this.source=source;reading=controller.fileInfo(source);String base=source.substring(source.lastIndexOf('/')+1);int dot=base.lastIndexOf('.');output=dev.betterlitematica.runtime.TemporarySources.temporary(source)?"selection-"+System.currentTimeMillis():(dot<0?base:base.substring(0,dot))+"-copy";}
    @Override protected void buildMenu(){
        addBody(new OverlayLabel(left,innerWidth,dev.betterlitematica.runtime.TemporarySources.temporary(source)?info==null?"临时投影":info.name():source),0);if(info==null){label(status.isEmpty()?"读取中…":status,2);return;}boolean idle=writing==null&&capture==null;int w=innerWidth-180,right=left+w+20;
        var nameField=fieldAt("名称",name,left,28,w,120);nameField.setChangedListener(v->name=v);nameField.setEditable(idle);
        var authorField=fieldAt("作者",author,left,74,w,120);authorField.setChangedListener(v->author=v);authorField.setEditable(idle);
        var descriptionField=fieldAt("说明",description,left,120,w,2048);descriptionField.setChangedListener(v->description=v);descriptionField.setEditable(idle);
        var file=fieldAt("新文件名",output,left,166,w-88,100);file.setChangedListener(v->output=v);file.setEditable(idle);
        buttonAt("."+format,left+w-80,180,80,()->{format=switch(format){case "litematic"->"schem";case "schem"->"nbt";default->"litematic";};refresh();},idle,false);
        if(image==null){image=new OverlayPreview(right,160);if(pixels!=null)image.pixels(pixels);}addBody(image,28);
        buttonAt("更新预览",right,200,76,()->{capture=controller.preview();refresh();},idle&&capture==null,false);
        buttonAt("清除",right+84,200,76,()->{pixels=new int[0];image.pixels(pixels);},idle,false);
        buttonAt("另存",left,228,100,()->{writing=controller.exportFile(source,output,format,name,author,description,pixels);refresh();},idle,true);
        buttonAt(savedSource==null?"加载":"加载新文件",left+108,228,100,()->{controller.load(savedSource==null?source:savedSource);client.setScreen(new PlacementConfigScreen(this,controller));},idle,false);
        String counts=info.regions()+" 个区域"+(info.blocks()<0?"":" · "+info.blocks()+" 方块");caption(counts,left,268,innerWidth);
    }
    @Override protected void updateMenu(){
        if(reading!=null&&reading.isDone()){try{info=reading.join();name=info.name();author=info.author();description=info.description();pixels=info.preview();}catch(RuntimeException e){status="读取失败："+detail(e);}reading=null;refresh();}
        if(capture!=null&&capture.isDone()){try{pixels=capture.join();image.pixels(pixels);status="";}catch(RuntimeException e){status="获取失败："+detail(e);}capture=null;refresh();}
        if(writing!=null&&writing.isDone()){try{savedSource=writing.join();status="已保存："+savedSource;if(dev.betterlitematica.runtime.TemporarySources.temporary(source)&&format.equals("litematic")){String previous=source;controller.promoteTemporary(source,savedSource);source=savedSource;if(parent instanceof ResourceScreen resources)resources.sourceChanged(previous,source);}else if(dev.betterlitematica.runtime.TemporarySources.temporary(source))status+=" · 当前投影仍为临时";if(parent instanceof BlueprintBrowserScreen browser)browser.fileCreated(savedSource);}catch(RuntimeException e){status="保存失败："+detail(e);}writing=null;refresh();}
    }
    private static String detail(Throwable e){while(e.getCause()!=null)e=e.getCause();return e.getMessage();}
    @Override protected String statusLine(){return writing!=null?"保存中…":!status.isEmpty()?status:format.equals("litematic")?"":"此格式不保留计划刻";}
    @Override public void removed(){if(reading!=null)reading.cancel(true);if(writing!=null)writing.cancel(true);if(capture!=null)capture.cancel(false);if(image!=null){image.close();image=null;}super.removed();}
}
