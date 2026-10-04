package dev.betterlitematica.fabric;

/** Deterministic production animation sampling and actual small-text color pair contracts. */
public final class UiDesignChecks {
    private static int checks;
    private static void check(boolean value,String message){checks++;if(!value)throw new AssertionError(message);}
    private static void near(double actual,double expected,String message){check(Math.abs(actual-expected)<1e-10,message);}

    public static int run(){checks=0;
        var font=dev.betterlitematica.runtime.OutlineFont.system();
        for(int[] size:new int[][]{{960,540},{1280,720},{1440,900},{1920,1080},{2560,1440}}){
            var view=dev.betterlitematica.core.UiViewport.fit(size[0],size[1],size[0],size[1],1);
            check(font.lineHeight(UiTypography.bodyPixels(view.scale(),font))/view.scale()<=14,"Input captions fit above their fields at "+size[0]+"x"+size[1]);
            check(font.lineHeight(UiTypography.titlePixels(view.scale(),font))/view.scale()<=16,"Titles fit before the divider at "+size[0]+"x"+size[1]);
            int requestedBody=Math.max(12,Math.min(64,(int)Math.round(view.scale()*9.5)));
            int requestedTitle=Math.max(12,Math.min(64,(int)Math.round(view.scale()*11.5)));
            check(font.lineHeight(requestedBody)>14*view.scale()||UiTypography.bodyPixels(view.scale(),font)==requestedBody,"Already fitting body text retains its original size");
            check(font.lineHeight(requestedTitle)>16*view.scale()||UiTypography.titlePixels(view.scale(),font)==requestedTitle,"Already fitting titles retain their original size");
            for(int guiScale:new int[]{2,3,4}){
                var scaled=dev.betterlitematica.core.UiViewport.fit(size[0],size[1],size[0]/guiScale,size[1]/guiScale,guiScale);
                check(UiTypography.bodyPixels(scaled.scale(),font)==UiTypography.bodyPixels(view.scale(),font)&&UiTypography.titlePixels(scaled.scale(),font)==UiTypography.titlePixels(view.scale(),font),"Font fitting is independent of Minecraft GUI scale");
            }
        }
        for(int pixels:new int[]{10,11}){
            var raster=font.raster("投影Ag",pixels,0xffffffff);
            check(java.util.Arrays.stream(raster.argb()).anyMatch(color->(color>>>24)!=0),"Small fallback font produces visible glyphs");
            check(raster.height()>=font.lineHeight(pixels)&&raster.bytes()<=dev.betterlitematica.runtime.OutlineFont.MAX_PIXELS*4L,"Small fallback raster retains line space and pixel budget");
        }
        for(int pixels:new int[]{9,65}){
            boolean rejected=false;try{font.raster("Ag",pixels,0xffffffff);}catch(IllegalArgumentException expected){rejected=true;}
            check(rejected,"Out-of-budget font size remains rejected");
        }
        near(UiMotion.easeOut(-1),0,"Entrance is held at its initial frame before start");
        near(UiMotion.easeOut(0),0,"Entrance starts exactly at zero");
        near(UiMotion.easeOut(1),1,"Entrance completes exactly at one");
        near(UiMotion.easeOut(4),1,"Late frames remain completed without overshoot");
        double previous=0,previousIncrement=1;
        for(int i=1;i<=20;i++){
            double t=i/20d,value=UiMotion.easeOut(t),increment=value-previous;
            check(value>=previous&&value<=1,"Ease-out remains monotonic and bounded");
            check(increment<=previousIncrement+1e-12,"Ease-out slows towards rest instead of linear movement");
            check(value>=t,"Ease-out front-loads movement so controls settle quickly");
            previous=value;previousIncrement=increment;
        }
        check(Double.isFinite(UiMotion.easeOut(Double.NaN)),"Invalid progress cannot poison rendering coordinates");

        for(double[] direction:new double[][]{{11,0},{-11,0},{0,7}}){
            var start=UiWindowMotion.sample(0,direction[0],direction[1]);near(start.x(),direction[0],"Navigation keeps its requested initial X");near(start.y(),direction[1],"Navigation keeps its requested initial Y");near(start.alpha(),0,"Navigation starts transparent");
            var backwardsTime=UiWindowMotion.sample(-1,direction[0],direction[1]);check(backwardsTime.equals(start),"Clock-before-start samples stay at the initial frame");
            var middle=UiWindowMotion.sample(UiWindowMotion.DURATION_NANOS/2,direction[0],direction[1]);
            check(middle.alpha()>.5&&middle.alpha()<1,"Window opacity uses non-linear production easing");
            check(Math.hypot(middle.x(),middle.y())<Math.hypot(start.x(),start.y())/2,"Window translation uses the same settled timing as opacity");
            var end=UiWindowMotion.sample(UiWindowMotion.DURATION_NANOS,direction[0],direction[1]);
            near(end.x(),0,"Finished window has no X hit-test displacement");near(end.y(),0,"Finished window has no Y hit-test displacement");near(end.alpha(),1,"Finished window is fully opaque");
            check(UiWindowMotion.sample(Long.MAX_VALUE,direction[0],direction[1]).equals(end),"Idle windows remain exactly at their final frame");
        }

        int translucent=0x80406ab5;
        check(UiMotion.alpha(translucent,1)==translucent,"Opacity one preserves source alpha and RGB");
        check(UiMotion.alpha(translucent,0)==0x00406ab5,"Opacity zero clears only alpha");
        check(UiMotion.alpha(translucent,.5)==0x40406ab5,"Transition alpha multiplies existing transparency");
        check(UiMotion.alpha(translucent,-3)==0x00406ab5&&UiMotion.alpha(translucent,3)==translucent,"Out-of-range opacity cannot wrap color channels");
        check(UiMotion.alpha(0x00406ab5,.8)==0x00406ab5,"Invisible colors never become visible during transitions");
        check(UiMotion.mix(0x10406080,0xf0a0c0e0,0)==0x10406080&&UiMotion.mix(0x10406080,0xf0a0c0e0,1)==0xf0a0c0e0,"Hover interpolation keeps exact endpoint colors");
        check(UiMotion.mix(0x10406080,0xf0a0c0e0,.5)==0x807090b0,"Hover interpolation includes alpha without channel carry");

        // These are actual painted combinations, including hover and text selection.
        for(int background:new int[]{UiTheme.PANEL,UiTheme.INPUT,UiTheme.TOOLTIP,UiTheme.SURFACE,UiTheme.SELECTED,UiTheme.HOVER,UiTheme.SELECTION}){
            contrast(UiTheme.TEXT,background,"Primary text");
            contrast(UiTheme.SECONDARY,background,"Secondary text");
        }
        contrast(UiTheme.MUTED,UiTheme.PANEL,"Footer status");
        contrast(UiTheme.FOCUS,UiTheme.SELECTED,"Primary button label");
        contrast(UiTheme.FOCUS,UiTheme.HOVER,"Hovered primary button label");
        for(int background:new int[]{UiTheme.PANEL,UiTheme.SURFACE,UiTheme.HOVER}){
            contrast(UiTheme.WARNING,background,"Material shortage text");
            contrast(UiTheme.SUCCESS,background,"Completed material text");
            contrast(UiTheme.ERROR,background,"Error text");
        }
        return checks;
    }
    private static void contrast(int foreground,int background,String name){
        double a=luminance(foreground),b=luminance(background),ratio=(Math.max(a,b)+.05)/(Math.min(a,b)+.05);
        check((foreground>>>24)==255&&(background>>>24)==255,"Contrast contract uses opaque settled colors");
        check(ratio>=4.5,name+" contrast below 4.5: "+ratio+" on "+Integer.toHexString(background));
    }
    private static double luminance(int color){return .2126*linear(color>>>16&255)+.7152*linear(color>>>8&255)+.0722*linear(color&255);}
    private static double linear(int value){double s=value/255d;return s<=.04045?s/12.92:Math.pow((s+.055)/1.055,2.4);}
}
