package dev.betterlitematica.fabric;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;

final class ClipboardIconButton extends UiButton {
    private final boolean paste;
    private final UiMotion motion=new UiMotion();
    ClipboardIconButton(int x,boolean paste,Runnable action){super(x,0,20,20,Component.literal(paste?"粘贴":"复制"),b->action.run(),DEFAULT_NARRATION);this.paste=paste;}
    @Override public void onPress(){motion.press();super.onPress();}
    @Override public void renderWidget(GuiGraphicsExtractor ctx,int mx,int my,float delta){
        var ui=IndependentUi.INSTANCE;int x=getX(),y=getY();
        boolean focused=(isMouseOver(mx,my)||isFocused())&&active;
        double hover=motion.hover(focused);int color=active?UiMotion.mix(UiTheme.SECONDARY,UiTheme.TEXT,hover):UiTheme.DISABLED_TEXT;
        MenuScreen.MenuButton.paint(ui,x,y,20,20,MenuScreen.Look.STANDARD,active,isFocused(),hover,motion.pressed());
        ui.glyph(paste?dev.betterlitematica.runtime.UiGlyphArt.Kind.PASTE:dev.betterlitematica.runtime.UiGlyphArt.Kind.COPY,x+3,y+3+motion.pressed()*.5,14,color);
    }
}
