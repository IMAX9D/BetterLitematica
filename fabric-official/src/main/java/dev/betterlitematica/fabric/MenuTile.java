package dev.betterlitematica.fabric;

import dev.betterlitematica.runtime.UiGlyphArt;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;

/** Launcher tile: an icon well and a single label. Emphasised tiles carry the accent, never a second colour. */
final class MenuTile extends UiButton {
    private final UiGlyphArt.Kind glyph;private final boolean emphasis;private final UiMotion motion;
    MenuTile(int x,int width,int height,UiGlyphArt.Kind glyph,String label,boolean emphasis,UiMotion motion,Runnable action){
        super(x,0,width,height,Component.literal(label),b->action.run(),DEFAULT_NARRATION);this.glyph=glyph;this.emphasis=emphasis;this.motion=motion;
    }
    @Override public void renderWidget(GuiGraphicsExtractor ctx,int mouseX,int mouseY,float delta){
        var ui=IndependentUi.INSTANCE;double hover=motion.hover(active&&(isHovered()||isFocused())),press=motion.pressed();
        double x=getX(),y=getY()+press*.5,r=x+width,b=y+height,radius=UiTheme.CARD_RADIUS;
        if(isFocused())ui.ring(x,y,r,b,radius,1);
        if(!UiTheme.DARK)ui.contact(x,y,r,b,radius);
        int fill=UiMotion.mix(UiTheme.SURFACE,UiTheme.HOVER,hover);fill=UiMotion.mix(fill,UiTheme.PRESSED,press*.7);
        ui.roundRect(x,y,r,b,radius,fill);
        ui.roundFrame(x,y,r,b,radius,isFocused()?UiTheme.ACCENT:UiMotion.mix(UiTheme.BORDER,emphasis?UiTheme.ACCENT:UiTheme.BORDER_STRONG,hover*.8));
        ui.sheen(x,y+ui.pixel(),r,radius);
        double well=height-12,wx=x+6,wy=y+6;
        int wellFill=emphasis?UiTheme.ACCENT_SOFT:UiMotion.alpha(UiTheme.DARK?0xffffffff:UiTheme.TEXT,UiTheme.DARK?.05:.045);
        ui.roundRect(wx,wy,wx+well,wy+well,radius-3,wellFill);
        double icon=Math.min(16,well-8);
        ui.glyph(glyph,wx+(well-icon)/2,wy+(well-icon)/2,icon,emphasis?UiTheme.ACCENT:UiMotion.mix(UiTheme.SECONDARY,UiTheme.TEXT,hover));
        double tx=wx+well+9;
        ui.text(getMessage().getString(),tx,y+(height-ui.lineHeight())/2-.5,r-tx-8,!active?UiTheme.DISABLED_TEXT:UiTheme.TEXT);
    }
    @Override public void onPress(){motion.press();super.onPress();}
}
