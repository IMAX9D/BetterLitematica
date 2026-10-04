package dev.betterlitematica.fabric;

/** Layout-relative font sizing, rasterized once at its final physical pixel size. */
final class UiTypography {
    private UiTypography(){}
    static int bodyPixels(double scale){return pixels(scale,9.5);}
    static int titlePixels(double scale){return pixels(scale,11.5);}
    private static int pixels(double scale,double size){return Math.max(12,Math.min(64,(int)Math.round(scale*size)));}
}
