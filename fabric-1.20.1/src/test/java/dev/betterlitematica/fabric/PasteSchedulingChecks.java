package dev.betterlitematica.fabric;

import dev.betterlitematica.core.*;
import java.util.*;

/** Real production discovery/progress state, with no client or world instance. */
public final class PasteSchedulingChecks {
    private static int checks;
    private static void check(boolean ok,String message){checks++;if(!ok)throw new AssertionError(message);}
    public static int run(){checks=0;
        var first=new Region("signed",new Vec3i(-17,-39,-18),new Vec3i(17,33,18),new Vec3i(-1,-7,-1));
        var last=new Region("other",new Vec3i(100,-64,100),new Vec3i(1,1,1));
        var regions=List.of(first,last);
        var columns=new DeferredSections(regions,true);var seen=new ArrayList<SectionKey>();
        columns.discover(100,key->{seen.add(key);return 0;},Long.MAX_VALUE);
        check(seen.size()==13&&columns.total()==13&&columns.completed()==13&&columns.finished(),"Columns account for ragged signed regions and final region exactly once");
        check(seen.subList(0,6).equals(List.of(new SectionKey(0,0,0,0),new SectionKey(0,0,1,0),new SectionKey(0,0,2,0),new SectionKey(0,1,0,0),new SectionKey(0,1,1,0),new SectionKey(0,1,2,0))),"Column traversal keeps all Y sections adjacent before crossing X");
        check(seen.get(6).equals(new SectionKey(0,0,0,1))&&seen.get(12).equals(new SectionKey(1,0,0,0)),"Z and region boundaries reset column coordinates");
        check(new HashSet<>(seen).size()==seen.size(),"No duplicated column across region rollover");
        check(first.sectionOrigin(seen.get(11)).equals(new Vec3i(-1,-7,-2)),"Column keys retain normalized negative source coordinates, not signed anchor origin");
        var legacy=new DeferredSections(regions);var old=new ArrayList<SectionKey>();legacy.discover(4,k->{old.add(k);return 0;},Long.MAX_VALUE);
        check(old.equals(List.of(new SectionKey(0,0,0,0),new SectionKey(0,1,0,0),new SectionKey(0,0,0,1),new SectionKey(0,1,0,1))),"Default callers retain previous row traversal");

        var held=new DeferredSections(regions,true);var stopped=new ArrayList<SectionKey>();
        held.discover(100,k->{stopped.add(k);return -2;},Long.MAX_VALUE);
        check(stopped.equals(List.of(seen.get(0)))&&held.pending()==0&&held.completed()==0&&!held.finished(),"Chunk-capacity hold claims no progress or queue slot");
        held.discover(100,k->{stopped.add(k);return -2;},Long.MAX_VALUE);
        check(stopped.size()==2&&stopped.get(1).equals(seen.get(0)),"Repeated capacity hold retries the identical source key");
        held.discover(1,k->{check(k.equals(seen.get(0)),"Release resumes held key, not following column");return 1;},Long.MAX_VALUE);
        check(held.pending()==1&&held.completed()==0,"Admission is pending, not completed");
        var work=held.poll(0);work.done.set(0,4096);held.resume(work);
        check(held.completed()==1&&held.pending()==0&&!held.finished(),"Finishing admitted work increments completion exactly once");
        held.discover(100,k->{check(k.equals(seen.get(1)),"Hold after completion keeps next Y section");return -2;},Long.MAX_VALUE);
        held.discover(100,k->0,Long.MAX_VALUE);
        check(held.finished()&&held.completed()==13,"Held frontier drains completely after capacity returns");

        var waiting=new DeferredSections(List.of(new Region("two",Vec3i.ZERO,new Vec3i(1,32,1))),true);
        waiting.discover(2,k->k.y()==0?-1:1,Long.MAX_VALUE);
        check(waiting.pending()==1&&waiting.completed()==0,"Unavailable -1 does not occupy admission slots or prevent later work");
        work=waiting.poll(0);check(work.key.y()==1,"Later loaded section can run past unavailable predecessor");work.done.set(0,4096);waiting.resume(work);
        waiting.discover(2,k->1,Long.MAX_VALUE);work=waiting.poll(0);check(work.key.y()==0,"Circular discovery eventually returns to newly available predecessor");work.done.set(0,4096);waiting.resume(work);check(waiting.finished(),"Unavailable predecessor does not permanently stall completion");

        var parked=new DeferredSections(regions,true);parked.admit(100);DeferredSections.Work partial=null;
        while((work=parked.poll(0))!=null){if(work.key.equals(new SectionKey(0,1,2,1))){partial=work;work.done.set(0);work.done.set(16);work.done.set(4095);parked.unavailable(work);}else{work.done.set(0,4096);parked.resume(work);}}
        check(partial!=null&&parked.completed()==12&&!parked.finished(),"Unloading partial last column removes exactly one claimed section");
        parked.discover(100,k->-2,Long.MAX_VALUE);check(parked.completed()==12&&parked.pending()==0,"Holding a parked column keeps its checkpoint without falsely finishing");
        parked.admit(100);work=parked.poll(0);
        check(work!=null&&work.key.equals(partial.key),"Parked column restores the correct region and ordinal");
        check(work.done.get(0)&&work.done.get(16)&&!work.done.get(1)&&!work.done.get(4095),"Real source progress survives; out-of-domain padding is not stored as source data");
        check(work.cursor==0,"Restored partial cursor rescans only unset positions");work.done.set(0,4096);parked.resume(work);check(parked.finished()&&parked.completed()==13,"Restored ragged column can finish normally");

        var budget=new DeferredSections(regions,true);int[] called={0};budget.discover(100,k->{called[0]++;return 1;},0);budget.discover(0,k->{called[0]++;return 1;},Long.MAX_VALUE);
        check(called[0]==0&&budget.pending()==0,"Expired time budget and zero count budget admit no work");
        budget.admit(2);work=budget.poll(0);work.retryAt=5;budget.defer(work);var next=budget.poll(0);check(next!=null&&!next.key.equals(work.key),"Async loading retry does not block ready sibling work");next.done.set(0,4096);budget.resume(next);check(budget.poll(4)==null&&budget.poll(5)==work,"Delayed work becomes eligible exactly at retry tick");budget.resume(work);budget.clear();
        check(budget.finished()&&budget.pending()==0&&budget.completed()==budget.total(),"Cancel clear releases admitted and delayed work");budget.discover(100,k->{throw new AssertionError("Cleared task rediscovered source");},Long.MAX_VALUE);
        var empty=new DeferredSections(List.of(),true);check(empty.finished()&&empty.total()==0,"Empty source is immediately complete");

        var bounded=new DeferredSections(List.of(new Region("bounded",Vec3i.ZERO,new Vec3i(4096,16,2064))),true);
        bounded.admit(Integer.MAX_VALUE);check(bounded.pending()==DeferredSections.LIMIT&&!bounded.finished(),"Column traversal still obeys the global pending-section cap");
        work=bounded.poll(0);work.done.set(0,4096);bounded.resume(work);bounded.admit(Integer.MAX_VALUE);
        check(bounded.pending()==DeferredSections.LIMIT&&bounded.completed()==1,"Released admission slot advances one more column without exceeding capacity");bounded.clear();
        for(boolean columnOrder:new boolean[]{false,true}){
            var priority=new DeferredSections(regions,columnOrder);var lastKey=new SectionKey(1,0,0,0);
            priority.prioritize(lastKey,k->-1);priority.prioritize(lastKey,k->-2);
            check(priority.pending()==0&&priority.completed()==0,"Unavailable prioritized source remains unclaimed");
            priority.prioritize(lastKey,k->1);var owned=priority.poll(0);
            check(owned!=null&&owned.key.equals(lastKey),"Exact later-region wake precedes undiscovered regions in either order");
            check(priority.prioritize(lastKey,k->{throw new AssertionError("Active work must not be readmitted");}),"Currently owned work acknowledges hint without duplication");
            check(priority.pending()==0,"Currently polled work is not duplicated by loading event");
            owned.done.set(0,4096);priority.resume(owned);
            check(priority.prioritize(lastKey,k->{throw new AssertionError("Completed work must not be readmitted");}),"Completed work acknowledges hint");
            var restoredKey=new SectionKey(0,1,2,1);priority.prioritize(restoredKey,k->1);owned=priority.poll(0);
            owned.done.set(0);owned.done.set(16);owned.done.set(4095);owned.cursor=120;priority.unavailable(owned);
            priority.prioritize(restoredKey,k->1);var restored=priority.poll(0);
            check(restored.done.get(0)&&restored.done.get(16)&&!restored.done.get(4095)&&restored.cursor==0,"Priority restores only real parked progress with correct row/column ordinal");
            restored.retryAt=500;priority.defer(restored);priority.admit(100);
            priority.prioritize(restoredKey,k->{throw new AssertionError("Queued wake must reuse work without disposition");});
            check(priority.poll(0)==restored&&restored.retryAt==0,"Queued delayed work wakes immediately and preserves bitmap identity");
            restored.done.set(0,4096);priority.resume(restored);
            var unique=new HashSet<SectionKey>();unique.add(lastKey);unique.add(restoredKey);
            while((work=priority.poll(0))!=null){check(unique.add(work.key),"Exact wake and circular discovery never duplicate an ordinal");work.done.set(0,4096);priority.resume(work);}
            check(priority.finished()&&priority.completed()==13&&unique.size()==13,"Both ordinal orders complete every source region once");
            priority.clear();priority.prioritize(lastKey,k->{throw new AssertionError("Cancelled task must not wake");});priority.defer(restored);
            check(priority.finished()&&priority.pending()==0,"Clear permanently rejects stale chunk wakeups and work references");
        }
        var full=new DeferredSections(List.of(new Region("full",Vec3i.ZERO,new Vec3i(4096,16,2064))),true);full.admit(Integer.MAX_VALUE);
        var notQueued=new SectionKey(0,0,0,128);check(!full.prioritize(notQueued,k->{throw new AssertionError("Full queue cannot admit extra priority work");}),"Full queue rejects hint for caller to retain and retry");
        check(full.pending()==DeferredSections.LIMIT,"New priority admission obeys count budget at capacity");
        var nearTail=new SectionKey(0,255,0,127);check(full.prioritize(nearTail,k->{throw new AssertionError("Existing priority wake cannot require an extra slot");}),"Existing queued hint succeeds even at capacity");
        work=full.poll(0);check(work.key.equals(nearTail)&&full.pending()==DeferredSections.LIMIT-1,"Existing tail wakes at capacity without linear queue traversal");
        work.done.set(0,4096);full.resume(work);check(full.prioritize(notQueued,k->1),"Retained hint succeeds once capacity is released");
        check(full.pending()==DeferredSections.LIMIT&&full.poll(0).key.equals(notQueued),"Freed slot accepts newly loaded source ahead of earlier queued work");full.clear();
        var invalid=new DeferredSections(regions);boolean rejected=false;try{invalid.prioritize(new SectionKey(0,2,0,0),k->1);}catch(IllegalArgumentException expected){rejected=true;}
        check(rejected&&invalid.pending()==0,"Out-of-range source key cannot alias another ordinal");
        return checks;
    }
    public static void main(String[] args){System.out.println("PasteSchedulingChecks: "+run()+" checks");}
}
