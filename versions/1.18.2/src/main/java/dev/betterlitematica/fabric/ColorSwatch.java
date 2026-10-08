package dev.betterlitematica.fabric;

import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;
import java.util.function.Supplier;

/** Live ARGB preview and keyboard-accessible picker entry. */
final class ColorSwatch extends ButtonWidget {
    public int getX(){return x;} public int getY(){return y;} public void setX(int value){x=value;} public void setY(int value){y=value;}

    private final Supplier<String> value;
    private final UiMotion motion=new UiMotion();
    ColorSwatch(int x,Supplier<String> value,String label,Runnable action){super(x,0,20,20,new net.minecraft.text.LiteralText(label),b->action.run(),EMPTY);this.value=value;}
    @Override public void renderButton(MatrixStack legacyMatrices,int mouseX,int mouseY,float delta){LegacyGuiContext context=new LegacyGuiContext(legacyMatrices);
        var ui=IndependentUi.INSTANCE;
        ui.roundRect(getX(),getY(),getX()+width,getY()+height,4,UiTheme.INPUT);
        for(int row=0;row<4;row++)for(int column=0;column<4;column++)ui.rect(getX()+2+column*4,getY()+2+row*4,getX()+6+column*4,getY()+6+row*4,((row+column)&1)==0?UiTheme.INPUT:UiTheme.TRACK);
        String text=value.get().trim();boolean valid=text.matches("[a-fA-F0-9]{8}");
        if(valid)ui.roundRect(getX(),getY(),getX()+width,getY()+height,4,(int)Long.parseLong(text,16));
        double hover=motion.hover(isHovered()||isFocused()),press=motion.pressed();
        ui.roundFrame(getX()-hover,getY()-hover,getX()+width+hover,getY()+height+hover,4,!valid?UiTheme.ERROR:UiMotion.mix(UiTheme.BORDER,UiTheme.FOCUS,Math.max(hover,press)));
    }
    @Override public void onPress(){motion.press();super.onPress();}
}
