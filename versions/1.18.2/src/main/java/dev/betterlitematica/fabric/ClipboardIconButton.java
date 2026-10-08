package dev.betterlitematica.fabric;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;

final class ClipboardIconButton extends ButtonWidget {
    public int getX(){return x;} public int getY(){return y;} public void setX(int value){x=value;} public void setY(int value){y=value;}

    private final boolean paste;
    private final UiMotion motion=new UiMotion();
    ClipboardIconButton(int x,boolean paste,Runnable action){super(x,0,20,20,new net.minecraft.text.LiteralText(paste?"粘贴":"复制"),b->action.run(),EMPTY);this.paste=paste;}
    @Override public void onPress(){motion.press();super.onPress();}
    @Override public void renderButton(MatrixStack legacyMatrices,int mx,int my,float delta){LegacyGuiContext ctx=new LegacyGuiContext(legacyMatrices);
        var ui=IndependentUi.INSTANCE;int x=getX(),y=getY();
        boolean focused=(isMouseOver(mx,my)||isFocused())&&active;
        double hover=motion.hover(focused);int color=active?UiMotion.mix(UiTheme.SECONDARY,UiTheme.TEXT,hover):UiTheme.DISABLED_TEXT;
        MenuScreen.MenuButton.paint(ui,x,y,20,20,MenuScreen.Look.STANDARD,active,isFocused(),hover,motion.pressed());
        ui.glyph(paste?dev.betterlitematica.runtime.UiGlyphArt.Kind.PASTE:dev.betterlitematica.runtime.UiGlyphArt.Kind.COPY,x+3,y+3+motion.pressed()*.5,14,color);
    }
}
