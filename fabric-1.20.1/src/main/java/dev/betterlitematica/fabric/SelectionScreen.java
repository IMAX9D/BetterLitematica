package dev.betterlitematica.fabric;

import dev.betterlitematica.core.*;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.TextFieldWidget;

final class SelectionScreen extends MenuScreen {
    private TextFieldWidget name,output;private final TextFieldWidget[][] coordinates=new TextFieldWidget[3][3];private OverlayList list;private String[][] draft;
    private String selected="",fileName="selection";private AreaSelection seen;
    SelectionScreen(Screen parent,ProjectionController controller){super("选区与保存","",parent,controller,true);}
    private void changed(){selected=controller.selection().selected();name=null;draft=null;seen=controller.selection();refresh();}
    @Override protected void buildMenu(){
        var selection=controller.selection();if(seen!=null&&!seen.equals(selection)){draft=null;name=null;}boolean has=!selection.selected().isEmpty();selected=selection.selected();seen=selection;
        int listWidth=176,right=left+listWidth+16,w=innerWidth-listWidth-16;
        String value=name==null?(has?selected:"region"):name.getText();name=fieldAt("区域名称",value,left,0,innerWidth-234,120);
        buttonAt("新增",left+innerWidth-226,14,66,()->{controller.selectionAdd(name.getText());changed();},true,true);
        buttonAt("重命名",left+innerWidth-152,14,72,()->{controller.selectionRename(name.getText());selected=controller.selection().selected();seen=controller.selection();name=null;refresh();},has,false);
        buttonAt("选区库",left+innerWidth-72,14,72,()->{if(has){controller.selectionCoordinates(read(0),read(1),read(2));seen=controller.selection();}client.setScreen(new SelectionLibraryScreen(this,controller));},true,false);
        if(list==null)list=new OverlayList(left,listWidth,156,id->{if(controller.action(()->{if(!controller.selection().selected().isEmpty())controller.selectionCoordinates(read(0),read(1),read(2));controller.selectionSelect(id);})!=0)changed();else list.rows(controller.selection().boxes().stream().map(b->new OverlayList.Row(b.name(),b.name(),true)).toList(),controller.selection().selected());});
        list.rows(selection.boxes().stream().map(b->new OverlayList.Row(b.name(),b.name(),true)).toList(),selected);addBody(list,50);
        buttonAt(selection.simple()?"简单模式":"多区域",left,214,84,()->{controller.selectionMode();seen=controller.selection();refresh();},true,false);
        buttonAt("移除",left+92,214,84,()->{controller.selectionRemove();changed();},has,false);
        if(draft==null)draft=new String[3][3];var current=has?selection.current():new SelectionBox("region",Vec3i.ZERO,Vec3i.ZERO);Vec3i[] positions={current.first(),current.second(),selection.origin()};
        for(int row=0;row<3;row++){
            int r=row,y=48+row*54;caption(new String[]{"角点 1","角点 2","原点"}[row],right,y,w-126);
            buttonAt("玩家位置",right+w-124,y-3,76,()->{var pos=client.player.getBlockPos();write(r,new Vec3i(pos.getX(),pos.getY(),pos.getZ()));},has,false);
            var copy=new ClipboardIconButton(right+w-44,false,()->controller.action(()->client.keyboard.setClipboard(SelectionCoordinates.encode(read(r)))));copy.active=has;addBody(copy,y-3);
            var paste=new ClipboardIconButton(right+w-20,true,()->controller.action(()->write(r,SelectionCoordinates.decode(client.keyboard.getClipboard()))));paste.active=has;addBody(paste,y-3);
            int cw=(w-16)/3;int[] values={positions[row].x(),positions[row].y(),positions[row].z()};
            for(int axis=0;axis<3;axis++){int a=axis;int x=right+axis*(cw+8);caption(new String[]{"X","Y","Z"}[axis],x,y+26,12);var field=new OverlayTextField(x+18,0,cw-24,"坐标");field.setMaxLength(12);field.setText(draft[row][axis]==null?Integer.toString(values[axis]):draft[row][axis]);field.setChangedListener(v->draft[r][a]=v);field.setEditable(has);coordinates[row][axis]=field;addBody(field,y+24);}
        }
        buttonAt("应用坐标",right,214,w,()->{controller.selectionCoordinates(read(0),read(1),read(2));draft=null;refresh();},has,true);
        output=fieldAt("文件名",fileName,left,244,innerWidth-262,100);output.setChangedListener(v->fileName=v);
        buttonAt(controller.options().capturePreviews?"附带预览：开":"附带预览：关",left+innerWidth-254,258,92,()->{controller.options().capturePreviews=!controller.options().capturePreviews;controller.saveOptions();refresh();},true,controller.options().capturePreviews);
        buttonAt("保存投影",left+innerWidth-154,258,76,()->{controller.selectionCoordinates(read(0),read(1),read(2));seen=controller.selection();controller.capture(fileName);draft=null;refresh();},has,true);
        buttonAt("取消捕获",left+innerWidth-70,258,70,controller::cancelCapture,true,false);
        fixed("创建投影",0,80,()->{controller.selectionCoordinates(read(0),read(1),read(2));seen=controller.selection();controller.captureTemporary();draft=null;refresh();}).active=has;
    }
    private Vec3i read(int row){
        int[] values=new int[3];
        for(int axis=0;axis<3;axis++)try{values[axis]=Integer.parseInt(coordinates[row][axis].getText().strip());}
        catch(NumberFormatException e){focusControl(coordinates[row][axis]);throw new IllegalArgumentException(new String[]{"角点 1","角点 2","原点"}[row]+" · "+new String[]{"X","Y","Z"}[axis]+" 坐标须为整数");}
        return new Vec3i(values[0],values[1],values[2]);
    }
    private void write(int row,Vec3i value){coordinates[row][0].setText(Integer.toString(value.x()));coordinates[row][1].setText(Integer.toString(value.y()));coordinates[row][2].setText(Integer.toString(value.z()));}
    @Override protected void updateMenu(){if(!controller.selection().equals(seen))changed();}
    @Override protected String statusLine(){return controller.captureStatus().equals("没有捕获任务")?"":controller.captureStatus();}
}
