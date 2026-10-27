package dev.betterlitematica.fabric;

import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicInteger;

/** Scope only the native terrain upload pass. Engine pages, sorting and backend fences stay owned
 * by the engine; pending native staging entries survive the end of a budgeted pass. */
public final class TerrainNativeBudget {
    private TerrainNativeBudget(){}
    public static boolean active;
    public static volatile boolean visibleHoles;
    private static long deadline,remaining;
    public static long uploadedBytes,uploadPasses,graphWalks,graphVisits,cacheReuses;
    private static final AtomicLong REVISION=new AtomicLong();
    private static final AtomicInteger WRITERS=new AtomicInteger();
    public static void begin(){active=true;deadline=System.nanoTime()+(visibleHoles?6_000_000:2_000_000);remaining=visibleHoles?8L<<20:4L<<20;uploadPasses++;}
    public static void end(){active=false;}
    public static boolean available(){return remaining>0&&System.nanoTime()<deadline;}
    public static long allowance(){return Math.min(remaining,visibleHoles?2L<<20:512L<<10);}
    public static void consume(long bytes){remaining-=bytes;uploadedBytes+=bytes;}
    public static void changing(){WRITERS.incrementAndGet();REVISION.incrementAndGet();}
    public static void changed(){REVISION.incrementAndGet();WRITERS.decrementAndGet();}
    public static long revision(){if(WRITERS.get()!=0)return Long.MIN_VALUE;long r=REVISION.get();return WRITERS.get()==0?r:Long.MIN_VALUE;}
}
