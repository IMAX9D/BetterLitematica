package dev.betterlitematica.fabric;

import dev.betterlitematica.runtime.StatusBadgeArt;
import dev.betterlitematica.runtime.UiGlyphArt;

/** Bounded presentation snapshots; ordinary status uses symbols, not instructional sentences. */
final class StatusHud {
    private long epoch=Long.MIN_VALUE,nextSnapshot;
    private String detail="",badgeLabel="打印中",blockedReason="";
    private PrinterReason.Id candidateReason=PrinterReason.Id.NONE;
    private long reasonSince;private int percent=-1;
    private java.util.List<PrinterEngine.Missing> missing=java.util.List.of();
    private NearbyBuildHud.Snapshot nearby=NearbyBuildHud.Snapshot.EMPTY;
    private java.util.List<NearbyBuildHud.Entry> displayEntries=java.util.List.of();
    private java.util.Set<net.minecraft.item.Item> unavailable=java.util.Set.of();
    private PrinterEngine.State printerState=PrinterEngine.State.STOPPED;
    private boolean printerVisible,error;
    private long motionEpoch=Long.MIN_VALUE;
    private UiMotion printerMotion=new UiMotion();
    private HudExpansion nearbyMotion=new HudExpansion();
    private int selectedPage,pageCount=1;
    private double cardX,cardY,cardWidth,cardHeight;
    private dev.betterlitematica.core.UiViewport viewport;
    boolean scroll(double x,double y,double amount){
        if(viewport==null||pageCount<=1||amount==0)return false;
        x=viewport.inputX(x);y=viewport.inputY(y);
        if(x<cardX||x>cardX+cardWidth||y<cardY||y>cardY+cardHeight)return false;
        selectedPage=Math.floorMod(selectedPage+(amount>0?-1:1),pageCount);return true;
    }
    static boolean missingStatus(PrinterReason status){return status.missing();}


    private void snapshot(ProjectionController controller){
        long now=System.nanoTime();var printer=controller.printer();
        boolean visible=printer.state()!=PrinterEngine.State.STOPPED&&controller.options().printer.hud;
        if(epoch==controller.sessionEpoch()&&now<nextSnapshot&&printerState==printer.state()&&printerVisible==visible)return;
        epoch=controller.sessionEpoch();nextSnapshot=now+200_000_000L;printerState=printer.state();printerVisible=visible;
        String projection=controller.hud();
        error=!controller.actionError().isEmpty();
        detail=projection.matches("投影 \\d+ · 仅预览附近区域")||projection.equals("投影已隐藏")?"":projection;
        PrinterReason status=printer.reason();percent=printer.progressPercent();
        missing=visible?printer.missingMaterials():java.util.List.of();nearby=printer.nearbyHud();
        var unavailableNow=new java.util.HashSet<net.minecraft.item.Item>();for(var e:nearby.entries())if(!e.icon().isEmpty()&&printer.stock().available(e.icon().getItem())==0)unavailableNow.add(e.icon().getItem());unavailable=java.util.Set.copyOf(unavailableNow);
        var missingItems=new java.util.HashSet<net.minecraft.item.Item>();for(var entry:missing)missingItems.add(entry.item());
        displayEntries=nearby.entries().stream().filter(e->e.icon().isEmpty()||!missingItems.contains(e.icon().getItem())).sorted(java.util.Comparator.comparingInt(e->!e.icon().isEmpty()&&unavailable.contains(e.icon().getItem())?0:1)).toList();
        String reason=visible?reason(status,!missing.isEmpty()):"";
        if(status.id()==PrinterReason.Id.NO_WORK&&percent==100)reason="";
        var reasonId=reason.isEmpty()?PrinterReason.Id.NONE:status.id();
        if(reasonId!=candidateReason){candidateReason=reasonId;reasonSince=now;blockedReason="";}
        if(reason.isEmpty())blockedReason="";else if(now-reasonSince>=1_000_000_000L)blockedReason=reason;
        badgeLabel=!blockedReason.isEmpty()?blockedReason:"打印中"+(percent<0?"":" · "+percent+"%");
        if(detail.isEmpty()&&printerVisible)detail=switch(status.id()){
            case NONE,RUNNING,PAUSED,STOPPED,NO_WORK,LOADING,MISSING->"";
            default->!reason.isEmpty()?"":status.description();
        };

    }

