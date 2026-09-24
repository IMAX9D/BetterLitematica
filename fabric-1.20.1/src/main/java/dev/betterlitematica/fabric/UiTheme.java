package dev.betterlitematica.fabric;

/** Shared neutral palette for the independent overlay, never world rendering. */
final class UiTheme {
    private UiTheme() {}
    static final int BACKDROP=0xb809090b, PANEL=0xff18191b, INPUT=0xff121315;
    static final int SURFACE=0xff242628, SELECTED=0xff34332f, HOVER=0xff323538;
    static final int BORDER=0xff3c3f42, DIVIDER=0xff303235, TRACK=0xff292c2f;
    static final int TEXT=0xffeeede9, SECONDARY=0xffb5b6b7, MUTED=0xff929698;
    static final int ACCENT=0xffcebb98, FOCUS=0xffe5d8bd, DISABLED=0xff202224;
    static final int DISABLED_TEXT=0xff85898d, SELECTION=0xff5b554a, TOOLTIP=0xff101113;
    static final int WARNING=0xffd4b183, SUCCESS=0xffa5bba5, ERROR=0xffdfaaa4;
}
