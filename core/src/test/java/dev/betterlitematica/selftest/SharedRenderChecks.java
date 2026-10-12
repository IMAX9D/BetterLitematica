package dev.betterlitematica.selftest;
import dev.betterlitematica.runtime.RenderResources;
public final class SharedRenderChecks {
    private static int checks;
    private static void check(boolean value,String message){checks++;if(!value)throw new AssertionError(message);}
    public static int run(){
        checks=0;var resources=new RenderResources(100,20,20);resources.concurrentBuilds(true);
        var a=resources.begin(RenderResources.VISIBLE);var page=resources.reserveShared(80,2,a,RenderResources.VISIBLE);
        check(page!=null&&resources.usedBytes()==80&&resources.objects()==2,"Full page capacity and both GL objects are charged");
        int[] users={2};Runnable release=()->{if(--users[0]==0)page.close();};
        a.reference(10,release);a.finish(RenderResources.VISIBLE,()->{});
        var b=resources.begin(RenderResources.VISIBLE);b.reference(10,release);b.finish(RenderResources.VISIBLE,()->{});
        check(resources.usedBytes()==80,"Shared slices never double-charge the same page");
        var c=resources.begin(RenderResources.NEARBY);
        check(resources.reserveShared(30,2,c,RenderResources.NEARBY)==null,"Unused page space still occupies the hard budget");
        check(c.reserve(new int[]{20},RenderResources.NEARBY)!=null&&resources.usedBytes()==100,"Private and shared storage use the same budget");
        c.finish(RenderResources.NEARBY,()->{});a.close();resources.drain(20,Long.MAX_VALUE);
        check(resources.usedBytes()==100&&users[0]==1&&resources.pendingBytes()==0,"Freeing one slice does not free a live page's capacity");
        b.close();resources.drain(20,Long.MAX_VALUE);
        check(resources.usedBytes()==20&&resources.objects()==1,"The last slice releases exactly one physical page");
        c.close();check(resources.pendingBytes()==20,"Pending private deletion remains charged");resources.drain(20,Long.MAX_VALUE);
        check(resources.usedBytes()==0&&resources.pendingBytes()==0&&resources.objects()==0&&resources.groups()==0,"Mixed ownership drains without negative counters or leaks");
        var failed=resources.begin(RenderResources.VISIBLE);var reserved=resources.reserveShared(100,2,failed,RenderResources.VISIBLE);
        reserved.close();reserved.close();failed.close();check(resources.usedBytes()==0&&resources.objects()==0,"Failed page construction rolls back once");
        return checks;
    }
}
