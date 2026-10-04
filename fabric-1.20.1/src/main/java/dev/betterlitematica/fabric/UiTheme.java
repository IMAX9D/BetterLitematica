package dev.betterlitematica.fabric;

/** Porcelain surfaces and ink text. These tokens never affect world highlights. */
final class UiTheme {
    private UiTheme() {}
    static final int BACKDROP=0x54232b3e, PANEL=0xfff7f8fa, INPUT=0xffffffff;
    static final int OVERLAY_PANEL=0xdff7f8fa, OVERLAY_SURFACE=0xdcebefF5, OVERLAY_SELECTED=0xe5e3e8fa;
    static final int SURFACE=0xffeceff4, SELECTED=0xffe2e7fb, HOVER=0xffe1e6f0;
    static final int BORDER=0xffd4dae4, DIVIDER=0xffe1e5ec, TRACK=0xffe4e8ef;
    static final int TEXT=0xff242b39, SECONDARY=0xff535f73, MUTED=0xff637087;
    static final int ACCENT=0xff596cca, FOCUS=0xff4056b4, DISABLED=0xfff0f2f5;
    static final int DISABLED_TEXT=0xff939cac, SELECTION=0xffced8f6, TOOLTIP=0xfffdfdff;
    static final int WARNING=0xff865815, SUCCESS=0xff22694f, ERROR=0xffad424f;
    static final int SHADOW=0x12232b3e;
    static final double BUTTON_RADIUS=5, CARD_RADIUS=7, PANEL_RADIUS=11;
}
