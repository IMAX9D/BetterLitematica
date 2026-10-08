package dev.betterlitematica.fabric;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;
import java.util.function.Supplier;

/** Live ARGB preview and keyboard-accessible picker entry. */
final class ColorSwatch extends ButtonWidget {
    private final Supplier<String> value;
    private final UiMotion motion=new UiMotion();
    ColorSwatch(int x,Supplier<String> value,String label,Runnable action){super(x,0,20,20,net.minecraft.text.Text.literal(label),b->action.run(),DEFAULT_NARRATION_SUPPLIER);this.value=value;}
    @Override public void drawIcon(DrawContext context,int mouseX,int mouseY,float delta){
        var ui=IndependentUi.INSTANCE;
        ui.roundRect(getX(),getY(),getX()+width,getY()+height,4,UiTheme.INPUT);
        for(int row=0;row<4;row++)for(int column=0;column<4;column++)ui.rect(getX()+2+column*4,getY()+2+row*4,getX()+6+column*4,getY()+6+row*4,((row+column)&1)==0?UiTheme.INPUT:UiTheme.TRACK);
        String text=value.get().trim();boolean valid=text.matches("[a-fA-F0-9]{8}");
        if(valid)ui.roundRect(getX(),getY(),getX()+width,getY()+height,4,(int)Long.parseLong(text,16));
        double hover=motion.hover(isHovered()||isFocused()),press=motion.pressed();
        ui.roundFrame(getX()-hover,getY()-hover,getX()+width+hover,getY()+height+hover,4,!valid?UiTheme.ERROR:UiMotion.mix(UiTheme.BORDER,UiTheme.FOCUS,Math.max(hover,press)));
    }
    @Override public void onPress(net.minecraft.client.input.AbstractInput input){motion.press();super.onPress(input);}
}
