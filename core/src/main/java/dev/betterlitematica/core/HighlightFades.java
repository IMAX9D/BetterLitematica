package dev.betterlitematica.core;

/** Stateless presentation curves; never change highlight membership or world observations. */
public final class HighlightFades {
    private HighlightFades(){}
    public static long duration(ActionHighlights.Kind kind,long requested){return kind==ActionHighlights.Kind.PLACE?Math.min(requested,450_000_000L):requested;}
    public static float action(ActionHighlights.Mark mark,long now){
        double remaining=clamp((double)(mark.expires()-now)/(mark.expires()-mark.started()));
        return (float)(mark.kind()==ActionHighlights.Kind.PLACE?smooth(remaining):remaining);
    }
    public static float missing(double distance,double range,double alignment){
        if(range<=0||distance>=range)return 0;
        double near=Math.min(2,range*.4),fade=1-smooth((distance-near)/(range-near));
        // Gaze only adds emphasis. It cannot hide a cell when the player turns away.
        double focus=.65+.35*smooth((alignment-.85)/.13);
        return (float)(.66*fade*(fade+(1-fade)*focus));
    }
    private static double clamp(double value){return Math.max(0,Math.min(1,value));}
    private static double smooth(double value){double t=clamp(value);return t*t*(3-2*t);}
}
