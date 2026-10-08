package dev.betterlitematica.fabric;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;
final class HistoryButton extends UiButton {
    private final boolean redo;private final java.util.function.Supplier<String> binding;
    private final UiMotion motion=new UiMotion();
    HistoryButton(boolean redo,Runnable action,java.util.function.Supplier<String> binding){super(0,0,20,20,Component.literal(redo?"重做":"撤销"),button->action.run(),DEFAULT_NARRATION);this.redo=redo;this.binding=binding;}
    @Override public void onPress(){motion.press();super.onPress();}
    @Override public void renderWidget(GuiGraphicsExtractor context,int mx,int my,float delta){
        var ui=IndependentUi.INSTANCE;int x=getX(),y=getY();
        boolean focused=(isMouseOver(mx,my)||isFocused())&&active;
        double hover=motion.hover(focused);int color=active?UiMotion.mix(UiTheme.SECONDARY,UiTheme.TEXT,hover):UiTheme.DISABLED_TEXT;
        MenuScreen.MenuButton.paint(ui,x,y,20,20,MenuScreen.Look.STANDARD,active,isFocused(),hover,motion.pressed());
        ui.rect(x+6,y+6,x+14,y+8,color);ui.rect(x+(redo?4:14),y+8,x+(redo?6:16),y+14,color);ui.rect(x+7,y+13,x+13,y+15,color);
        int head=x+(redo?13:5);ui.rect(head,y+4,head+2,y+10,color);ui.rect(head+(redo?-2:2),y+3,head+(redo?0:4),y+5,color);ui.rect(head+(redo?-2:2),y+9,head+(redo?0:4),y+11,color);
        if(focused){String keys=binding.get();String hint=(redo?"重做":"撤销")+(keys.isBlank()?"":" · "+keys);double w=ui.measure(hint)+16;MenuScreen.tooltip(ui,x,y-26,w,20);ui.text(hint,x+8,y-26+(20-ui.lineHeight())/2-.5,w-16,UiTheme.TEXT);}
    }
}
