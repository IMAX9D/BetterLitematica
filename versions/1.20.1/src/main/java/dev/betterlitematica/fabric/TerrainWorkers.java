package dev.betterlitematica.fabric;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/** Dedicated, bounded-by-builder-count mesh execution; leaves CPU capacity for the game/projection. */
public final class TerrainWorkers {
    private TerrainWorkers(){}
    private static final AtomicInteger THREAD_IDS=new AtomicInteger();
    public static int parallelism(){return Math.max(1,Math.min(6,Runtime.getRuntime().availableProcessors()/3));}
    public static ExecutorService create(){
        int count=parallelism();
        var pool=new ThreadPoolExecutor(count,count,30,TimeUnit.SECONDS,new LinkedBlockingQueue<>(),work->{
            var thread=new Thread(work,"BetterLitematica-Terrain-"+THREAD_IDS.incrementAndGet());
            thread.setDaemon(true);thread.setPriority(Math.max(Thread.MIN_PRIORITY,Thread.NORM_PRIORITY-1));return thread;
        });
        pool.allowCoreThreadTimeOut(true);return pool;
    }
}
