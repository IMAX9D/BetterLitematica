package dev.betterlitematica.fabric;
import net.minecraft.client.gui.screen.Screen;
final class DisplayScreen extends MenuScreen {
    @Override protected boolean isSettingsPage(){return true;}
    DisplayScreen(Screen parent,ProjectionController controller){super("显示","",parent,controller,true);}
    private static void toggle(MenuScreen page,SettingId id,String name,boolean value,int column,int row,Runnable change,Runnable save ){page.setting(id,page.button(name+"："+(value?"开":"关"),column,2,row,()->{change.run();save.run();},true,value));}
    @Override protected void buildMenu(){buildControls(this,controller,0,()->client.setScreen(new LayerScreen(this,controller)),()->{controller.saveOptions();refresh();});}
    static void buildControls(MenuScreen page,ProjectionController controller,int row,Runnable layers,Runnable save){var s=controller.options().display;
        // Interface first, then what is drawn in the world, then block information; no spacer rows.
        page.setting(SettingId.DISPLAY_THEME,page.button("界面主题："+UiTheme.Palette.parse(s.uiTheme).label,0,2,row,()->{var next=UiTheme.Palette.parse(s.uiTheme).next();s.uiTheme=next.name();UiTheme.apply(next);save.run();},true,false));
        page.setting(SettingId.DISPLAY_ERROR_STYLE,page.button("错误样式："+switch(s.errorStyle){case OUTLINE->"轮廓";case FILLED->"填充";case BOTH->"轮廓与填充";},1,2,row,()->{s.errorStyle=PrinterSettings.HighlightStyle.values()[(s.errorStyle.ordinal()+1)%3];save.run();}));
        toggle(page,SettingId.DISPLAY_PROJECTION,"投影方块",s.projection,0,row+1,()->s.projection=!s.projection,save);page.setting(SettingId.DISPLAY_LAYERS,page.button("分层",1,2,row+1,layers));
        toggle(page,SettingId.DISPLAY_PLACEMENT_BOUNDS,"摆放边框",s.placementBounds,0,row+2,()->s.placementBounds=!s.placementBounds,save);toggle(page,SettingId.DISPLAY_REGION_BOUNDS,"子区域边框",s.regionBounds,1,row+2,()->s.regionBounds=!s.regionBounds,save);
        toggle(page,SettingId.DISPLAY_SELECTION_BOUNDS,"选区边框",controller.options().boxes,0,row+3,()->controller.options().boxes=!controller.options().boxes,save);toggle(page,SettingId.DISPLAY_ORIGINS,"原点",s.origins,1,row+3,()->s.origins=!s.origins,save);
        toggle(page,SettingId.DISPLAY_INFORMATION,"方块信息",s.information,0,row+4,()->s.information=!s.information,save);toggle(page,SettingId.DISPLAY_WORLD_INFORMATION,"实际方块信息",s.informationWorld,1,row+4,()->s.informationWorld=!s.informationWorld,save);
        toggle(page,SettingId.DISPLAY_STATES,"方块状态",s.informationStates,0,row+5,()->s.informationStates=!s.informationStates,save);
    }
}
