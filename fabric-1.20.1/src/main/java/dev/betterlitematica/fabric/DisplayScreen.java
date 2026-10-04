package dev.betterlitematica.fabric;
import net.minecraft.client.gui.screen.Screen;
final class DisplayScreen extends MenuScreen {
    DisplayScreen(Screen parent,ProjectionController controller){super("显示","",parent,controller,true);}
    private static void toggle(MenuScreen page,String name,boolean value,int column,int row,Runnable change,Runnable save){page.button(name+"："+(value?"开":"关"),column,2,row,()->{change.run();save.run();},true,value);}
    @Override protected void buildMenu(){buildControls(this,controller,0,()->client.setScreen(new LayerScreen(this,controller)),()->{controller.saveOptions();refresh();});}
    static void buildControls(MenuScreen page,ProjectionController controller,int row,Runnable layers,Runnable save){var s=controller.options().display;
        toggle(page,"投影方块",s.projection,0,row,()->s.projection=!s.projection,save);toggle(page,"选区边框",controller.options().boxes,1,row,()->controller.options().boxes=!controller.options().boxes,save);
        toggle(page,"摆放边框",s.placementBounds,0,row+1,()->s.placementBounds=!s.placementBounds,save);toggle(page,"子区域边框",s.regionBounds,1,row+1,()->s.regionBounds=!s.regionBounds,save);
        toggle(page,"原点",s.origins,0,row+2,()->s.origins=!s.origins,save);page.button("分层",1,2,row+2,layers);
        toggle(page,"方块信息",s.information,0,row+4,()->s.information=!s.information,save);toggle(page,"实际方块信息",s.informationWorld,1,row+4,()->s.informationWorld=!s.informationWorld,save);
        toggle(page,"方块状态",s.informationStates,0,row+5,()->s.informationStates=!s.informationStates,save);
        page.button("错误样式："+switch(s.errorStyle){case OUTLINE->"轮廓";case FILLED->"填充";case BOTH->"轮廓与填充";},0,2,row+6,()->{s.errorStyle=PrinterSettings.HighlightStyle.values()[(s.errorStyle.ordinal()+1)%3];save.run();});
    }
}
