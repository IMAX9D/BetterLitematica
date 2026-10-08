package dev.betterlitematica.fabric;
import java.util.List;
/** Tool modes are independent from the printer's composable operation switches. */
enum ToolMode {
    SELECTION("选区",false,true,false,false), PLACEMENT("摆放",false,false,false,false),
    FILL("填充",true,true,true,false), REPLACE("替换",true,true,true,true),
    PASTE("粘贴",true,false,false,false), GRID_PASTE("网格粘贴",true,false,false,false),
    MOVE("移动",true,true,false,false), DELETE("删除",true,true,false,false),
    REBUILD("编辑",false,false,true,false);
    // Keep GRID_PASTE readable in older settings, but never offer the unfinished mode.
    private static final List<ToolMode> CREATIVE_MODES=List.of(SELECTION,PLACEMENT,FILL,REPLACE,PASTE,MOVE,DELETE,REBUILD);
    private static final List<ToolMode> SURVIVAL_MODES=List.of(SELECTION,PLACEMENT,REBUILD);
    private final String label;private final boolean creative,selection,primary,secondary;
    ToolMode(String label,boolean creative,boolean selection,boolean primary,boolean secondary){this.label=label;this.creative=creative;this.selection=selection;this.primary=primary;this.secondary=secondary;}
    String label(){return label;}boolean creativeOnly(){return creative;}boolean selection(){return selection;}boolean primary(){return primary;}boolean secondary(){return secondary;}
    static List<ToolMode> selectable(boolean creative){return creative?CREATIVE_MODES:SURVIVAL_MODES;}
    ToolMode replacement(){return this==GRID_PASTE?PASTE:this;}
    ToolMode usable(boolean creative){var value=replacement();return !creative&&value.creativeOnly()?PLACEMENT:value;}
    ToolMode cycle(int direction,boolean creative){var modes=selectable(creative);int i=modes.indexOf(usable(creative));return modes.get(Math.floorMod(i+Integer.signum(direction),modes.size()));}
}
