import com.retrievepanel.printer.*;
import java.util.*;

public class BatchChecks {
    static int checks;
    static void check(boolean value,String message){checks++;if(!value)throw new AssertionError(message);}
    public static void main(String[] args){
        var demand=List.of(new BatchPlanner.Demand<>("stone",100000,64),new BatchPlanner.Demand<>("dirt",65,64));
        var batch=BatchPlanner.plan(demand,36,32,2);
        check(batch.size()==32,"bounded packet budget");
        check(batch.get(0).item().equals("stone")&&batch.get(1).item().equals("dirt"),"material fairness");
        check(batch.stream().filter(s->s.item().equals("dirt")).mapToInt(s->s.count()).sum()==65,"exact secondary demand");
        check(BatchPlanner.plan(demand,0,32,2).isEmpty(),"full inventory");
        check(BatchPlanner.plan(demand,1,32,2).size()==1,"allow refill with one free slot");
        check(BatchPlanner.plan(demand,5,32,2).size()==3,"reserve user slots");
        check(BatchPlanner.plan(List.of(new BatchPlanner.Demand<>("bucket",99,1)),36,32,2).stream().allMatch(s->s.count()==1),"unstackable limit");
        check(BatchPlanner.plan(List.of(new BatchPlanner.Demand<>("air",0,64)),36,32,2).isEmpty(),"no unsolicited items");
        for(int empty=0;empty<=36;empty++)for(int limit=1;limit<=36;limit++){
            var p=BatchPlanner.plan(demand,empty,limit,2);
            check(p.size()<=empty&&p.size()<=limit,"never overbook inventory");
            check(p.stream().allMatch(s->s.count()>0&&s.count()<=64),"legal stack size");
        }
        var receipt=new ReceiptBatch(32);
        check(!receipt.complete(),"sending is not confirmation");
        for(int i=0;i<32;i++)receipt.receive(i,false);
        check(receipt.pending()==32,"wrong server contents are not accepted");
        for(int i=31;i>0;i--){receipt.receive(i,true);receipt.receive(i,true);}
        check(receipt.pending()==1&&!receipt.complete(),"out-of-order and duplicate receipts");
        receipt.receive(0,true);check(receipt.complete(),"all server receipts required");
        var timeout=new ReceiptBatch(1);
        for(int i=0;i<100;i++)check(!timeout.timedOut(100),"bounded wait");
        check(timeout.timedOut(100),"timeout terminates rather than retransmits");
        var queue=new MaterialQueue<String>();queue.touch("stone");queue.touch("dirt");long used=queue.usedAt("dirt");queue.touch("dirt");check(queue.usedAt("dirt")==used,"same-material placements do no map churn");
        LinkedHashMap<String,Integer> jobs=new LinkedHashMap<>();jobs.put("redstone",200);jobs.put("glass",64);jobs.put("stone",1000);
        var window=MaterialQueue.window("redstone",jobs,Map.of("dirt",999999),2,512);
        check(new ArrayList<>(window.keySet()).equals(List.of("redstone","glass")),"real queue priority beats global demand size");
        var stock=List.of(new EvictionPlanner.Slot<>(0,"pickaxe",1,false,true),new EvictionPlanner.Slot<>(9,"dirt",64,true,false),
            new EvictionPlanner.Slot<>(10,"stone",64,true,false),new EvictionPlanner.Slot<>(11,"glass",64,true,false),new EvictionPlanner.Slot<>(12,"named_block",64,false,false));
        var eviction=EvictionPlanner.plan(stock,window,Set.of("stone","glass"),queue,10);
        check(eviction.equals(List.of(9,10)),"reclaim unused first then future queue tail; retain next-window and named/tool items");
        check(EvictionPlanner.plan(stock,window,Set.of("stone"),queue,1).equals(List.of(9)),"reclaim only required capacity");
        var duplicates=List.of(new EvictionPlanner.Slot<>(9,"stone",64,true,false),new EvictionPlanner.Slot<>(10,"stone",64,true,false),new EvictionPlanner.Slot<>(11,"stone",64,true,false));
        check(EvictionPlanner.plan(duplicates,Map.of("stone",128),Set.of("stone"),queue,9).size()==1,"retain exact demand when clearing duplicate stacks");
        check(EvictionPlanner.plan(duplicates,Map.of("stone",129),Set.of("stone"),queue,9).isEmpty(),"never delete a partially needed whole stack");
        check(EvictionPlanner.plan(List.of(new EvictionPlanner.Slot<>(2,"dirt",64,true,true)),Map.of(),Set.of(),queue,1).isEmpty(),"held and protected slots survive even if surplus");
        queue.clear();check(queue.usedAt("stone")==0,"connection resets recency");
        List<EvictionPlanner.Slot<String>> full=new ArrayList<>();
        for(int i=0;i<36;i++)full.add(new EvictionPlanner.Slot<>(i,i<5?"tool":"old_stone",i<5?1:64,i>=5,i<5));
        var freed=EvictionPlanner.plan(full,Map.of("redstone",2048),Set.of(),queue,34);
        check(freed.size()==31&&freed.stream().allMatch(i->i>=5),"full inventory frees material slots but preserves all five tools");
        var afterTrash=BatchPlanner.plan(List.of(new BatchPlanner.Demand<>("redstone",2048,64)),freed.size(),32,2);
        check(afterTrash.size()==29,"full-inventory disposal feeds a bounded refill with two spare slots");
        var trashAck=new ReceiptBatch(freed.size());for(int i=0;i<30;i++)trashAck.receive(i,true);
        check(!trashAck.complete(),"one missing deletion receipt blocks reuse of the whole batch");
        trashAck.receive(30,false);check(!trashAck.complete(),"nonempty server result cannot authorize refill");
        trashAck.receive(30,true);check(trashAck.complete(),"late exact empty receipt settles without re-deleting");
        check(InventoryOwnership.blocksAutoRefill(true,true,true,true,true,false,false,false),"printer running excludes competing IPN operations");
        check(InventoryOwnership.blocksAutoRefill(true,true,true,true,false,true,false,false),"refill cleanup owns inventory after printer stops");
        check(InventoryOwnership.blocksAutoRefill(true,true,true,true,false,false,true,false),"pending swap owns inventory");
        check(InventoryOwnership.blocksAutoRefill(true,true,true,true,false,false,false,true),"unsettled trash owns inventory");
        check(!InventoryOwnership.blocksAutoRefill(true,true,true,true,false,false,false,false),"normal play restores IPN");
        check(!InventoryOwnership.blocksAutoRefill(false,true,true,true,true,false,false,false),"addon off restores IPN");
        check(!InventoryOwnership.blocksAutoRefill(true,false,true,true,true,false,false,false),"compatibility override respected");
        check(!InventoryOwnership.blocksAutoRefill(true,true,false,true,true,false,false,false),"no player no override");
        check(!InventoryOwnership.blocksAutoRefill(true,true,true,false,true,false,false,false),"manual GUI remains with its normal IPN behavior");
        long start=System.nanoTime();long stacks=0;
        for(int i=0;i<100000;i++)stacks+=BatchPlanner.plan(demand,36,32,2).size();
        System.out.println("PASS "+checks+" checks; planner 100000 batches / "+stacks+" stacks in "+((System.nanoTime()-start)/1e6)+" ms (CPU-only, not network throughput)");
    }
}