    void draw(IndependentUi ui,ProjectionController controller,HudLayout layout,double toolTop){
        snapshot(controller);
        double x=layout.left(),y=layout.top(),line=Math.max(12,ui.lineHeight()),row=Math.max(18,line+2),maxWidth=layout.statusWidth();
        // Read the switches every frame; only the explanatory detail below uses the 5 Hz snapshot.
        boolean projectionBadge=controller.hasProjection();var currentState=controller.printer().state();
        boolean printerBadge=controller.options().printer.hud&&(projectionBadge||currentState!=PrinterEngine.State.STOPPED);
        if(motionEpoch!=controller.sessionEpoch()){
            motionEpoch=controller.sessionEpoch();printerMotion=new UiMotion();nearbyMotion=new HudExpansion();selectedPage=0;
        }
        double expansion=printerMotion.hover(printerBadge&&(currentState==PrinterEngine.State.RUNNING||!blockedReason.isEmpty()));
        if(projectionBadge||printerBadge){
            double diameter=24,tx=x;ui.prepareStatusBadges(diameter);
            if(projectionBadge){ui.statusBadge(controller.projectionRenderingEnabled()?StatusBadgeArt.Kind.EYE_OPEN:StatusBadgeArt.Kind.EYE_CLOSED,tx,y,diameter);tx+=diameter+HudLayout.GAP;}
            if(printerBadge)drawPrinterBadge(ui,tx,y,diameter,expansion,currentState==PrinterEngine.State.PAUSED,badgeLabel,!blockedReason.isEmpty(),currentState==PrinterEngine.State.RUNNING?percent:-1);
            y+=diameter+HudLayout.GAP;
        }
        if(!detail.isEmpty()){
            double width=Math.min(maxWidth,Math.max(34,ui.measure(detail)+30)),height=12+row;
            String firstDetail=error?ui.fittingText(detail,Math.max(0,width-24)):detail;
            String errorTail=error?detail.substring(firstDetail.length()):"";if(!errorTail.isEmpty())height+=line+1;
            HudLayout.card(ui,x,y,width,height);double tx=x+6,ty=y+6;
            HudIcons.warning(ui,tx,ty+3,10,error?UiTheme.ERROR:UiTheme.WARNING);tx+=12;
            if(!detail.isEmpty())ui.text(firstDetail,tx,ty+(row-line)/2,Math.max(0,x+width-6-tx),error?UiTheme.ERROR:UiTheme.SECONDARY);
            if(!errorTail.isEmpty())ui.text(errorTail,x+18,ty+(row-line)/2+line+1,width-24,UiTheme.ERROR);
            y+=height+HudLayout.GAP;
        }
        drawNearby(ui,nearby,layout,x,y,toolTop);
    }

    static String reason(PrinterReason status,boolean missing){
        if(missing&&status.id()==PrinterReason.Id.NO_WORK)return PrinterReason.Id.MISSING.shortLabel();
        return status.shortLabel();
    }

    private void drawNearby(IndependentUi ui,NearbyBuildHud.Snapshot snapshot,HudLayout layout,double x,double y,double toolTop){
        viewport=layout.viewport();
        var entries=displayEntries;
        long now=System.nanoTime();double line=ui.lineHeight();
        double cellWidth=Math.max(32,ui.measure("9999")+6),rowHeight=25+line,width=cellWidth*4+12,header=12+line;
        if(!missing.isEmpty())width=Math.max(width,180);
        cellWidth=(width-12)/4;
        double bottom=Math.min(Math.min(layout.bottom()-30,toolTop-HudLayout.GAP),y+(layout.bottom()-layout.top())*.45);
        double gridReserve=entries.isEmpty()?0:rowHeight+line+4;
        int missingRows=Math.min(6,Math.min(missing.size(),Math.max(0,(int)((bottom-y-header-6-gridReserve-(line+4))/(line+8)))));
        double missingHeight=missingRows*(line+8)+(missing.size()>missingRows?line+4:0);
        int maxRows=Math.max(0,(int)((bottom-y-header-missingHeight-(line+4)-6)/rowHeight));
        int pageSize=Math.max(4,maxRows*4),pages=Math.max(1,(entries.size()+pageSize-1)/pageSize);
        pageCount=pages;selectedPage=Math.min(selectedPage,pages-1);
        int page=selectedPage,start=page*pageSize;
        int shown=maxRows==0?0:Math.min(pageSize,entries.size()-start),rows=HudExpansion.rows(shown);
        boolean present=!entries.isEmpty()||!missing.isEmpty()||snapshot.scanning()||snapshot.unknown()>0;
        String hint=snapshot.unknown()>0?"待加载":snapshot.scanning()?"刷新中":"";
        double footer=(pages>1?line+4:0);
        double target=present?header+missingHeight+rows*rowHeight+6+footer:0;
        double height=nearbyMotion.height(target,now);
        cardX=x;cardY=y;cardWidth=width;cardHeight=height;
        if(height<1)return;
        HudLayout.card(ui,x,y,width,height);ui.clip(x+1,y+1,x+width-1,y+height-1);
        ui.text(missing.isEmpty()?"周围待建":"缺 "+missing.size()+" 种",x+7,y+6,width-64,missing.isEmpty()?UiTheme.SECONDARY:UiTheme.ERROR);
        if(!hint.isEmpty())ui.text(hint,x+width-52,y+6,45,UiTheme.WARNING);
        for(int i=0;i<missingRows;i++){
            var entry=missing.get(i);double cy=y+header+i*(line+8);
            ui.roundRect(x+6,cy,x+width-6,cy+line+6,4,UiMotion.alpha(UiTheme.ERROR,.09));
            ui.item(new net.minecraft.item.ItemStack(entry.item()),x+8,cy+1,line+4);
            String need=entry.required()>0?"需 "+HudNumbers.compact(entry.required()):"取料失败";
            double countWidth=ui.measure(need)+4;
            ui.text(entry.item().getName().getString(),x+line+17,cy+3,width-line-countWidth-32,UiTheme.ERROR);
            ui.text(need,x+width-countWidth-9,cy+3,countWidth,UiTheme.SECONDARY);
        }
        if(missing.size()>missingRows)ui.text("还有 "+(missing.size()-missingRows)+" 种",x+8,y+header+missingRows*(line+8),width-16,UiTheme.ERROR);
        for(int i=0;i<shown;i++){
            var entry=entries.get(start+i);double cx=x+6+i%4*cellWidth,cy=y+header+missingHeight+i/4*rowHeight;
            ui.roundRect(cx,cy,cx+cellWidth-2,cy+rowHeight-3,5,UiMotion.alpha(UiTheme.BORDER,.28));
            if(entry.icon().isEmpty())ui.text(entry.block().getName().getString(),cx+2,cy+5,cellWidth-6,UiTheme.SECONDARY);
            else ui.item(entry.icon(),cx+(cellWidth-22)/2,cy+2,20);
            boolean absent=!entry.icon().isEmpty()&&unavailable.contains(entry.icon().getItem());
            if(absent||entry.wrongState()+entry.wrongBlock()>0)ui.roundFrame(cx,cy,cx+cellWidth-2,cy+rowHeight-3,5,absent?UiTheme.ERROR:UiTheme.WARNING);
            String count=HudNumbers.compact(entry.count());ui.text(count,cx+(cellWidth-2-ui.measure(count))/2,cy+23,cellWidth-4,absent?UiTheme.ERROR:UiTheme.TEXT);
        }
        double footY=y+header+missingHeight+rows*rowHeight;

        if(pages>1){ui.text((page+1)+" / "+pages+" · "+entries.size()+" 类",x+7,footY,width-14,UiTheme.SECONDARY);}
        ui.unclip();
    }

