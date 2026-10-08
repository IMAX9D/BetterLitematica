package dev.betterlitematica.fabric;

import dev.betterlitematica.core.AreaSelection;
import java.util.*;
import java.util.concurrent.*;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;

final class SelectionLibraryScreen extends MenuScreen {
    private String selected="",status="",name="selection",deleting="";private List<String> names=List.of();private EditBox input;private OverlayList list;
    private CompletableFuture<List<String>> reading;private CompletableFuture<String> writing;private CompletableFuture<AreaSelection> loading;
    SelectionLibraryScreen(Screen parent,ProjectionController controller){super("选区库","",parent,controller,true);read();}
    private void read(){if(reading!=null)reading.cancel(true);reading=controller.selectionNames();}
    private boolean busy(){return writing!=null||loading!=null;}
    @Override protected void buildMenu(){
        int w=(innerWidth-20)/2,right=left+w+20;
        if(list==null)list=new OverlayList(left,w,bodyBottom-bodyTop,id->{selected=id;name=id;deleting="";refresh();});list.active=!busy();list.rows(names.stream().map(n->new OverlayList.Row(n,n,true)).toList(),selected);addBody(list,0);
        input=fieldAt("名称",name,right,0,w,100);input.setResponder(v->name=v);input.setEditable(!busy());
        buttonAt("保存当前选区",right,48,w,()->{writing=controller.selectionSave(name);refresh();},!busy()&&!controller.selection().boxes().isEmpty(),true);
        buttonAt("载入",right,78,w,()->{loading=controller.selectionLoad(selected);refresh();},!busy()&&!selected.isEmpty(),false);
        buttonAt("重命名",right,108,w,()->{writing=controller.selectionLibraryRename(selected,name);refresh();},!busy()&&!selected.isEmpty(),false);
        buttonAt(deleting.equals(selected)&&!selected.isEmpty()?"确认删除":"删除",right,138,w,()->{if(deleting.equals(selected)){writing=controller.selectionDelete(selected);deleting="";}else deleting=selected;refresh();},!busy()&&!selected.isEmpty(),false);
    }
    @Override protected void updateMenu(){
        if(reading!=null&&reading.isDone()){try{names=reading.join();if(!names.contains(selected))selected="";status="";}catch(CompletionException|CancellationException e){status="读取失败："+detail(e);}reading=null;refresh();}
        if(writing!=null&&writing.isDone()){try{selected=writing.join();status="";read();}catch(CompletionException|CancellationException e){status="保存失败："+detail(e);}writing=null;refresh();}
        if(loading!=null&&loading.isDone()){try{controller.selectionApply(loading.join());loading=null;onClose();return;}catch(RuntimeException e){status="载入失败："+detail(e);}loading=null;refresh();}
    }
    private static String detail(Throwable e){while(e.getCause()!=null)e=e.getCause();return e.getMessage();}
    @Override protected String statusLine(){return busy()?"处理中…":status;}
    @Override public void removed(){if(reading!=null)reading.cancel(true);if(loading!=null)loading.cancel(true);if(writing!=null)writing.cancel(true);super.removed();}
}
