package dev.betterlitematica.fabric;

public final class ColorPickerChecks {
    private static int checks;
    private static void check(boolean value,String message){checks++;if(!value)throw new AssertionError(message);}
    public static int run(){checks=0;
        int[] anchors={0x00000000,0xffffffff,0x00ffffff,0x12000000,0xffff0000,0xff00ff00,0xff0000ff,0xff00ffff,0xffff00ff,0xffffff00,0x80808080,0x01234567};
        for(int color:anchors){var hsv=ColorPickerColor.hsv(color);check(ColorPickerColor.argb(hsv.hue(),hsv.saturation(),hsv.value(),hsv.alpha())==color,"ARGB endpoint round trip");}
        var random=new java.util.Random(1844);for(int i=0;i<20000;i++){int color=random.nextInt();var hsv=ColorPickerColor.hsv(color);check(ColorPickerColor.argb(hsv.hue(),hsv.saturation(),hsv.value(),hsv.alpha())==color,"HSV preserves all input RGB and alpha bytes");}
        for(int alpha=0;alpha<=255;alpha++)check((ColorPickerColor.argb(.37,.63,.81,alpha)>>>24)==alpha,"Every opacity byte is reachable withoutRGB coupling");
        check(ColorPickerColor.argb(0,1,1,255)==0xffff0000&&ColorPickerColor.argb(1d/3,1,1,255)==0xff00ff00&&ColorPickerColor.argb(2d/3,1,1,255)==0xff0000ff,"HSV primary hues");
        check(ColorPickerColor.argb(1,1,1,255)==ColorPickerColor.argb(0,1,1,255)&&ColorPickerColor.argb(-1,1,1,255)==0xffff0000,"Hue seam wraps without discontinuity");
        for(int deg=0;deg<360;deg++){double angle=deg*Math.PI/180,x=.71*Math.cos(angle),y=-.71*Math.sin(angle);double hue=ColorPickerColor.hue(x,y),distance=Math.abs(hue-deg/360d);check(Math.min(distance,1-distance)<1e-10,"Wheel marker and pointer use identical orientation");check(Math.abs(ColorPickerColor.saturation(x,y)-.71)<1e-10,"Wheel distance gives saturation");}
        check(ColorPickerColor.saturation(0,0)==0&&ColorPickerColor.saturation(4,-2)==1,"Center desaturates; outside drag clamps to rim");
        check(ColorPickerColor.argb(.4,0,.5,17)==0x11808080,"Saturation zero is gray whilealpha retained");
        check(ColorPickerColor.argb(.8,1,0,133)==0x85000000,"Brightness zero preserves opacity");
        check(ColorPickerColor.argb(Double.NaN,Double.POSITIVE_INFINITY,-1,500)==0xff000000,"Nonfinite and out-of-range inputs stay bounded");
        for(int value:anchors)check(ColorPickerColor.abgr(ColorPickerColor.abgr(value))==value,"NativeImage ABGR swap preserves alpha and green");
        return checks;
    }
    public static void main(String[] args){System.out.println("ColorPickerChecks: "+run()+" checks");}
}
