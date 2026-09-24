package dev.betterlitematica.fabric;

import dev.betterlitematica.core.UiViewport;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.widget.ClickableWidget;
import net.minecraft.client.gui.screen.narration.NarrationMessageBuilder;
import net.minecraft.client.gui.screen.narration.NarrationPart;
import net.minecraft.text.Text;
import org.lwjgl.glfw.GLFW;

/** Hold-to-open independent overlay; settings are independent, never an exclusive mode selection. */
final class ModeWheelScreen extends MenuScreen {
    static final double CX=300,CY=200,INNER=54,OUTER=132;
    private static final WheelModes[] MODES={WheelModes.PRINT,WheelModes.MINE,WheelModes.DRAIN,WheelModes.FILL};
    private final ClickableWidget surface=new WheelSurface();
    private boolean modes;
    private String error="";
    ModeWheelScreen(ProjectionController controller){super("快捷操作","",null,controller,true);}
    @Override protected void buildMenu(){}
    @Override protected ClickableWidget previewControl(){return surface;}
    boolean modesPage(){return modes;}
    private UiViewport view(){var w=client.getWindow();return UiViewport.fit(w.getFramebufferWidth(),w.getFramebufferHeight(),w.getScaledWidth(),w.getScaledHeight(),w.getScaleFactor());}
    static int sectorAt(double x,double y,int count){
        double dx=x-CX,dy=y-CY,r=Math.hypot(dx,dy);if(r<INNER||r>OUTER)return -1;
        double step=Math.PI*2/count,angle=Math.atan2(dy,dx)+Math.PI/2+step/2;
        angle=(angle%(Math.PI*2)+Math.PI*2)%(Math.PI*2);
        int index=(int)(angle/step);
        if(count>1&&(angle%step<.02||angle%step>step-.02))return -1;
        return index;
    }
    @Override public boolean mouseClicked(double x,double y,int button){
        if(button!=GLFW.GLFW_MOUSE_BUTTON_LEFT)return true;
        var v=view();double px=v.inputX(x),py=v.inputY(y);
        if(Math.hypot(px-CX,py-CY)<INNER-5){if(modes){modes=false;error="";}return true;}
        int selected=sectorAt(px,py,modes?MODES.length:2);if(selected<0)return true;
        if(!modes){if(selected==0)modes=true;else controller.toggleRendering();return true;}
        try{MODES[selected].toggle(controller);error="";}catch(RuntimeException failure){error=java.util.Objects.toString(failure.getMessage(),"设置失败");}
        return true;
    }
    @Override public boolean mouseReleased(double x,double y,int button){return true;}
    @Override public boolean mouseDragged(double x,double y,int button,double dx,double dy){return true;}
    @Override public boolean keyPressed(int key,int scan,int modifiers){if(key==GLFW.GLFW_KEY_ESCAPE){close();return true;}return true;}
    private void drawWheel(IndependentUi ui,int x,int y){
        ui.sector(CX,CY+3,0,OUTER+5,0,Math.PI*2,0x58000000);
        ui.sector(CX,CY,0,INNER-5,0,Math.PI*2,UiTheme.PANEL);
        ui.sector(CX,CY,INNER-6,INNER-5,0,Math.PI*2,UiTheme.BORDER);
        int count=modes?MODES.length:2,hover=sectorAt(x,y,count);double step=Math.PI*2/count;
        for(int i=0;i<count;i++){
            boolean on=modes?MODES[i].enabled(controller.options().printer):i==1&&controller.projectionRenderingEnabled();double center=-Math.PI/2+i*step,start=center-step/2+(count>1?.02:0),end=center+step/2-(count>1?.02:0);
            ui.sector(CX,CY,INNER,OUTER,start,end,on?UiTheme.SELECTED:UiTheme.SURFACE);
            if(hover==i)ui.sector(CX,CY,INNER,OUTER,start,end,0x244f4b44);
            ui.sector(CX,CY,OUTER-1,OUTER,start,end,hover==i?UiTheme.FOCUS:UiTheme.BORDER);
            if(on)ui.sector(CX,CY,OUTER-4,OUTER-2,start+.09,end-.09,UiTheme.ACCENT);
            double lx=CX+Math.cos(center)*94,ly=CY+Math.sin(center)*94;
            ui.centered(modes?MODES[i].label():i==0?"模式选择":"总渲染",lx-42,ly-13,84,22,hover==i||on?UiTheme.TEXT:UiTheme.SECONDARY);
            if(modes||i==1)ui.centered(modes&&MODES[i].mixed(controller.options().printer)?"部分":on?"开":"关",lx-24,ly+8,48,14,on?UiTheme.ACCENT:UiTheme.MUTED);
        }
        boolean back=modes&&Math.hypot(x-CX,y-CY)<INNER-5;
        ui.centered(modes?"返回":"快捷操作",CX-45,CY-12,90,24,back?UiTheme.ACCENT:UiTheme.SECONDARY);
        if(modes&&hover==1)ui.centered("投影内：错误 · 多余 · 错误状态",CX-110,CY+OUTER+14,220,20,UiTheme.SECONDARY);
        if(!error.isEmpty())ui.centered(error,90,365,420,22,UiTheme.ERROR);
    }
    private final class WheelSurface extends ClickableWidget {
        WheelSurface(){super(0,0,600,400,Text.literal("快捷操作"));}
        @Override public void renderButton(DrawContext context,int x,int y,float delta){drawWheel(IndependentUi.INSTANCE,x,y);}
        @Override protected void appendClickableNarrations(NarrationMessageBuilder builder){builder.put(NarrationPart.TITLE,getMessage());}
    }
}
