package dev.betterlitematica.fabric;

import dev.betterlitematica.core.UiViewport;
import net.minecraft.client.Minecraft;

/** Shared safe-area docking for all independent in-world cards. */
record HudLayout(UiViewport viewport,double left,double top,double right,double bottom,
                 double statusWidth,double informationWidth) {
    static final double GAP=6;
    static boolean menuHidesHud(net.minecraft.client.gui.screens.Screen screen){return screen!=null&&!(screen instanceof ModeWheelScreen);}
    static HudLayout of(Minecraft client,boolean doubleInformation){
        var w=client.getWindow();
        return of(UiViewport.fit(Math.max(1,w.getWidth()),Math.max(1,w.getHeight()),
            Math.max(1,w.getGuiScaledWidth()),Math.max(1,w.getGuiScaledHeight()),w.getGuiScale()),doubleInformation);
    }
    static HudLayout of(UiViewport view,boolean doubleInformation){
        double left=view.localPixelX(view.pixelWidth()*.05),right=view.localPixelX(view.pixelWidth()*.95);
        double top=view.localPixelY(view.pixelHeight()*.05),bottom=view.localPixelY(view.pixelHeight()*.95);
        double available=right-left;
        double status=150;
        double information=doubleInformation?Math.min(228,(available-status-GAP*2)/2):228;
        return new HudLayout(view,left,top,right,bottom,status,information);
    }
    private double toolEdgePixels(){return Math.max(8,5*viewport.scale());}
    double toolLeft(){return viewport.localPixelX(toolEdgePixels());}
    double toolBottom(){return viewport.localPixelY(viewport.pixelHeight()-toolEdgePixels());}
    static void card(IndependentUi ui,double x,double y,double width,double height){
        double radius=UiTheme.CARD_RADIUS;
        ui.shadow(x,y,x+width,y+height,radius);
        ui.roundRect(x,y,x+width,y+height,radius,UiTheme.OVERLAY_PANEL);
        ui.roundFrame(x,y,x+width,y+height,radius,UiMotion.alpha(UiTheme.BORDER,.85));
        ui.sheen(x,y+ui.pixel(),x+width,radius);
    }
}
