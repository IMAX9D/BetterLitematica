package dev.betterlitematica.fabric;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;
import java.util.function.Supplier;

/** Live ARGB preview and keyboard-accessible picker entry. */
final class ColorSwatch extends ButtonWidget {
    private final Supplier<String> value;
    ColorSwatch(int x,Supplier<String> value,String label,Runnable action){super(x,0,20,20,Text.literal(label),b->action.run(),DEFAULT_NARRATION_SUPPLIER);this.value=value;}
    @Override public void renderButton(DrawContext context,int mouseX,int mouseY,float delta){
        var ui=IndependentUi.INSTANCE;
        for(int row=0;row<4;row++)for(int column=0;column<4;column++)ui.rect(getX()+column*5,getY()+row*5,getX()+column*5+5,getY()+row*5+5,((row+column)&1)==0?UiTheme.SURFACE:UiTheme.SECONDARY);
        String text=value.get().trim();boolean valid=text.matches("[a-fA-F0-9]{8}");
        if(valid)ui.rect(getX(),getY(),getX()+width,getY()+height,(int)Long.parseLong(text,16));
        ui.frame(getX(),getY(),getX()+width,getY()+height,!valid?UiTheme.ERROR:isHovered()||isFocused()?UiTheme.FOCUS:UiTheme.BORDER);
    }
}
