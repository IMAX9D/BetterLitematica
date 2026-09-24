package dev.betterlitematica.fabric;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;
final class HistoryButton extends ButtonWidget {
    private final boolean redo;private final java.util.function.Supplier<String> binding;
    HistoryButton(boolean redo,Runnable action,java.util.function.Supplier<String> binding){super(0,0,20,20,Text.literal(redo?"重做":"撤销"),button->action.run(),DEFAULT_NARRATION_SUPPLIER);this.redo=redo;this.binding=binding;}
    @Override public void renderButton(DrawContext context,int mx,int my,float delta){
        var ui=IndependentUi.INSTANCE;int x=getX(),y=getY(),color=active?UiTheme.TEXT:UiTheme.DISABLED_TEXT;
        boolean focused=(isMouseOver(mx,my)||isFocused())&&active;
        ui.rect(x,y,x+20,y+20,focused?UiTheme.HOVER:UiTheme.SURFACE);
        ui.frame(x,y,x+20,y+20,focused?UiTheme.FOCUS:UiTheme.DIVIDER);
        ui.rect(x+6,y+6,x+14,y+8,color);ui.rect(x+(redo?4:14),y+8,x+(redo?6:16),y+14,color);ui.rect(x+7,y+13,x+13,y+15,color);
        int head=x+(redo?13:5);ui.rect(head,y+4,head+2,y+10,color);ui.rect(head+(redo?-2:2),y+3,head+(redo?0:4),y+5,color);ui.rect(head+(redo?-2:2),y+9,head+(redo?0:4),y+11,color);
        if(focused){String keys=binding.get();String hint=(redo?"重做":"撤销")+(keys.isBlank()?"":" · "+keys);double w=ui.measure(hint)+12;ui.rect(x,y-24,x+w,y-4,UiTheme.TOOLTIP);ui.text(hint,x+6,y-22,w-12,UiTheme.TEXT);}
    }
}
