package dev.betterlitematica.fabric;

/** HSV math keeps alpha independent, including RGB values in fully transparent colors. */
final class ColorPickerColor {
    record Hsv(double hue,double saturation,double value,int alpha){}
    private ColorPickerColor(){}
    static double clamp(double value){return Math.max(0,Math.min(1,Double.isFinite(value)?value:0));}
    static Hsv hsv(int argb){
        double r=((argb>>>16)&255)/255d,g=((argb>>>8)&255)/255d,b=(argb&255)/255d;
        double max=Math.max(r,Math.max(g,b)),min=Math.min(r,Math.min(g,b)),delta=max-min,h=0;
        if(delta!=0){if(max==r)h=(g-b)/delta;else if(max==g)h=2+(b-r)/delta;else h=4+(r-g)/delta;h/=6;if(h<0)h++;}
        return new Hsv(h,max==0?0:delta/max,max,argb>>>24);
    }
    static int argb(double hue,double saturation,double value,int alpha){
        hue=Double.isFinite(hue)?hue-Math.floor(hue):0;saturation=clamp(saturation);value=clamp(value);
        double chroma=value*saturation,h=hue*6,x=chroma*(1-Math.abs(h%2-1)),m=value-chroma;
        double r=0,g=0,b=0;
        switch((int)h){case 0->{r=chroma;g=x;}case 1->{r=x;g=chroma;}case 2->{g=chroma;b=x;}case 3->{g=x;b=chroma;}case 4->{r=x;b=chroma;}default->{r=chroma;b=x;}}
        return (Math.max(0,Math.min(255,alpha))<<24)|((int)Math.round((r+m)*255)<<16)|((int)Math.round((g+m)*255)<<8)|(int)Math.round((b+m)*255);
    }
    static double hue(double x,double y){double angle=Math.atan2(-y,x)/(2*Math.PI);return angle-Math.floor(angle);}
    static double saturation(double x,double y){return clamp(Math.hypot(x,y));}
    static int abgr(int argb){return (argb&0xff00ff00)|((argb&255)<<16)|((argb>>>16)&255);}
}
