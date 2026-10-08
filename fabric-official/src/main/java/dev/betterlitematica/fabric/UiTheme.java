package dev.betterlitematica.fabric;

/**
 * Two complete palettes behind one set of semantic tokens. Tokens are read every frame, so switching
 * palettes restyles every screen, HUD card and the wheel at once. World highlights never read these.
 *
 * Obsidian: graphite glass, warm ivory type and a single champagne accent.
 * Porcelain: warm ivory paper, deep ink type and a single indigo accent.
 */
final class UiTheme {
    private UiTheme() {}
    enum Palette {
        OBSIDIAN("曜石"), PORCELAIN("瓷白");
        final String label;
        Palette(String label){this.label=label;}
        Palette next(){return values()[(ordinal()+1)%values().length];}
        static Palette parse(String value){
            if(value!=null)for(var palette:values())if(palette.name().equalsIgnoreCase(value))return palette;
            return OBSIDIAN;
        }
    }

    /** Settled opaque colours; translucency is applied only where a token says so. */
    static int BACKDROP, SCRIM;
    static int PANEL, HEADER, FOOTER, SUNKEN, INPUT, TOOLTIP;
    static int OVERLAY_PANEL, OVERLAY_SURFACE, OVERLAY_SELECTED;
    static int SURFACE, HOVER, SELECTED, PRESSED;
    static int BORDER, BORDER_STRONG, DIVIDER, TRACK, THUMB, SHEEN;
    static int TEXT, SECONDARY, MUTED, DISABLED, DISABLED_TEXT;
    static int ACCENT, ACCENT_HOVER, FOCUS, ON_ACCENT, ACCENT_SOFT, SELECTION, RING;
    static int WARNING, SUCCESS, ERROR;
    static int SHADOW, SHADOW_AMBIENT;
    static boolean DARK;

    static final double BUTTON_RADIUS=6, CARD_RADIUS=9, PANEL_RADIUS=14, CONTROL_HEIGHT=20;

    private static Palette current;
    static { apply(Palette.OBSIDIAN); }

    static Palette current(){return current;}

    static void apply(Palette palette){
        if(palette==null)palette=Palette.OBSIDIAN;
        current=palette;
        if(palette==Palette.OBSIDIAN)obsidian();else porcelain();
    }

    private static void obsidian(){
        DARK=true;
        BACKDROP=0x9a05070b; SCRIM=0x66000000;
        PANEL=0xff15161a; HEADER=0xff15161a; FOOTER=0xff111215; SUNKEN=0xff101114; INPUT=0xff0e0f12; TOOLTIP=0xff202228;
        OVERLAY_PANEL=0xe6141519; OVERLAY_SURFACE=0xdc1c1e24; OVERLAY_SELECTED=0xe62a2820;
        SURFACE=0xff1e2026; HOVER=0xff272a31; SELECTED=0xff2b2822; PRESSED=0xff35312a;
        BORDER=0xff2e3139; BORDER_STRONG=0xff3d414b; DIVIDER=0xff24262c; TRACK=0xff23252b; THUMB=0xff4a4e58; SHEEN=0x14ffffff;
        TEXT=0xffeeebe5; SECONDARY=0xffa9acb4; MUTED=0xff8b8f99; DISABLED=0xff18191d; DISABLED_TEXT=0xff595d66;
        ACCENT=0xffd6b98a; ACCENT_HOVER=0xffe4cba2; FOCUS=0xffe8cfa4; ON_ACCENT=0xff1b1610; ACCENT_SOFT=0x33d6b98a; SELECTION=0xff3d3426; RING=0x55d6b98a;
        WARNING=0xffe7b660; SUCCESS=0xff74d3a0; ERROR=0xfff08a86;
        SHADOW=0x52000000; SHADOW_AMBIENT=0x14000000;
    }

    private static void porcelain(){
        DARK=false;
        BACKDROP=0x5c1a1d24; SCRIM=0x33000000;
        PANEL=0xfffbfaf7; HEADER=0xfffbfaf7; FOOTER=0xfff4f2ed; SUNKEN=0xfff3f1ec; INPUT=0xffffffff; TOOLTIP=0xffffffff;
        OVERLAY_PANEL=0xebfbfaf7; OVERLAY_SURFACE=0xe2f1efea; OVERLAY_SELECTED=0xebe5e8f6;
        SURFACE=0xffefede8; HOVER=0xffe6e3dc; SELECTED=0xffe6e9f6; PRESSED=0xffd9ddf0;
        BORDER=0xffdcd8cf; BORDER_STRONG=0xffc9c4b9; DIVIDER=0xffe8e5de; TRACK=0xffe8e5de; THUMB=0xffb9b4a9; SHEEN=0x00ffffff;
        TEXT=0xff1c1e24; SECONDARY=0xff50545e; MUTED=0xff676b75; DISABLED=0xfff3f1ed; DISABLED_TEXT=0xffa3a29c;
        ACCENT=0xff3b4aab; ACCENT_HOVER=0xff4a59bb; FOCUS=0xff2f3c93; ON_ACCENT=0xffffffff; ACCENT_SOFT=0x223b4aab; SELECTION=0xffd2d8f2; RING=0x403b4aab;
        WARNING=0xff8a5a0e; SUCCESS=0xff1d6a4b; ERROR=0xffb2343f;
        SHADOW=0x1c262a36; SHADOW_AMBIENT=0x0a262a36;
    }
}
