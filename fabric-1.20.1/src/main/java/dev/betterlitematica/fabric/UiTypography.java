package dev.betterlitematica.fabric;

import dev.betterlitematica.runtime.OutlineFont;

/** Layout-relative font sizing, rasterized once at its final physical pixel size. */
final class UiTypography {
    private UiTypography(){}
    static int bodyPixels(double scale,OutlineFont font){return pixels(scale,9.5,14,font);}
    static int titlePixels(double scale,OutlineFont font){return pixels(scale,11.5,16,font);}
    private static int pixels(double scale,double size,double lineLimit,OutlineFont font){
        int pixels=Math.max(12,Math.min(64,(int)Math.round(scale*size)));
        while(pixels>10&&font.lineHeight(pixels)>lineLimit*scale)pixels--;
        return pixels;
    }
}
