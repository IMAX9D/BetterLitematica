package dev.betterlitematica.fabric;

import java.util.List;
import dev.betterlitematica.runtime.StatusBadgeArt;
import dev.betterlitematica.runtime.UiGlyphArt;
import net.minecraft.item.ItemStack;

/** Bounded presentation snapshots; ordinary status uses symbols, not instructional sentences. */
final class StatusHud {
    private long epoch=Long.MIN_VALUE,nextSnapshot;
    private String detail="";
    private List<ItemStack> shortages=List.of();
    private PrinterEngine.State printerState=PrinterEngine.State.STOPPED;
    private boolean printerVisible,error;
    private long motionEpoch=Long.MIN_VALUE;
    private UiMotion printerMotion=new UiMotion();

    private void snapshot(ProjectionController controller){
        long now=System.nanoTime();var printer=controller.printer();
        boolean visible=printer.state()!=PrinterEngine.State.STOPPED&&controller.options().printer.hud;
        if(epoch==controller.sessionEpoch()&&now<nextSnapshot&&printerState==printer.state()&&printerVisible==visible)return;
        epoch=controller.sessionEpoch();nextSnapshot=now+200_000_000L;printerState=printer.state();printerVisible=visible;
        String projection=controller.hud();
        error=!controller.actionError().isEmpty();
        detail=projection.matches("投影 \\d+ · 仅预览附近区域")||projection.equals("投影已隐藏")?"":projection;
        String status=printer.status();
        shortages=error?List.of():printer.missingHudItems().stream().map(ItemStack::new).toList();
        if(detail.isEmpty()&&printerVisible)detail=switch(status){
            case "打印中","已暂停","已停止","等待可施工位置"->"";
            case "等待加载"->"待加载";
            case "等待材料"->shortages.isEmpty()?"缺料":"";
            default->status;
        };
        if(!shortages.isEmpty()&&detail.startsWith("缺少"))detail="";
    }

    void draw(IndependentUi ui,ProjectionController controller,HudLayout layout){
        snapshot(controller);
        double x=layout.left(),y=layout.top(),line=Math.max(12,ui.lineHeight()),row=Math.max(18,line+2),maxWidth=layout.statusWidth();
        // Read the switches every frame; only the explanatory detail below uses the 5 Hz snapshot.
        boolean projectionBadge=controller.hasProjection();var currentState=controller.printer().state();
        boolean printerBadge=controller.options().printer.hud&&(projectionBadge||currentState!=PrinterEngine.State.STOPPED);
        if(motionEpoch!=controller.sessionEpoch()){
            motionEpoch=controller.sessionEpoch();printerMotion=new UiMotion();
        }
        double expansion=printerMotion.hover(printerBadge&&currentState==PrinterEngine.State.RUNNING);
        if(projectionBadge||printerBadge){
            double diameter=24,tx=x;ui.prepareStatusBadges(diameter);
            if(projectionBadge){ui.statusBadge(controller.projectionRenderingEnabled()?StatusBadgeArt.Kind.EYE_OPEN:StatusBadgeArt.Kind.EYE_CLOSED,tx,y,diameter);tx+=diameter+HudLayout.GAP;}
            if(printerBadge)drawPrinterBadge(ui,tx,y,diameter,expansion,currentState==PrinterEngine.State.PAUSED);
            y+=diameter+HudLayout.GAP;
        }
        double shortageWidth=shortages.isEmpty()?0:10+shortages.size()*18+4;
        double secondWidth=shortageWidth+(!detail.isEmpty()?Math.min(120,ui.measure(detail)+2)+(shortages.isEmpty()?12:0):0);
        if(!detail.isEmpty()||!shortages.isEmpty()){
            double width=Math.min(maxWidth,Math.max(34,secondWidth+12)),height=12+row;
            String firstDetail=error?ui.fittingText(detail,Math.max(0,width-24)):detail;
            String errorTail=error?detail.substring(firstDetail.length()):"";if(!errorTail.isEmpty())height+=line+1;
            HudLayout.card(ui,x,y,width,height);double tx=x+6,ty=y+6;
            HudIcons.warning(ui,tx,ty+3,10,error?UiTheme.ERROR:UiTheme.WARNING);tx+=12;
            for(var item:shortages){ui.item(item,tx,ty,17);tx+=18;}
            if(!detail.isEmpty())ui.text(firstDetail,tx,ty+(row-line)/2,Math.max(0,x+width-6-tx),error?UiTheme.ERROR:UiTheme.SECONDARY);
            if(!errorTail.isEmpty())ui.text(errorTail,x+18,ty+(row-line)/2+line+1,width-24,UiTheme.ERROR);
        }
    }

    /** One continuous surface; only the right edge moves, with no rescaled icons or text rasters. */
    static void drawPrinterBadge(IndependentUi ui,double x,double y,double diameter,double expansion){drawPrinterBadge(ui,x,y,diameter,expansion,false);}
    /** Paused keeps the collapsed circle but tints the printer amber, so it never reads as switched off. */
    static void drawPrinterBadge(IndependentUi ui,double x,double y,double diameter,double expansion,boolean paused){
        double progress=UiMotion.clamp(expansion),radius=diameter/2;
        String label="打印中";
        ui.prepareText(label);
        double width=diameter+(ui.measure(label)+10)*progress;
        int surface=UiTheme.DARK?0xee1f2027:0xe3f6f9fc;
        int border=UiTheme.DARK?0x1effffff:0x3a8191a7;
        int idle=paused?UiTheme.WARNING:UiTheme.DARK?0xff82868f:0xff8993a1;
        int icon=UiMotion.mix(idle,UiTheme.DARK?0xff6ed69c:0xff0ab463,progress);
        ui.shadow(x,y,x+width,y+diameter,radius);
        ui.roundRect(x,y,x+width,y+diameter,radius,surface);
        ui.roundFrame(x,y,x+width,y+diameter,radius,border);
        ui.glyph(UiGlyphArt.Kind.PRINTER,x+5,y+5,diameter-10,icon);
        if(progress>0){
            ui.clip(x+diameter,y,x+width-5,y+diameter);
            ui.rawText(label,x+diameter+1,y+(diameter-ui.lineHeight())/2,UiMotion.alpha(UiTheme.TEXT,progress));
            ui.unclip();
        }
    }
}
