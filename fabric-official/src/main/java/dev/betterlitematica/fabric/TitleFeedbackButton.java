package dev.betterlitematica.fabric;

import dev.betterlitematica.core.UiViewport;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;

/** A title-screen entry using the mod's own font, theme and physical-pixel scale. */
final class TitleFeedbackButton extends UiButton {
    private final UiMotion motion=new UiMotion();
    TitleFeedbackButton(int x,int y,int width,int height,Runnable action){super(x,y,width,height,Component.literal("问题反馈"),button->action.run(),DEFAULT_NARRATION);}
    @Override public void renderWidget(GuiGraphicsExtractor context,int mouseX,int mouseY,float delta){
        var window=Minecraft.getInstance().getWindow();var viewport=UiViewport.fit(window.getWidth(),window.getHeight(),window.getGuiScaledWidth(),window.getGuiScaledHeight(),window.getGuiScale());
        var ui=IndependentUi.INSTANCE;if(!ui.beginMenu(context,viewport))return;
        try{
            double x=viewport.inputX(getX()),y=viewport.inputY(getY()),w=width/viewport.drawScale(),h=height/viewport.drawScale();
            MenuScreen.MenuButton.paint(ui,x,y,w,h,MenuScreen.Look.STANDARD,active,isFocused(),motion.hover(isHovered()||isFocused()),motion.pressed());
            ui.centered("问题反馈",x,y,w,h,UiTheme.TEXT);
        }finally{ui.end();}
    }
}
