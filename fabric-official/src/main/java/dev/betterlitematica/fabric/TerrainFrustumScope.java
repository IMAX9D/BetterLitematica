package dev.betterlitematica.fabric;

public final class TerrainFrustumScope {
    private TerrainFrustumScope(){}
    private static final ThreadLocal<Integer> DEPTH=ThreadLocal.withInitial(()->0);
    public static void begin(){DEPTH.set(DEPTH.get()+1);} public static void end(){DEPTH.set(DEPTH.get()-1);}
    public static float margin(){return DEPTH.get()>0?4.125f:0;}
}
