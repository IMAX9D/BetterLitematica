package dev.betterlitematica.fabric;

import dev.betterlitematica.runtime.UiGlyphArt;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;

/** Square icon control; its accessible name is the narrated message, its tooltip comes from the screen hint. */
final class GlyphButton extends ButtonWidget {
    private final UiGlyphArt.Kind glyph;private final MenuScreen.Look look;private final int tint;
    private final UiMotion motion;
    GlyphButton(int x,int size,UiGlyphArt.Kind glyph,String name,MenuScreen.Look look,int tint,UiMotion motion,Runnable action){
        super(x,0,size,size,net.minecraft.text.Text.literal(name),b->action.run(),DEFAULT_NARRATION_SUPPLIER);this.glyph=glyph;this.look=look;this.tint=tint;this.motion=motion;
    }
    @Override public void drawIcon(DrawContext ctx,int mouseX,int mouseY,float delta){
        var ui=IndependentUi.INSTANCE;double hover=motion.hover(active&&(isHovered()||isFocused())),press=motion.pressed();
        MenuScreen.MenuButton.paint(ui,getX(),getY(),width,height,look,active,isFocused(),hover,press);
        int color=!active?UiTheme.DISABLED_TEXT:tint!=0?tint:look==MenuScreen.Look.PRIMARY?UiTheme.FOCUS:UiMotion.mix(UiTheme.SECONDARY,UiTheme.TEXT,hover);
        double icon=Math.round(height*.62);ui.glyph(glyph,getX()+(width-icon)/2,getY()+(height-icon)/2+press*.5,icon,color);
    }
    @Override public void onPress(net.minecraft.client.input.AbstractInput input){motion.press();super.onPress(input);}
}
