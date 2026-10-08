package dev.betterlitematica.fabric;

import java.util.Locale;

/** Aggregated local log only; no per-block logging, UI text, world writes or unbounded history. */
final class PrinterDiagnostics {
    long scanNanos,planNanos,equipNanos,sendNanos;
    int attempts,stale,budgetStops,discovered,unknown;
    private long window,started,workNanos;
    private int ticks,sent,accepted,waits,retries,unsupported;
    void begin(){long now=System.nanoTime();if(window==0)window=now;started=now;ticks++;}
    void outcome(PrinterActions.Outcome result,boolean dispatched){
        if(dispatched)sent++;
        switch(result){case SENT->accepted++;case WAIT->waits++;case RETRY->retries++;case STALE->stale++;case UNSUPPORTED,MISSING->unsupported++;}
    }
    void finish(PrinterSettings settings,int queued,String reason){
        if(started==0)return;long now=System.nanoTime();workNanos+=now-started;started=0;
        if(now-window<5_000_000_000L)return;
        BetterLitematicaClient.LOGGER.info(String.format(Locale.ROOT,
            "Printer profile: ticks=%d discovered=%d unknown=%d attempts=%d sent=%d accepted=%d stale=%d retry=%d wait=%d unavailable=%d budgetStops=%d queued=%d pacing=%d/%d/%d budget=%dms work=%.2fms scan=%.2fms plan=%.2fms equip=%.2fms interact=%.2fms reason=%s",
            ticks,discovered,unknown,attempts,sent,accepted,stale,retries,waits,unsupported,budgetStops,queued,settings.interval,settings.perTick,settings.cooldown,settings.workBudgetMillis,
            workNanos/1e6,scanNanos/1e6,planNanos/1e6,equipNanos/1e6,sendNanos/1e6,reason));
        reset();
    }
    void reset(){window=started=workNanos=scanNanos=planNanos=equipNanos=sendNanos=0;ticks=sent=accepted=waits=retries=unsupported=attempts=stale=budgetStops=discovered=unknown=0;}
}
