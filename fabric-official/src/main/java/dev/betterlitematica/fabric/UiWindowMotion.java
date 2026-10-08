package dev.betterlitematica.fabric;

/** One entrance per navigation, never per refresh, tick or window resize. */
final class UiWindowMotion {
    static final long DURATION_NANOS=220_000_000L;
    record Frame(double x,double y,double alpha){}
    private long start;
    private double fromX,fromY;
    private boolean fromMenu;
    void enter(boolean fromMenu,boolean backwards){
        this.fromMenu=fromMenu;
        start=System.nanoTime();fromX=fromMenu?(backwards?-11:11):0;fromY=fromMenu?0:7;
    }
    Frame frame(){return sample(System.nanoTime()-start,fromX,fromY,fromMenu);}
    static Frame sample(long elapsed,double x,double y){return sample(elapsed,x,y,false);}
    static Frame sample(long elapsed,double x,double y,boolean fromMenu){double eased=UiMotion.easeOut(elapsed/(double)DURATION_NANOS);return new Frame(x*(1-eased),y*(1-eased),fromMenu?1:eased);}
}
