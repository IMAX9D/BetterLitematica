import com.retrievepanel.printer.*;
import java.util.*;

/** Deterministic adversarial traces; no Minecraft/packet throughput claims. */
public final class StockChecks {
    static int checks;
    static void check(boolean yes,String message){checks++;if(!yes)throw new AssertionError(message);}
    static StockPolicy<String> policy(){return new StockPolicy<>(s->s.equals("bucket")?1:64,StockPolicy.Settings.defaults());}
    static List<StockPolicy.Slot<String>> empty(int n){var s=new ArrayList<StockPolicy.Slot<String>>();for(int i=0;i<n;i++)s.add(new StockPolicy.Slot<>(i,null,0,true,false));return s;}
    static List<StockPolicy.Slot<String>> full(int n,String item,int count){var s=empty(n);for(int i=0;i<n;i++)s.set(i,new StockPolicy.Slot<>(i,item,count,true,true));return s;}
    static void apply(StockPolicy<String> policy,StockPolicy.Plan<String> p,List<StockPolicy.Slot<String>> slots){
        Set<Integer> unique=new HashSet<>();
        for(int index:p.deletes()){
            var s=slots.get(index);check(unique.add(index)&&s.deletable(),"only distinct safe slots are deleted");
            slots.set(index,new StockPolicy.Slot<>(index,null,0,true,false));
        }
        for(var w:p.writes()){
            int index=-1;for(var s:slots)if(s.usable()&&s.count()==0){index=s.index();break;}
            check(index>=0,"no overwrite or inventory overbooking");
            check(w.count()==(w.item().equals("bucket")?1:64),"every generated ordinary stack is full");
            slots.set(index,new StockPolicy.Slot<>(index,w.item(),w.count(),true,!w.item().equals("bucket")));
        }
        policy.committed(p);
    }
    static void consume(List<StockPolicy.Slot<String>> slots,String item,int n){
        for(int i=0;i<slots.size()&&n>0;i++){
            var s=slots.get(i);if(!Objects.equals(item,s.item()))continue;
            int used=Math.min(n,s.count()),left=s.count()-used;n-=used;
            slots.set(i,new StockPolicy.Slot<>(i,left==0?null:item,left,true,left>0&&s.deletable()));
        }check(n==0,"trace cannot consume unavailable inventory");
    }
    static int count(List<StockPolicy.Slot<String>> slots,String item){return slots.stream().filter(s->Objects.equals(s.item(),item)).mapToInt(StockPolicy.Slot::count).sum();}
    public static void main(String[] args){
        var p=policy();var slots=empty(30);p.observe(0,Map.of("stone",1),Set.of(),slots,"stone");
        var first=p.plan(slots,"stone",Set.of(),true);
        check(first.writes().size()==30&&first.deletes().isEmpty(),"one-job demand fills all 30 printing slots");apply(p,first,slots);
        consume(slots,"stone",1);p.observe(1,Map.of("stone",3),Set.of(),slots,null);
        check(!p.plan(slots,null,Set.of("stone"),true).actionable(),"no one-item refill loop");
        consume(slots,"stone",63);p.observe(2,Map.of("stone",1),Set.of(),slots,null);
        check(!p.plan(slots,null,Set.of("stone"),true).actionable(),"one empty slot does not open a session");
        consume(slots,"stone",7*64);p.observe(3,Map.of("stone",1),Set.of(),slots,null);
        var bulk=p.plan(slots,null,Set.of("stone"),true);
        check(bulk.cause()==StockPolicy.Cause.BULK&&bulk.writes().size()==8&&bulk.deletes().isEmpty(),"eight empty slots are filled together");apply(p,bulk,slots);
        check(count(slots,"stone")==30*64,"full after bulk receipt");

        // 80 persistent kinds, different turns ordering each snapshot, 30 occupied slots.
        p=policy();slots=empty(30);LinkedHashMap<String,Integer> work=new LinkedHashMap<>();
        for(int i=0;i<80;i++)work.put("m"+i,i==0?4096:1);
        p.observe(0,work,Set.of(),slots,null);var initial=p.plan(slots,null,Set.of(),true);apply(p,initial,slots);
        Set<String> admitted=new HashSet<>(initial.admissions());boolean firstRotation=true;int rotations=0;
        for(int step=1;step<180&&admitted.size()<80;step++){
            // Opportunities are attempts, not claimed successful consumptions.
            for(var s:slots)if(s.item()!=null)p.attempted(s.item());
            var rotated=new LinkedHashMap<String,Integer>();for(int i=0;i<80;i++){String k="m"+((i+step)%80);rotated.put(k,work.get(k));}
            p.observe(step,rotated,Set.of(),slots,null);var plan=p.plan(slots,null,Set.of("m0"),true);
            if(plan.actionable()){
                check(plan.deletes().size()<=3,"30-slot proactive rotation never wipes more than 3 slots");
                if(firstRotation){check(new ArrayList<>(plan.admissions()).equals(List.of("m30","m31","m32")),"rotating turns do not reorder pending admissions");firstRotation=false;}
                for(int d:plan.deletes())check(!Objects.equals(slots.get(d).item(),"m0"),"active last stack protected");
                admitted.addAll(plan.admissions());apply(p,plan,slots);rotations++;
            }
        }
        check(admitted.size()==80,"all 80 kinds receive admission under continuing safe opportunities");
        System.out.println("TRACE 30 slots / 80 kinds: "+rotations+" bounded rotations to observe all kinds (scenario result, not a universal bound)");

        // With no printing attempts and unchanged work, only one exploratory rotation is permitted.
        p=policy();slots=full(30,"old",64);p.observe(0,work,Set.of(),slots,null);int idleRotations=0;
        for(int step=1;step<=100;step++){
            p.observe(step,work,Set.of(),slots,null);var plan=p.plan(slots,null,Set.of(),true);
            if(plan.actionable()){idleRotations++;apply(p,plan,slots);}
        }
        check(idleRotations==1,"stalled placement cannot cycle inventory forever");

        p=policy();slots=full(30,"stone",1);p.observe(0,Map.of("stone",1),Set.of(),slots,null);
        check(!p.plan(slots,null,Set.of("stone"),true).actionable(),"normal top-up never deletes 30 partial stacks");
        p.observe(1,Map.of("stone",1,"glass",1),Set.of(),slots,"glass");var urgent=p.plan(slots,"glass",Set.of("stone"),true);
        check(urgent.deletes().size()==1&&urgent.writes().size()==1&&urgent.writes().get(0).item().equals("glass"),"urgent new material frees exactly one partial slot");apply(p,urgent,slots);
        check(count(slots,"stone")==29,"all unnecessary partial-stack deletions avoided");

        p=policy();slots=full(1,"a",64);p.observe(0,Map.of("a",1,"b",1),Set.of(),slots,null);
        check(!p.plan(slots,null,Set.of("a"),true).actionable(),"one-slot case retains usable active stock");
        p.observe(1,Map.of("a",1,"b",1),Set.of(),slots,"b");var one=p.plan(slots,"b",Set.of("a"),true);
        check(one.deletes().size()==1&&one.writes().get(0).item().equals("b"),"one-slot genuine urgent need can rotate");apply(p,one,slots);
        consume(slots,"b",64);p.observe(2,Map.of("a",1),Set.of(),slots,"a");
        check(p.plan(slots,"a",Set.of(),true).writes().size()==1,"one available slot necessarily refills once per stack");

        p=policy();slots=empty(4);slots.set(0,new StockPolicy.Slot<>(0,"tool",1,false,false));slots.set(1,new StockPolicy.Slot<>(1,"named",64,false,false));slots.set(2,new StockPolicy.Slot<>(2,"protected",64,false,false));slots.set(3,new StockPolicy.Slot<>(3,"held",64,true,false));
        p.observe(0,Map.of("stone",1),Set.of(),slots,"stone");check(!p.plan(slots,"stone",Set.of(),true).actionable(),"protected inventory cannot be evicted even for urgent need");
        p=policy();slots=full(30,"old",64);p.observe(0,Map.of("new",1),Set.of(),slots,"new");
        check(!p.plan(slots,"new",Set.of(),false).actionable(),"no trash ownership means no deletion or overwrite");

        // One empty slot reserved for pending admission BEFORE any dominant resident top-up.
        p=policy();slots=full(30,"stone",64);slots.set(0,new StockPolicy.Slot<>(0,null,0,true,false));
        p.observe(0,Map.of("stone",4096,"glass",1),Set.of(),slots,"glass");var reserved=p.plan(slots,"glass",Set.of("stone"),true);
        check(reserved.writes().get(0).item().equals("glass"),"new material cannot lose its slot to resident top-up");

        p=policy();slots=empty(30);p.observe(0,Map.of(),Set.of(),slots,"bucket");var bucket=p.plan(slots,"bucket",Set.of(),true);
        check(bucket.writes().size()==1&&bucket.writes().get(0).count()==1,"nonstackable urgent item is not duplicated to fill inventory");
        p=policy();slots=full(30,"stone",64);var hand=p.emptyHandPlan(slots,true);
        check(hand.deletes().size()==1&&hand.writes().isEmpty(),"empty-hand operation frees one slot and never generates AIR");apply(p,hand,slots);
        check(!p.emptyHandPlan(slots,true).actionable(),"existing empty hand slot needs no extra deletion");

        p=policy();slots=empty(30);p.observe(0,Map.of("old",1),Set.of(),slots,"old");apply(p,p.plan(slots,"old",Set.of(),true),slots);
        p.observe(1,Map.of(),Set.of(),slots,null);check(!p.plan(slots,null,Set.of(),true).actionable(),"transient absent queue is not a clearing command");
        p.reset();p.observe(0,Map.of("new",1),Set.of(),slots,"new");var changed=p.plan(slots,"new",Set.of(),true);
        check(changed.deletes().size()==1&&changed.admissions().equals(Set.of("new")),"new generation retires old priority without clearing entire inventory");

        // Fresh admission protection and repeated snapshots must not age on server callback count.
        p=policy();slots=empty(2);p.observe(0,Map.of("a",1,"b",1),Set.of(),slots,null);apply(p,p.plan(slots,null,Set.of(),true),slots);
        for(int i=0;i<100;i++)p.observe(0,Map.of("a",1,"b",1,"c",1),Set.of(),slots,null);
        check(p.round()==1&&!p.plan(slots,null,Set.of(),true).actionable(),"duplicate evaluations/packets cannot age fresh stock into eviction");

        // Random full/partial inventories, protected items and genuine urgent requests.
        Random random=new Random(730254);
        for(int run=0;run<500;run++){
            p=policy();int n=1+random.nextInt(36);slots=empty(n);
            for(int i=0;i<n;i++)if(random.nextBoolean()){
                boolean safe=random.nextInt(4)!=0;slots.set(i,new StockPolicy.Slot<>(i,"m"+random.nextInt(8),1+random.nextInt(64),safe,safe&&random.nextBoolean()));
            }
            String required=random.nextBoolean()?"urgent":null;
            p.observe(0,Map.of("m0",4000,"m1",2,"urgent",1),Set.of(),slots,required);
            boolean trash=random.nextBoolean();var plan=p.plan(slots,required,Set.of("m0"),trash);
            if(!trash)check(plan.deletes().isEmpty(),"random no-trash plan does not delete");
            var before=new ArrayList<>(slots);if(plan.actionable())apply(p,plan,slots);
            for(int i=0;i<n;i++)if(!before.get(i).usable())check(before.get(i).equals(slots.get(i)),"random protected item unchanged");
        }

        // 65,536 confirmed-model consumption events, each short queue contains only 1-3 jobs.
        p=policy();slots=empty(30);int sessions=0,deleted=0;int blocks=65536;
        for(int step=0;step<blocks;step++){
            boolean missing=count(slots,"stone")==0;
            p.observe(step,Map.of("stone",1+step%3),Set.of(),slots,missing?"stone":null);
            var plan=p.plan(slots,missing?"stone":null,Set.of("stone"),true);
            if(plan.actionable()){sessions++;deleted+=plan.deletes().size();apply(p,plan,slots);}
            p.attempted("stone");consume(slots,"stone",1);
        }
        check(sessions<150&&deleted==0,"short-queue trace batches sessions with no disposal");
        int legacy=0,oldStock=0;for(int step=0;step<blocks;step++){if(oldStock==0){oldStock=1+step%3;legacy++;}oldStock--;}
        System.out.println("TRACE short queues / "+blocks+" model placements: exact-count baseline="+legacy+" sessions, stock="+sessions+", deleted="+deleted+" (CPU model only)");
        // Large pending set is bounded and evaluated without any per-placement traversal.
        p=policy();work.clear();for(int i=0;i<4096;i++)work.put("wide"+i,1);slots=full(30,"fixed",64);
        long start=System.nanoTime();for(int i=0;i<100;i++){p.observe(i,work,Set.of(),slots,null);p.plan(slots,null,Set.of(),false);}
        System.out.println("PASS "+checks+" stock checks; 100 evaluations / 4096 materials: "+((System.nanoTime()-start)/1e6)+" ms (not game throughput)");
    }
}
