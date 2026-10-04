package dev.betterlitematica.fabric;
/** Tool modes are independent from the printer's composable operation switches. */
enum ToolMode {
    SELECTION("选区",false,true,false,false), PLACEMENT("摆放",false,false,false,false),
    FILL("填充",true,true,true,false), REPLACE("替换",true,true,true,true),
    PASTE("粘贴",true,false,false,false), GRID_PASTE("网格粘贴",true,false,false,false),
    MOVE("移动",true,true,false,false), DELETE("删除",true,true,false,false),
    REBUILD("编辑",false,false,true,false);
    private final String label;private final boolean creative,selection,primary,secondary;
    ToolMode(String label,boolean creative,boolean selection,boolean primary,boolean secondary){this.label=label;this.creative=creative;this.selection=selection;this.primary=primary;this.secondary=secondary;}
    String label(){return label;}boolean creativeOnly(){return creative;}boolean selection(){return selection;}boolean primary(){return primary;}boolean secondary(){return secondary;}
    ToolMode cycle(int direction,boolean creative){var modes=values();int i=ordinal();do{i=Math.floorMod(i+direction,modes.length);}while(!creative&&modes[i].creativeOnly());return modes[i];}
}
