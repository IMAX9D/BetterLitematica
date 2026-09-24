package dev.betterlitematica.fabric;
import net.minecraft.client.gui.screen.Screen;
final class DisplayScreen extends MenuScreen {
    DisplayScreen(Screen parent,ProjectionController controller){super("显示","",parent,controller,true);}
    private void toggle(String name,boolean value,int column,int row,Runnable change){button(name+"："+(value?"开":"关"),column,2,row,()->{change.run();controller.saveOptions();refresh();},true,value);}
    @Override protected void buildMenu(){var s=controller.options().display;
        toggle("投影方块",s.projection,0,0,()->s.projection=!s.projection);toggle("选区边框",controller.options().boxes,1,0,()->controller.options().boxes=!controller.options().boxes);
        toggle("摆放边框",s.placementBounds,0,1,()->s.placementBounds=!s.placementBounds);toggle("子区域边框",s.regionBounds,1,1,()->s.regionBounds=!s.regionBounds);
        toggle("原点",s.origins,0,2,()->s.origins=!s.origins);button("分层",1,2,2,()->client.setScreen(new LayerScreen(this,controller)));
        toggle("方块信息",s.information,0,4,()->s.information=!s.information);toggle("实际方块信息",s.informationWorld,1,4,()->s.informationWorld=!s.informationWorld);
        toggle("方块状态",s.informationStates,0,5,()->s.informationStates=!s.informationStates);
        button("错误样式："+switch(s.errorStyle){case OUTLINE->"轮廓";case FILLED->"填充";case BOTH->"轮廓与填充";},0,2,6,()->{s.errorStyle=PrinterSettings.HighlightStyle.values()[(s.errorStyle.ordinal()+1)%3];controller.saveOptions();refresh();});
    }
}
