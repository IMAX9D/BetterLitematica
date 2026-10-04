package dev.betterlitematica.fabric;
/** Presentation preferences; no scanner or renderer control is implied by opening a menu. */
final class DisplayOptions {
    boolean projection=true,selection=true,placementBounds,regionBounds,origins,information,informationStates=true,informationWorld=true,errorsThrough;
    PrinterSettings.HighlightStyle errorStyle=PrinterSettings.HighlightStyle.OUTLINE;
    /** Interface palette name; unknown values fall back to Obsidian. */
    String uiTheme=UiTheme.Palette.OBSIDIAN.name();
    int missingColor=0xaa33bfff,extraColor=0xaaff4de6,wrongBlockColor=0xaaff3333,wrongStateColor=0xaaffaa1a;
}
