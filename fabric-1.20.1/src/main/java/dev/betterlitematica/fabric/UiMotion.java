package dev.betterlitematica.fabric;

/** Time based motion; UI animation is independent of game ticks and frame rate. */
final class UiMotion {
    private long hoverTime, pressTime;
    private double hover;

    double hover(boolean active) {
        long now=System.nanoTime();
        double seconds=hoverTime==0?0:Math.min(.1,Math.max(0,(now-hoverTime)/1e9));
        hoverTime=now;
        hover+=( (active?1:0)-hover)*(1-Math.exp(-seconds*19));
        if(Math.abs(hover-(active?1:0))<.001)hover=active?1:0;
        return hover;
    }
    void press(){pressTime=System.nanoTime();}
    double pressed(){return pressTime==0?0:1-easeOut((System.nanoTime()-pressTime)/180_000_000d);}
    static double easeOut(double t){t=clamp(t);double inverse=1-t;return 1-inverse*inverse*inverse;}
    static double clamp(double value){return Math.max(0,Math.min(1,Double.isFinite(value)?value:0));}
    static int alpha(int color,double opacity){return color&0xffffff|((int)Math.round((color>>>24)*clamp(opacity))<<24);}
    static int mix(int from,int to,double progress){
        double t=clamp(progress);int value=0;
        for(int shift=0;shift<=24;shift+=8){int a=from>>>shift&255,b=to>>>shift&255;value|=(int)Math.round(a+(b-a)*t)<<shift;}
        return value;
    }
}
