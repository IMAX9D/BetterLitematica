package dev.betterlitematica.fabric;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;

final class ClipboardIconButton extends ButtonWidget {
    private final boolean paste;
    private final UiMotion motion=new UiMotion();
    ClipboardIconButton(int x,boolean paste,Runnable action){super(x,0,20,20,Text.literal(paste?"粘贴":"复制"),b->action.run(),DEFAULT_NARRATION_SUPPLIER);this.paste=paste;}
    @Override public void onPress(){motion.press();super.onPress();}
    @Override public void renderButton(DrawContext ctx,int mx,int my,float delta){
        var ui=IndependentUi.INSTANCE;int x=getX(),y=getY(),color=active?UiTheme.TEXT:UiTheme.DISABLED_TEXT;
        boolean focused=(isMouseOver(mx,my)||isFocused())&&active;
        double hover=motion.hover(focused);int fill=active?UiMotion.mix(UiTheme.SURFACE,UiTheme.HOVER,hover):UiTheme.DISABLED;fill=UiMotion.mix(fill,UiTheme.SELECTED,motion.pressed()*.45);
        ui.roundRect(x,y,x+20,y+20,6,fill);
        ui.roundFrame(x,y,x+20,y+20,6,UiMotion.mix(UiTheme.BORDER,UiTheme.FOCUS,hover));
        if(paste){outline(x+5,y+5,10,12,color);ui.rect(x+8,y+3,x+12,y+7,color);ui.rect(x+8,y+9,x+13,y+10,color);ui.rect(x+8,y+12,x+13,y+13,color);}
        else{outline(x+4,y+4,9,10,color);ui.roundRect(x+7,y+7,x+16,y+17,1.5,fill);outline(x+7,y+7,9,10,color);}
    }
    private void outline(int x,int y,int w,int h,int color){IndependentUi.INSTANCE.roundFrame(x,y,x+w,y+h,1.5,color);}
}
