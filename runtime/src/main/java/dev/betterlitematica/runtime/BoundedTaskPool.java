package dev.betterlitematica.runtime;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.*;

/** Bounds queued work, running work and results awaiting consumption together. Cancellation never invents a free slot. */
public final class BoundedTaskPool implements AutoCloseable {
    private final ThreadPoolExecutor executor;
    private final Semaphore slots;
    private final int limit;
    private final java.util.Set<Ticket<?>> tickets=ConcurrentHashMap.newKeySet();
    public BoundedTaskPool(int threads,int limit,String name){
        if(threads<1||limit<threads)throw new IllegalArgumentException();
        this.limit=limit;slots=new Semaphore(limit);var number=new AtomicInteger();
        executor=new ThreadPoolExecutor(threads,threads,30,TimeUnit.SECONDS,new ArrayBlockingQueue<>(limit),r->{
            var t=new Thread(r,name+number.incrementAndGet());t.setDaemon(true);t.setPriority(Thread.MIN_PRIORITY);return t;
        });
    }
    public final class Ticket<T> implements Runnable,AutoCloseable {
        private final Function<BooleanSupplier,T> work;private final Consumer<T> discard;
        private final AtomicBoolean released=new AtomicBoolean();
        private volatile boolean cancelled,done;private volatile long started;private T result;private Throwable failure;
        private Ticket(Function<BooleanSupplier,T> work,Consumer<T> discard){this.work=work;this.discard=discard;}
        @Override public void run(){
            started=System.nanoTime();
            try{if(!cancelled){T value=work.apply(()->cancelled);synchronized(this){if(cancelled){if(value!=null)discard.accept(value);}else result=value;}}}
            catch(Throwable problem){synchronized(this){failure=problem;}}
            finally{done=true;if(cancelled)release();}
        }
        public boolean done(){return done;}
        public boolean cancelled(){return cancelled;}
        public long started(){return started;}
        public synchronized Throwable failure(){return failure;}
        /** Ownership transfers to the consumer. Close the ticket after that result has been released. */
        public synchronized T take(){if(!done||cancelled)return null;T value=result;result=null;return value;}
        private void release(){if(released.compareAndSet(false,true)){tickets.remove(this);slots.release();}}
        @Override public void close(){
            try{synchronized(this){cancelled=true;if(result!=null){try{discard.accept(result);}finally{result=null;}}}}
            finally{if(executor.remove(this))done=true;if(done)release();}
        }
    }
    public <T> Ticket<T> submit(Function<BooleanSupplier,T> work,Consumer<T> discard){
        if(!slots.tryAcquire())return null;
        var ticket=new Ticket<T>(work,discard);tickets.add(ticket);
        try{executor.execute(ticket);return ticket;}catch(RejectedExecutionException rejected){ticket.done=true;ticket.close();return null;}
    }
    public int outstanding(){return limit-slots.availablePermits();}
    @Override public void close(){executor.shutdown();for(var ticket:tickets)ticket.close();}
}
