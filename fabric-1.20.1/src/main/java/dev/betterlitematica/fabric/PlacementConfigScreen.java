package dev.betterlitematica.fabric;

import dev.betterlitematica.core.Placement;
import dev.betterlitematica.core.BlockDisplayFilter;
import dev.betterlitematica.core.RegionPlacement;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.TextFieldWidget;
import java.util.UUID;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

final class PlacementConfigScreen extends MenuScreen {
    private final UUID id;
    private TextFieldWidget name,px,py,pz;
    private float pendingOpacity=Float.NaN;
    private boolean opacityDirty;
    private OverlayOpacitySlider opacitySlider;
    private boolean previewing,previousHudHidden;
    private BlueprintPreviewPanel schematicPreview;
    private CompletableFuture<FilePreviews.Preview> previewPending;
    private String previewSource="";
    private Map<String,RegionPlacement> previewRegions=Map.of();
    private long lastOpacityUpdate=System.nanoTime();
    PlacementConfigScreen(Screen p,ProjectionController c){super("摆放设置","",p,c,true);id=c.selectedId();}
    @Override protected int preferredHeight(){return 380;}
    private TextFieldWidget coordinate(String axis,String value,int x,int y,int width){
        var field=new OverlayTextField(x+40,0,width-46,axis);field.setMaxLength(12);field.setText(value);addBody(field,y);return field;
    }
    private Placement placement(){return controller.placements().stream().filter(v->v.id().equals(id)).findFirst().orElse(null);}
    private void act(Runnable r){controller.select(id);r.run();}
    private void resetCoordinates(){px=py=pz=null;refresh();}
    @Override protected void buildMenu(){
        Placement p=placement();
        if(p==null){cancelPreview();label("此摆放已被移除。",0);return;}
        int previewWidth=196,controlsLeft=left+previewWidth+16,columnWidth=(innerWidth-previewWidth-32)/2,right=controlsLeft+columnWidth+16;
        name=fieldAt("摆放名称",name==null?p.name():name.getText(),left,0,innerWidth-88,120);
        buttonAt("保存名称",left+innerWidth-80,14,80,()->act(()->controller.rename(name.getText())),true,true);
        if(schematicPreview==null)schematicPreview=new BlueprintPreviewPanel(controller,left,previewWidth,196,false);
        schematicPreview.layout(left,previewWidth,196);addBody(schematicPreview,44);syncPreview(p);
        hint(schematicPreview,"左键旋转 · 滚轮缩放 · 中键平移 · 双击复位");

        caption("位置",controlsLeft,42,columnWidth);
        buttonAt("移到玩家位置",controlsLeft,58,columnWidth,()->act(()->{controller.here();resetCoordinates();}),!p.locked(),false);
        px=coordinate("X",px==null?""+p.transform().origin().x():px.getText(),controlsLeft,84,columnWidth);
        py=coordinate("Y",py==null?""+p.transform().origin().y():py.getText(),controlsLeft,110,columnWidth);
        pz=coordinate("Z",pz==null?""+p.transform().origin().z():pz.getText(),controlsLeft,136,columnWidth);
        for(int axis=0;axis<3;axis++){final int a=axis;boolean locked=(p.lockedAxes()&(1<<axis))!=0;buttonAt(new String[]{"X","Y","Z"}[axis]+(locked?" ×":""),controlsLeft,84+axis*26,30,()->act(()->{controller.axisLock(a);switch(a){case 0->px=null;case 1->py=null;case 2->pz=null;}refresh();}),!p.locked(),locked);}
        px.setEditable(!p.locked()&&(p.lockedAxes()&1)==0);py.setEditable(!p.locked()&&(p.lockedAxes()&2)==0);pz.setEditable(!p.locked()&&(p.lockedAxes()&4)==0);
        buttonAt("应用坐标",controlsLeft,172,columnWidth,()->act(()->controller.move(Integer.parseInt(px.getText()),Integer.parseInt(py.getText()),Integer.parseInt(pz.getText()))),!p.locked(),true);

        caption("方向与显示",right,42,columnWidth-48);
        addBody(new ClipboardIconButton(right+columnWidth-44,false,()->controller.action(()->{applyOpacity();client.keyboard.setClipboard(PlacementClipboard.encode(placement(),controller.regions(id)));})),36);
        var paste=new ClipboardIconButton(right+columnWidth-20,true,()->controller.action(()->act(()->{
            controller.pastePlacement(client.keyboard.getClipboard());opacityDirty=false;resetCoordinates();
        })));paste.active=!p.locked();addBody(paste,36);
        buttonAt("旋转 "+p.transform().quarterTurns()*90+"° → +90°",right,58,columnWidth,()->act(()->{controller.rotateNext();refresh();}),!p.locked(),false);
        String mirror=p.transform().mirrorX()?(p.transform().mirrorZ()?"xz":"x"):(p.transform().mirrorZ()?"z":"none");
        buttonAt("镜像："+(mirror.equals("none")?"无":mirror.toUpperCase()),right,84,columnWidth,()->act(()->{controller.mirror(switch(mirror){case "none"->"x";case "x"->"z";case "z"->"xz";default->"none";});refresh();}),!p.locked(),false);
        buttonAt(p.enabled()?"启用：是":"启用：否",right,110,columnWidth,()->act(()->{controller.toggle();refresh();}),true,false);
        buttonAt(p.locked()?"变换：已锁定":"变换：未锁定",right,136,columnWidth,()->act(()->{controller.toggleLock();refresh();}),true,false);
        if(!opacityDirty)pendingOpacity=p.opacity();
        opacitySlider=new OverlayOpacitySlider(right,columnWidth,pendingOpacity,value->{pendingOpacity=(float)value;opacityDirty=true;});
        opacitySlider.previewCallbacks(this::beginPreview,this::endPreview);
        addBody(opacitySlider,168);
        buttonAt(p.renderBlocks()?"渲染：是":"渲染：否",controlsLeft,206,columnWidth,()->act(()->{controller.toggleBlocks();refresh();}),true,false);
        buttonAt("重叠："+switch(p.overlapRule()){case ALL->"替换全部";case NON_AIR->"忽略空气";case NONE->"仅填空白";},right,206,columnWidth,()->act(()->{controller.overlapNext();refresh();}),true,false);
        buttonAt("材料清单",cellX(0,5),246,cellWidth(5),()->act(()->client.setScreen(new AnalysisScreen(this,controller,true))),true,false);
        buttonAt("投影校验",cellX(1,5),246,cellWidth(5),()->act(()->client.setScreen(new AnalysisScreen(this,controller,false))),true,false);
        buttonAt("投影编辑",cellX(2,5),246,cellWidth(5),()->act(()->client.setScreen(new EditingScreen(this,controller))),true,false);
        buttonAt("子区域",cellX(3,5),246,cellWidth(5),()->client.setScreen(new SubregionScreen(this,controller,id)),true,false);
        buttonAt("显示过滤",cellX(4,5),246,cellWidth(5),()->client.setScreen(new BlockDisplayFilterScreen(this,controller,id)),true,p.displayFilter().mode()!=BlockDisplayFilter.Mode.OFF);
        fixed("另存投影",0,80,()->client.setScreen(controller.editor().owns(p.id())?new EditingScreen(this,controller,p.id()):new SchematicFileScreen(this,controller,p.source())));

    }
    private void syncPreview(Placement p){
        if(schematicPreview==null)return;
        schematicPreview.orientation(p.transform());
        if(previewSource.equals(p.source())&&previewRegions.equals(p.regions()))return;
        cancelPreview();previewSource=p.source();previewRegions=p.regions();schematicPreview.selected(p.name());
        try{previewPending=controller.interactivePreview(previewSource,previewRegions);}catch(RuntimeException e){schematicPreview.failed();}
    }
    private void cancelPreview(){
        if(previewPending!=null)previewPending.cancel(true);previewPending=null;previewSource="";previewRegions=Map.of();
        if(schematicPreview!=null)schematicPreview.close();
    }
    private void beginPreview(){
        if(previewing)return;
        previousHudHidden=client.options.hudHidden;previewing=true;client.options.hudHidden=true;
    }
    private void endPreview(){
        if(!previewing)return;
        client.options.hudHidden=previousHudHidden;previewing=false;
    }
    @Override protected net.minecraft.client.gui.widget.ClickableWidget previewControl(){return previewing?opacitySlider:null;}
    @Override public boolean mouseReleased(double x,double y,int button){
        try{return super.mouseReleased(x,y,button);}finally{if(button==0)endPreview();}
    }
    @Override public boolean keyPressed(int key,int scan,int modifiers){
        if(previewing){
            if(key==org.lwjgl.glfw.GLFW.GLFW_KEY_ESCAPE){endPreview();return true;}
            return true;
        }
        return super.keyPressed(key,scan,modifiers);
    }
    private void applyOpacity(){
        if(!opacityDirty)return;
        opacityDirty=false;
        if(placement()!=null)controller.action(()->controller.placementOpacity(id,pendingOpacity));
    }
    @Override protected void updateMenu(){
        if(previewing&&!client.isWindowFocused())endPreview();
        Placement p=placement();
        if(p==null){if(schematicPreview!=null){cancelPreview();schematicPreview=null;refresh();}}
        else{
            syncPreview(p);
            if(previewPending!=null&&previewPending.isDone()){
                var completed=previewPending;previewPending=null;
                try{schematicPreview.images(completed.join());}catch(RuntimeException e){schematicPreview.failed();}
            }
        }
        long now=System.nanoTime();
        if(now-lastOpacityUpdate>=200_000_000L){lastOpacityUpdate=now;applyOpacity();}
    }
    // Preserve the last drag value when closing before the next 200 ms update.
    @Override public void removed(){cancelPreview();schematicPreview=null;endPreview();applyOpacity();super.removed();}
}
