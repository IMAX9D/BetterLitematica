package dev.betterlitematica.fabric;

import dev.betterlitematica.core.UiViewport;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;

/** A title-screen entry using the mod's own font, theme and physical-pixel scale. */
final class TitleFeedbackButton extends ButtonWidget {
    private final UiMotion motion=new UiMotion();
    TitleFeedbackButton(int x,int y,int width,int height,Runnable action){super(x,y,width,height,Text.literal("问题反馈"),button->action.run(),DEFAULT_NARRATION_SUPPLIER);}
    @Override public void renderWidget(DrawContext context,int mouseX,int mouseY,float delta){
        var window=MinecraftClient.getInstance().getWindow();var viewport=UiViewport.fit(window.getFramebufferWidth(),window.getFramebufferHeight(),window.getScaledWidth(),window.getScaledHeight(),window.getScaleFactor());
        var ui=IndependentUi.INSTANCE;if(!ui.beginMenu(context,viewport))return;
        try{
            double x=viewport.inputX(getX()),y=viewport.inputY(getY()),w=width/viewport.drawScale(),h=height/viewport.drawScale();
            MenuScreen.MenuButton.paint(ui,x,y,w,h,MenuScreen.Look.STANDARD,active,isFocused(),motion.hover(isHovered()||isFocused()),motion.pressed());
            ui.centered("问题反馈",x,y,w,h,UiTheme.TEXT);
        }finally{ui.end();}
    }
}
