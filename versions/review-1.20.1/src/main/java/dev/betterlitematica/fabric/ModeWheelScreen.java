package dev.betterlitematica.fabric;

import dev.betterlitematica.core.UiViewport;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.widget.*;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.narration.NarrationMessageBuilder;
import net.minecraft.client.gui.screen.narration.NarrationPart;
import net.minecraft.text.Text;
import org.lwjgl.glfw.GLFW;

/** Held overlay with independent work modes and a separate render-layer wheel. */
final class ModeWheelScreen extends MenuScreen {
    static final double CX=300,CY=200,INNER=54,OUTER=132;

    private static final int CARD_X=452,CARD_RIGHT=590,CARD_TOP=130;
    private static final WheelModes[] MODES=WheelModes.values();
    private static final WheelRenderMode[] RENDER_MODES=WheelRenderMode.values();
    private enum Page { MAIN,MODES,RENDER }
    private final ClickableWidget surface=new WheelSurface();private final long epoch;
    private Page page=Page.MAIN;
    private boolean editingRetained;
    boolean retained(){return editingRetained;}
    private String error="",firstText="0",secondText="0";
    private OverlayTextField firstField,secondField;
    private ButtonWidget firstPlayer,secondPlayer;
    private long transition=System.nanoTime();
    private final UiMotion[] modeMotion=motions(MODES.length),mainMotion=motions(4),renderMotion=motions(9);
    private final UiMotion centerMotion=new UiMotion();
    private static UiMotion[] motions(int count){var result=new UiMotion[count];for(int i=0;i<count;i++)result[i]=new UiMotion();return result;}
    ModeWheelScreen(ProjectionController controller){super("快捷操作","",null,controller,true);epoch=controller.sessionEpoch();}
    @Override protected void buildMenu(){
        firstField=secondField=null;firstPlayer=secondPlayer=null;if(page!=Page.RENDER)return;
        var mode=controller.wheelRenderMode();
        if(mode.editable()){
            firstField=input(mode==WheelRenderMode.RANGE?"起始层":"当前层",firstText,168,v->{firstText=v;applyInput(false);});
            firstPlayer=playerButton(firstField);
            if(mode==WheelRenderMode.RANGE){secondField=input("结束层",secondText,211,v->{secondText=v;applyInput(false);});secondPlayer=playerButton(secondField);}
        }


    }
    private OverlayTextField input(String label,String value,int y,java.util.function.Consumer<String> changed){var field=new OverlayTextField(CARD_X+16,y,CARD_RIGHT-CARD_X-96,label);field.setMaxLength(12);field.setText(value);field.setChangedListener(changed);addBody(field,y-bodyTop);return field;}
    private ButtonWidget playerButton(OverlayTextField field){return buttonAt("移到玩家",CARD_RIGHT-68,field.getY()-bodyTop,58,()->{editingRetained=true;setFocused(field);field.setText(Integer.toString(client.player.getBlockY()));applyInput(true);},true,false);}
    @Override protected ClickableWidget previewControl(){return surface;}
    boolean modesPage(){return page==Page.MODES;}boolean renderingPage(){return page==Page.RENDER;}
    private double centerX(){return CX;}
    private void syncDraft(){firstText=Integer.toString(LayerScreen.first(controller.layerRange()));secondText=Integer.toString(LayerScreen.second(controller.layerRange()));}
    private void show(Page next){page=next;error="";setFocused(null);if(next==Page.RENDER)syncDraft();transition=System.nanoTime();refresh();}
    private void selectRender(WheelRenderMode mode){
        try{var current=controller.wheelRenderMode();int first=current==WheelRenderMode.ALL?client.player.getBlockY():LayerScreen.first(controller.layerRange());int second=current==WheelRenderMode.RANGE?LayerScreen.second(controller.layerRange()):first;
            controller.wheelRendering(mode,first,second);error="";syncDraft();setFocused(null);refresh();
        }catch(RuntimeException failure){error=message(failure);}
    }
    private static String message(RuntimeException failure){return java.util.Objects.toString(failure.getMessage(),"设置失败");}
    private WheelRenderMode.Coordinates coordinates(){int first=WheelRenderMode.coordinate(firstText),second=controller.wheelRenderMode()==WheelRenderMode.RANGE?WheelRenderMode.coordinate(secondText):first;controller.wheelRenderMode().range(first,second,0);return new WheelRenderMode.Coordinates(first,second);}
    private boolean applyInput(boolean strict){
        if(page!=Page.RENDER||!controller.wheelRenderMode().editable())return true;
        if(!strict&&(firstText.isEmpty()||firstText.equals("-")||controller.wheelRenderMode()==WheelRenderMode.RANGE&&(secondText.isEmpty()||secondText.equals("-")))){error="";return false;}
        try{var values=coordinates();controller.wheelRendering(controller.wheelRenderMode(),values.first(),values.second());error="";return true;}catch(RuntimeException invalid){error=message(invalid);return false;}
    }
    private void shift(int sign){
        try{var values=coordinates();var next=WheelRenderMode.shifted(controller.wheelRenderMode(),values.first(),values.second(),sign*WheelRenderMode.step(Screen.hasControlDown(),Screen.hasShiftDown()));controller.wheelRendering(controller.wheelRenderMode(),next.first(),next.second());error="";syncDraft();refresh();}catch(RuntimeException invalid){error=message(invalid);}
    }
    private UiViewport view(){var w=client.getWindow();return UiViewport.fit(w.getFramebufferWidth(),w.getFramebufferHeight(),w.getScaledWidth(),w.getScaledHeight(),w.getScaleFactor());}
    static int sectorAt(double x,double y,int count){return sectorAt(x,y,count,CX);}
    static int sectorAt(double x,double y,int count,double cx){
        double dx=x-cx,dy=y-CY,r=Math.hypot(dx,dy);if(r<INNER||r>OUTER)return -1;
        double step=Math.PI*2/count,angle=Math.atan2(dy,dx)+Math.PI/2+step/2;angle=(angle%(Math.PI*2)+Math.PI*2)%(Math.PI*2);
        if(count>1&&(angle%step<.02||angle%step>step-.02))return -1;return (int)(angle/step);
    }
    @Override public boolean mouseClicked(double x,double y,int button){
        if(button!=GLFW.GLFW_MOUSE_BUTTON_LEFT)return true;var v=view();double px=v.inputX(x),py=v.inputY(y);
        for(var field:new OverlayTextField[]{firstField,secondField})if(field!=null&&field.mouseClicked(px,py,button)){editingRetained=true;setFocused(field);return true;}

        for(var control:new ButtonWidget[]{firstPlayer,secondPlayer})if(control!=null&&control.mouseClicked(px,py,button))return true;
        setFocused(null);if(Math.hypot(px-centerX(),py-CY)<INNER-5){if(page!=Page.MAIN){show(Page.MAIN);centerMotion.press();}return true;}
        int count=page==Page.MAIN?4:page==Page.MODES?MODES.length:RENDER_MODES.length+2,selected=sectorAt(px,py,count,centerX());if(selected<0)return true;
        if(page==Page.MAIN){mainMotion[selected].press();if(selected==0)show(Page.MODES);else if(selected==1)controller.toggleRendering();else if(selected==2)show(Page.RENDER);else {try{controller.tool().executeHotkey();if(client.currentScreen==this)close();}catch(RuntimeException failure){error=message(failure);}}return true;}
        if(page==Page.RENDER){if(selected<RENDER_MODES.length){renderMotion[selected].press();selectRender(RENDER_MODES[selected]);}else if(controller.wheelRenderMode().editable()){renderMotion[selected].press();shift(selected==RENDER_MODES.length?-1:1);}return true;}
        modeMotion[selected].press();try{MODES[selected].toggle(controller);error="";}catch(RuntimeException failure){error=message(failure);}return true;
    }
    @Override public boolean mouseScrolled(double x,double y,double amount){return BetterLitematicaClient.scrollNearby(x,y,amount)||super.mouseScrolled(x,y,amount);}
    @Override public boolean mouseReleased(double x,double y,int button){var v=view();for(var field:new OverlayTextField[]{firstField,secondField})if(field!=null)field.mouseReleased(v.inputX(x),v.inputY(y),button);return true;}
    @Override public boolean mouseDragged(double x,double y,int button,double dx,double dy){var v=view();if(getFocused() instanceof OverlayTextField field)field.mouseDragged(v.inputX(x),v.inputY(y),button,v.deltaX(dx),v.deltaY(dy));return true;}
    @Override public boolean keyPressed(int key,int scan,int modifiers){
        if(key==GLFW.GLFW_KEY_ESCAPE){close();return true;}if(key==GLFW.GLFW_KEY_ENTER||key==GLFW.GLFW_KEY_KP_ENTER){applyInput(true);return true;}
        if(getFocused() instanceof OverlayTextField field)field.keyPressed(key,scan,modifiers);return true;
    }
    @Override public boolean charTyped(char chr,int modifiers){if(getFocused() instanceof OverlayTextField field)field.charTyped(chr,modifiers);return true;}
    @Override public void close(){
        if(epoch==controller.sessionEpoch()&&page==Page.RENDER&&controller.wheelRenderMode().editable())try{coordinates();}catch(RuntimeException invalid){controller.action(()->{throw new IllegalArgumentException("层数未保存："+message(invalid));});}
        client.setScreen(null);
    }
    private void drawCard(IndependentUi ui,DrawContext ctx,int x,int y,float delta){
        int bottom=controller.wheelRenderMode()==WheelRenderMode.RANGE?242:200;HudLayout.card(ui,CARD_X,CARD_TOP,CARD_RIGHT-CARD_X,bottom-CARD_TOP);
        ui.text(controller.wheelRenderMode().label(),CARD_X+10,CARD_TOP+9,CARD_RIGHT-CARD_X-20,UiTheme.TEXT);
        for(var field:new OverlayTextField[]{firstField,secondField})if(field!=null){ui.text(field.getMessage().getString(),CARD_X+10,field.getY()-14,CARD_RIGHT-CARD_X-20,UiTheme.MUTED);MenuScreen.paintFieldStatic(ui,field);field.render(ctx,x,y,delta);}
        for(var control:new ButtonWidget[]{firstPlayer,secondPlayer})if(control!=null)control.render(ctx,x,y,delta);
    }
    private void drawWheel(IndependentUi ui,DrawContext ctx,int x,int y,float delta){
        double progress=UiMotion.easeOut(Math.min(1,(System.nanoTime()-transition)/160_000_000d));ui.effect(0,0,progress);
        try{
            double cx=centerX();
            // Soft halo under the ring, then a hairline rim; no hard drop shadow over the world.
            for(int i=4;i>=1;i--)ui.sector(cx,CY+2,0,OUTER+i*3,0,Math.PI*2,UiMotion.alpha(UiTheme.SHADOW_AMBIENT,UiTheme.DARK?1.6:1.2));
            double centerHover=centerMotion.hover(page!=Page.MAIN&&Math.hypot(x-cx,y-CY)<INNER-5);
            ui.sector(cx,CY,0,INNER-5,0,Math.PI*2,UiMotion.mix(UiTheme.OVERLAY_PANEL,UiTheme.OVERLAY_SELECTED,centerHover));ui.sector(cx,CY,INNER-5.6,INNER-5,0,Math.PI*2,UiMotion.mix(UiTheme.BORDER,UiTheme.ACCENT,centerHover));
            int count=page==Page.MAIN?4:page==Page.MODES?MODES.length:RENDER_MODES.length+2,hover=sectorAt(x,y,count,cx);double step=Math.PI*2/count;
            for(int i=0;i<count;i++){
                boolean on=page==Page.MODES?MODES[i].enabled(controller.options().printer):page==Page.RENDER?i<RENDER_MODES.length&&RENDER_MODES[i]==controller.wheelRenderMode():i==1&&controller.projectionRenderingEnabled();
                double center=-Math.PI/2+i*step,start=center-step/2+.02,end=center+step/2-.02;var motion=page==Page.MODES?modeMotion[i]:page==Page.RENDER?renderMotion[i]:mainMotion[i];boolean enabled=page!=Page.RENDER||i<RENDER_MODES.length||controller.wheelRenderMode().editable();double over=motion.hover(enabled&&hover==i);
                int fill=enabled?UiMotion.mix(UiTheme.OVERLAY_PANEL,UiTheme.OVERLAY_SELECTED,Math.max(over,on?.55:0)):UiMotion.alpha(UiTheme.DISABLED,.85);fill=UiMotion.mix(fill,UiTheme.OVERLAY_SURFACE,motion.pressed()*.5);ui.sector(cx,CY,INNER,OUTER,start,end,fill);
                ui.sector(cx,CY,OUTER-.6,OUTER,start,end,UiMotion.alpha(UiTheme.BORDER,.9));ui.sector(cx,CY,INNER,INNER+.6,start,end,UiMotion.alpha(UiTheme.BORDER,.9));
                if(over>.01)ui.sector(cx,CY,OUTER-2.2,OUTER,start+.04,end-.04,UiMotion.alpha(UiTheme.ACCENT,over));
                if(on)ui.sector(cx,CY,INNER+3,INNER+5,center-.18,center+.18,UiTheme.ACCENT);
                double lx=cx+Math.cos(center)*94,ly=CY+Math.sin(center)*94;String label=page==Page.MODES?MODES[i].label():page==Page.RENDER?(i<RENDER_MODES.length?RENDER_MODES[i].label():i==RENDER_MODES.length?"下一层":"上一层"):i==0?"施工模式":i==1?"总渲染":i==2?"分层":"执行操作";
                int labelColor=!enabled?UiTheme.DISABLED_TEXT:hover==i||on?UiTheme.TEXT:UiTheme.SECONDARY;
                if(page==Page.MAIN){
                    // Each wheel glyph is unique to its action; none is borrowed from a main-menu destination.
                    var glyph=i==0?dev.betterlitematica.runtime.UiGlyphArt.Kind.MODES:i==1?(on?dev.betterlitematica.runtime.UiGlyphArt.Kind.EYE:dev.betterlitematica.runtime.UiGlyphArt.Kind.EYE_OFF):i==2?dev.betterlitematica.runtime.UiGlyphArt.Kind.LAYERS:dev.betterlitematica.runtime.UiGlyphArt.Kind.PLAY;
                    ui.glyph(glyph,lx-8,ly-(i==1?24:20),16,hover==i||on?UiTheme.ACCENT:UiTheme.MUTED);
                    ui.centered(label,lx-42,ly-(i==1?7:3),84,22,labelColor);
                    if(i==1)ui.centered(on?"开":"关",lx-24,ly+12,48,14,on?UiTheme.ACCENT:UiTheme.MUTED);
                }else{
                    ui.centered(label,lx-42,ly-(page==Page.RENDER?10:13),84,22,labelColor);
                    if(page==Page.MODES)ui.centered(MODES[i].mixed(controller.options().printer)?"部分":on?"开":"关",lx-24,ly+8,48,14,on?UiTheme.ACCENT:UiTheme.MUTED);
                }
            }
            boolean back=page!=Page.MAIN&&Math.hypot(x-cx,y-CY)<INNER-5;if(page!=Page.MAIN){ui.glyph(dev.betterlitematica.runtime.UiGlyphArt.Kind.BACK,cx-7,CY-17,14,back?UiTheme.ACCENT:UiTheme.MUTED);ui.centered("返回",cx-45,CY-4,90,22,back?UiTheme.ACCENT:UiTheme.SECONDARY);}
            else{ui.glyph(dev.betterlitematica.runtime.UiGlyphArt.Kind.BRAND,cx-8,CY-19,16,UiTheme.ACCENT);ui.centered("快捷操作",cx-45,CY-3,90,22,UiTheme.SECONDARY);}
            if(page==Page.MODES&&hover==1)ui.centered("投影内：错误 · 多余 · 错误状态",cx-110,CY+OUTER+14,220,20,UiTheme.SECONDARY);
            if(page==Page.RENDER&&controller.wheelRenderMode().editable())drawCard(ui,ctx,x,y,delta);if(!error.isEmpty())ui.centered(error,70,365,460,22,UiTheme.ERROR);
        }finally{ui.effect(0,0,1);}
    }
    private final class WheelSurface extends ClickableWidget {
        WheelSurface(){super(0,0,600,400,Text.literal("快捷操作"));}
        @Override public void renderButton(DrawContext context,int x,int y,float delta){drawWheel(IndependentUi.INSTANCE,context,x,y,delta);}
        @Override protected void appendClickableNarrations(NarrationMessageBuilder builder){builder.put(NarrationPart.TITLE,getMessage());}
    }
}
