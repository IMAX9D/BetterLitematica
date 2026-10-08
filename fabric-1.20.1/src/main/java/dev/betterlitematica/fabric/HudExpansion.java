package dev.betterlitematica.fabric;

/** Retargetable cubic ease-out; changing counts does not restart a settled card. */
final class HudExpansion {
    private double from,value,target;
    private long started;
    double height(double next,long now){
        value=from+(target-from)*UiMotion.easeOut((now-started)/220_000_000d);
        if(next!=target){from=value;target=next;started=now;}
        return value;
    }
    static int rows(int size){return (size+3)/4;}
}
