package dev.betterlitematica.selftest;

import dev.betterlitematica.core.RangeAllocator;
import java.util.*;

/** Random allocate/release sequences against a plain occupancy bitmap. */
public final class RangeAllocatorChecks {
    private static int checks;
    private static void check(boolean value,String message){checks++;if(!value)throw new AssertionError(message);}
    private RangeAllocatorChecks(){}
    private static void expect(Runnable call,String message){try{call.run();}catch(IllegalArgumentException|IllegalStateException expected){checks++;return;}throw new AssertionError(message);}

    public static int run(){
        checks=0;
        var random=new Random(20261011);
        for(int round=0;round<300;round++){
            int capacity=1+random.nextInt(round<50?64:20000);var allocator=new RangeAllocator(capacity);var used=new BitSet(capacity);
            var live=new ArrayList<int[]>();
            for(int step=0;step<400;step++){
                if(live.isEmpty()||random.nextInt(3)>0){
                    int size=1+random.nextInt(Math.max(1,random.nextBoolean()?capacity/8:capacity));int at=allocator.allocate(size);
                    // First fit: the lowest start of a free run that is long enough.
                    int expected=-1;for(int start=used.nextClearBit(0);start<capacity;){int end=Math.min(capacity,used.nextSetBit(start)<0?capacity:used.nextSetBit(start));if(end-start>=size){expected=start;break;}start=used.nextClearBit(end);}
                    check(at==expected,"First-fit start matches the reference (round "+round+")");
                    if(at>=0){check(used.get(at,at+size).isEmpty(),"New range is free");used.set(at,at+size);live.add(new int[]{at,size});}
                }else{
                    var range=live.remove(random.nextInt(live.size()));allocator.release(range[0],range[1]);used.clear(range[0],range[0]+range[1]);
                }
                check(allocator.used()==used.cardinality()&&allocator.free()==capacity-used.cardinality(),"Accounting matches occupancy");
                int largest=0,runs=0;for(int start=used.nextClearBit(0);start<capacity;){int end=used.nextSetBit(start)<0?capacity:used.nextSetBit(start);largest=Math.max(largest,end-start);runs++;start=used.nextClearBit(end);}
                check(allocator.largestFree()==largest&&allocator.fragments()==runs,"Free ranges are fully coalesced");
            }
            for(var range:live)allocator.release(range[0],range[1]);
            check(allocator.empty()&&allocator.fragments()==1&&allocator.largestFree()==capacity,"Releasing everything restores one free range");
        }
        var small=new RangeAllocator(10);int a=small.allocate(4);
        expect(()->small.release(a,4+7),"A release past the end is rejected");
        small.release(a,4);expect(()->small.release(a,4),"A double release is rejected");
        int b=small.allocate(6);expect(()->small.release(b+2,6),"A release overlapping free space is rejected");
        expect(()->small.allocate(0),"Empty allocations are rejected");
        check(small.allocate(5)==-1&&small.allocate(4)==6,"Exhaustion reports -1 and leaves the remaining range usable");
        return checks;
    }
}