    /** One continuous surface; only the right edge moves, with no rescaled icons or text rasters. */
    static void drawPrinterBadge(IndependentUi ui,double x,double y,double diameter,double expansion){drawPrinterBadge(ui,x,y,diameter,expansion,false);}
    /** Paused keeps the collapsed circle but tints the printer amber, so it never reads as switched off. */
    static void drawPrinterBadge(IndependentUi ui,double x,double y,double diameter,double expansion,boolean paused){
        drawPrinterBadge(ui,x,y,diameter,expansion,paused,"打印中");
    }
    static void drawPrinterBadge(IndependentUi ui,double x,double y,double diameter,double expansion,boolean paused,String label){
        drawPrinterBadge(ui,x,y,diameter,expansion,paused,label,false,-1);
    }
    static void drawPrinterBadge(IndependentUi ui,double x,double y,double diameter,double expansion,boolean paused,String label,boolean blocked,int percent){
        double progress=UiMotion.clamp(expansion),radius=diameter/2;
        ui.prepareText(label);
        double width=diameter+(ui.measure(label)+10)*progress;
        int surface=UiTheme.DARK?0xee1f2027:0xe3f6f9fc;
        int border=UiTheme.DARK?0x1effffff:0x3a8191a7;
        int idle=paused?UiTheme.WARNING:UiTheme.DARK?0xff82868f:0xff8993a1;
        int icon=UiMotion.mix(idle,blocked?UiTheme.WARNING:UiTheme.DARK?0xff6ed69c:0xff0ab463,progress);
        ui.shadow(x,y,x+width,y+diameter,radius);
        ui.roundRect(x,y,x+width,y+diameter,radius,surface);
        ui.roundFrame(x,y,x+width,y+diameter,radius,border);
        ui.glyph(UiGlyphArt.Kind.PRINTER,x+5,y+5,diameter-10,icon);
        if(percent>=0&&progress>0){ui.sector(x+radius,y+radius,radius-2.5,radius-1,-Math.PI/2,Math.PI*1.5,UiMotion.alpha(icon,.15));if(percent>0)ui.sector(x+radius,y+radius,radius-2.5,radius-1,-Math.PI/2,-Math.PI/2+Math.PI*2*Math.min(100,percent)/100,icon);}
        if(progress>0){
            ui.clip(x+diameter,y,x+width-5,y+diameter);
            ui.rawText(label,x+diameter+1,y+(diameter-ui.lineHeight())/2,UiMotion.alpha(UiTheme.TEXT,progress));
            ui.unclip();
        }
    }
}
