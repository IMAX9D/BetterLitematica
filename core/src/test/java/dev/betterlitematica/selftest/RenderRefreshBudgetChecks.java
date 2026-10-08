package dev.betterlitematica.selftest;

import dev.betterlitematica.runtime.RenderResources;
import java.util.concurrent.atomic.AtomicInteger;

/** Dependency-free checks for replacement headroom and deferred native release. */
public final class RenderRefreshBudgetChecks {
    private int checks;
    private void check(boolean value,String message){checks++;if(!value)throw new AssertionError(message);}
    private void rejects(Runnable action){try{action.run();}catch(IllegalArgumentException expected){checks++;return;}throw new AssertionError("Expected invalid budget/allocation rejection");}
    private RenderResources.Group resident(RenderResources budget,int priority,int...sizes){
        var group=budget.begin(priority);check(group!=null,"Normal group admitted");
        check(group.reserve(sizes,priority)!=null,"Normal allocation admitted");group.finish(priority,()->{});return group;
    }
    private void empty(RenderResources budget,RenderResources.Group...groups){
        for(var group:groups)group.close();budget.drain(1000,Long.MAX_VALUE);
        check(budget.usedBytes()==0&&budget.objects()==0&&budget.groups()==0&&budget.pendingBytes()==0,"All accounting returns to zero");
    }
    public static int run(){
        var suite=new RenderRefreshBudgetChecks();suite.fullNormalBudget();suite.retirementAndResume();suite.pendingPressure();suite.objectAndGroupPressure();suite.prioritiesAndPermit();suite.defaultsAndValidation();return suite.checks;
    }
    private void fullNormalBudget(){
        var budget=new RenderResources(100,10,4,20,2,1);
        var a=resident(budget,RenderResources.VISIBLE,10,10);
        var b=resident(budget,RenderResources.VISIBLE,10,10,10);
        var c=resident(budget,RenderResources.VISIBLE,10,10,10);
        check(budget.begin(RenderResources.VISIBLE)==null,"Ordinary groups cannot consume reserved slot");
        var replacement=budget.beginReplacement(RenderResources.VISIBLE);check(replacement!=null,"Replacement starts at full normal group budget");
        check(budget.begin(RenderResources.VISIBLE)==null&&budget.beginReplacement(RenderResources.VISIBLE)==null,"One builder shared by both admission paths");
        check(replacement.reserve(new int[]{10,10},RenderResources.VISIBLE)!=null,"Replacement consumes byte and object reserves");
        check(budget.usedBytes()==100&&budget.objects()==10&&budget.groups()==4,"Replacement reaches but never exceeds hard caps");
        check(replacement.reserve(new int[]{1},RenderResources.VISIBLE)==null,"Replacement cannot exceed hard byte/object capacity");
        check(!a.closed()&&!b.closed()&&!c.closed(),"Visible old meshes remain attached during replacement");
        replacement.finish(RenderResources.VISIBLE,()->{});
        check(budget.beginReplacement(RenderResources.VISIBLE)==null,"Replacement cannot exceed hard group cap");
        empty(budget,a,b,c,replacement);

        budget=new RenderResources(100,10,8,20,2,1);
        a=resident(budget,RenderResources.VISIBLE,10,10,10,10,10,10,10,10);
        var normal=budget.begin(RenderResources.VISIBLE);check(normal!=null,"Ordinary empty builder fits available normal group slots");
        check(normal.reserve(new int[]{1},RenderResources.VISIBLE)==null,"Ordinary reserve cannot steal replacement bytes or objects");
        check(normal.reserve(new int[]{81},RenderResources.VISIBLE)==null,"Impossible normal request does not enter reserved capacity");
        check(!a.closed()&&budget.usedBytes()==80,"Rejected ordinary allocation leaves old mesh intact");empty(budget,a,normal);
    }
    private void retirementAndResume(){
        var budget=new RenderResources(100,10,5,20,2,1);var released=new AtomicInteger();
        var old=budget.begin(RenderResources.VISIBLE);check(old!=null,"Old mesh builder admitted");
        for(var lease:old.reserve(new int[]{10,10},RenderResources.VISIBLE))lease.attach(released::incrementAndGet);
        old.finish(RenderResources.VISIBLE,()->{});var other=resident(budget,RenderResources.VISIBLE,10,10,10,10,10,10);
        var replacement=budget.beginReplacement(RenderResources.VISIBLE);check(replacement.reserve(new int[]{10},RenderResources.VISIBLE)!=null,"Smaller replacement built beside old mesh");replacement.finish(RenderResources.VISIBLE,()->{});
        old.close();check(budget.usedBytes()==90&&budget.pendingBytes()==20&&released.get()==0,"Retirement retains full native charge");
        var next=budget.begin(RenderResources.VISIBLE);check(next!=null&&next.reserve(new int[]{10},RenderResources.VISIBLE)==null,"Normal build waits for actual retirement, not pending promise");
        check(budget.drain(1,Long.MAX_VALUE)==1&&budget.usedBytes()==80&&budget.pendingBytes()==10&&released.get()==1,"Incremental drain charges remaining old allocation");
        check(next.reserve(new int[]{10},RenderResources.VISIBLE)==null,"Partial retirement still cannot use reserved bytes");
        budget.drain(1,Long.MAX_VALUE);check(next.reserve(new int[]{10},RenderResources.VISIBLE)!=null,"Ordinary building resumes once replacement headroom is restored");
        check(budget.groups()==4,"Group retirement waits for its separate drain step");budget.drain(1,Long.MAX_VALUE);check(budget.groups()==3,"Completed retired group releases its count");empty(budget,other,replacement,next);
    }
    private void pendingPressure(){
        var budget=new RenderResources(100,20,10,20,2,1);
        var a=resident(budget,RenderResources.COLD,20);var b=resident(budget,RenderResources.COLD,20);var c=resident(budget,RenderResources.COLD,20);var d=resident(budget,RenderResources.COLD,20);
        var normal=budget.begin(RenderResources.VISIBLE);
        check(normal.reserve(new int[]{20},RenderResources.VISIBLE)==null&&a.closed(),"Normal pressure reclaims against normal byte capacity");
        for(int i=0;i<20;i++)check(normal.reserve(new int[]{20},RenderResources.VISIBLE)==null,"Pending native deletion cannot admit ordinary allocations early");
        check(!b.closed()&&!c.closed()&&!d.closed()&&budget.pendingBytes()==20,"Repeated normal pressure does not retire additional promised space");
        budget.drain(100,Long.MAX_VALUE);check(normal.reserve(new int[]{20},RenderResources.VISIBLE)!=null,"Normal request succeeds after release");normal.finish(RenderResources.VISIBLE,()->{});
        var replacement=budget.beginReplacement(RenderResources.VISIBLE);check(replacement.reserve(new int[]{20},RenderResources.VISIBLE)!=null,"Replacement uses hard rather than normal capacity");
        check(replacement.reserve(new int[]{20},RenderResources.VISIBLE)==null&&b.closed(),"Replacement retires only when hard capacity is reached");
        for(int i=0;i<20;i++)check(replacement.reserve(new int[]{20},RenderResources.VISIBLE)==null,"Replacement waits for pending hard-cap release");
        check(!c.closed()&&!d.closed()&&budget.pendingBytes()==20,"Replacement retries also avoid wholesale eviction");
        budget.drain(100,Long.MAX_VALUE);check(replacement.reserve(new int[]{20},RenderResources.VISIBLE)!=null&&budget.usedBytes()==100,"Replacement uses only actually freed hard capacity");empty(budget,a,b,c,d,normal,replacement);
    }
    private void objectAndGroupPressure(){
        var budget=new RenderResources(1000,5,8,100,2,1);
        var a=resident(budget,RenderResources.COLD,1);var b=resident(budget,RenderResources.COLD,1);var c=resident(budget,RenderResources.COLD,1);
        var normal=budget.begin(RenderResources.VISIBLE);check(normal.reserve(new int[]{1},RenderResources.VISIBLE)==null&&a.closed(),"Object-only pressure honors normal object limit");
        check(normal.reserve(new int[]{1},RenderResources.VISIBLE)==null&&!b.closed()&&!c.closed(),"Pending object count prevents duplicate retirement");budget.drain(100,Long.MAX_VALUE);
        check(normal.reserve(new int[]{1},RenderResources.VISIBLE)!=null,"Object space reusable after release");normal.finish(RenderResources.VISIBLE,()->{});
        var replacement=budget.beginReplacement(RenderResources.VISIBLE);check(replacement.reserve(new int[]{1,1},RenderResources.VISIBLE)!=null&&budget.objects()==5,"Replacement may use reserved objects");
        check(replacement.reserve(new int[]{1},RenderResources.VISIBLE)==null&&b.closed(),"Replacement respects hard object capacity");empty(budget,a,b,c,normal,replacement);

        budget=new RenderResources(1000,20,4,100,2,1);a=resident(budget,RenderResources.COLD,1);b=resident(budget,RenderResources.COLD,1);c=resident(budget,RenderResources.COLD,1);
        check(budget.begin(RenderResources.VISIBLE)==null&&a.closed(),"Group-only pressure uses normal group limit");
        for(int i=0;i<10;i++)check(budget.begin(RenderResources.VISIBLE)==null,"Retired group remains charged until drained");
        check(!b.closed()&&!c.closed(),"Pending group avoids eviction of entire cache");
        budget.drain(1,Long.MAX_VALUE);check(budget.begin(RenderResources.VISIBLE)==null,"Released lease alone does not free group record");budget.drain(1,Long.MAX_VALUE);
        normal=budget.begin(RenderResources.VISIBLE);check(normal!=null,"Normal group admitted after retired record drains");empty(budget,a,b,c,normal);
    }
    private void prioritiesAndPermit(){
        var budget=new RenderResources(100,10,5,20,2,1);var old=resident(budget,RenderResources.VISIBLE,80);old.priority(RenderResources.NEARBY);
        var replacement=budget.beginReplacement(RenderResources.VISIBLE);check(replacement.reserve(new int[]{20},RenderResources.VISIBLE)!=null,"Replacement starts during camera grace");
        check(replacement.reserve(new int[]{1},RenderResources.VISIBLE)==null&&!old.closed(),"Replacement preserves recently visible old mesh");
        long before=budget.revision();for(int i=0;i<10;i++)budget.nextFrame();check(before==budget.revision(),"No per-frame retry wake during grace");budget.nextFrame();check(before!=budget.revision(),"Grace expiry wakes blocked replacement");
        check(replacement.reserve(new int[]{1},RenderResources.NEARBY)==null&&!old.closed(),"Replacement does not weaken demand priority");
        check(replacement.reserve(new int[]{1},RenderResources.VISIBLE)==null&&old.closed(),"Visible replacement may reclaim expired nearby mesh");empty(budget,old,replacement);
    }
    private void defaultsAndValidation(){
        var legacy=new RenderResources(100,2,2);var first=resident(legacy,RenderResources.VISIBLE,50,50);var next=legacy.begin(RenderResources.VISIBLE);
        check(next!=null&&next.reserve(new int[]{1},RenderResources.VISIBLE)==null,"Legacy constructor retains full ordinary byte/object budget");next.close();first.close();legacy.drain(100,Long.MAX_VALUE);check(legacy.groups()==0,"Legacy accounting unchanged");
        var budget=new RenderResources(100,3,3,20,1,1);var group=budget.beginReplacement(RenderResources.VISIBLE);
        rejects(()->group.reserve(new int[]{101},RenderResources.VISIBLE));rejects(()->group.reserve(new int[]{1,1,1,1},RenderResources.VISIBLE));
        check(group.reserve(new int[]{100},RenderResources.VISIBLE)!=null,"Replacement can reserve exact hard capacity");empty(budget,group);
        rejects(()->new RenderResources(100,3,3,-1,0,0));rejects(()->new RenderResources(100,3,3,100,0,0));rejects(()->new RenderResources(100,3,3,0,3,0));rejects(()->new RenderResources(100,3,3,0,0,3));
        var production=new RenderResources(1L<<30,65536,65536,8L<<20,8192,1);check(production.warm(),"Production headroom configuration is accepted");
    }
    public static void main(String[] args){System.out.println("RenderRefreshBudgetChecks: "+run()+" checks passed");}
}
