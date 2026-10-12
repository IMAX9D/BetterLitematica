package dev.betterlitematica.selftest;
import dev.betterlitematica.runtime.BoundedTaskPool;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
public final class BoundedTaskChecks {
    private static int checks;
    private static void check(boolean value,String message){checks++;if(!value)throw new AssertionError(message);}
    private static void await(CountDownLatch latch){try{if(!latch.await(3,TimeUnit.SECONDS))throw new AssertionError("Task timed out");}catch(InterruptedException e){Thread.currentThread().interrupt();throw new AssertionError(e);}}
    private static void until(BooleanSupplier value){long end=System.nanoTime()+3_000_000_000L;while(!value.getAsBoolean()){if(System.nanoTime()>end)throw new AssertionError("Task did not settle");java.util.concurrent.locks.LockSupport.parkNanos(100_000);}}
    public static int run(){
        checks=0;var entered=new CountDownLatch(1);var release=new CountDownLatch(1);var disposed=new AtomicInteger();
        try(var pool=new BoundedTaskPool(1,2,"bounded-check-")){
            var running=pool.submit(cancelled->{entered.countDown();await(release);return 7;},v->disposed.incrementAndGet());await(entered);
            var queued=pool.submit(cancelled->{throw new AssertionError("Cancelled queued work ran");},v->{});
            check(pool.submit(cancelled->9,v->{})==null,"The whole queue has a hard bound");
            running.close();check(pool.outstanding()==2,"Cancelling active work cannot free its slot early");
            queued.close();check(pool.outstanding()==1,"Queued cancellation removes the actual runnable");
            release.countDown();until(()->pool.outstanding()==0);check(disposed.get()==1,"Cancelled results are disposed exactly once");
            var completed=pool.submit(cancelled->11,v->disposed.incrementAndGet());until(completed::done);
            check(pool.outstanding()==1,"Finished results still occupy their global slot");
            check(completed.take()==11&&completed.take()==null,"Result ownership transfers only once");
            completed.close();completed.close();check(pool.outstanding()==0&&disposed.get()==1,"Consumed output is not freed by the pool");
            for(int i=0;i<100;i++){var t=pool.submit(cancelled->1,v->disposed.incrementAndGet());t.close();until(()->pool.outstanding()==0);}
            check(pool.outstanding()==0,"Cancellation versus completion does not leak permits");
            var failed=pool.submit(cancelled->{throw new IllegalStateException("expected");},v->{});until(failed::done);
            check(failed.failure() instanceof IllegalStateException,"Uncaught worker failures remain observable");failed.close();
        }finally{release.countDown();}
        return checks;
    }
}
