package dev.betterlitematica.fabric;
/** Presentation preferences; no scanner or renderer control is implied by opening a menu. */
final class DisplayOptions {
    boolean projection=true,selection=true,placementBounds,regionBounds,origins,information,informationStates=true,informationWorld=true,errorsThrough;
    PrinterSettings.HighlightStyle errorStyle=PrinterSettings.HighlightStyle.OUTLINE;
    /** Interface palette name; unknown values fall back to Obsidian. */
    String uiTheme=UiTheme.Palette.OBSIDIAN.name();
    int missingColor=0xaa33bfff,extraColor=0xaaff4de6,wrongBlockColor=0xaaff3333,wrongStateColor=0xaaffaa1a;
    /** Milliseconds per frame for building projection meshes; only spent while sections are missing or stale. */
    int buildBudgetMs=8;
    static final int[] BUILD_BUDGETS={2,4,8,12,16};
    int buildBudget(){for(int value:BUILD_BUDGETS)if(value==buildBudgetMs)return value;return 8;}
    int nextBuildBudget(){int current=buildBudget();for(int i=0;i<BUILD_BUDGETS.length;i++)if(BUILD_BUDGETS[i]==current)return BUILD_BUDGETS[(i+1)%BUILD_BUDGETS.length];return 8;}
}
