package dev.betterlitematica.selftest;
import dev.betterlitematica.core.*;
import java.util.*;
final class PrinterModeChecks {
    static int run(){int checks=0;
        int k=PrinterDiscovery.KNOWN;
        var page=new PrinterDiscovery.Page(1,new long[]{1,2,3,4,5},new int[]{1,1,0,1,1},new int[]{2,3,4,0,5},
            new int[]{k,k|PrinterDiscovery.SAME_BLOCK|PrinterDiscovery.ADJUSTABLE,k|PrinterDiscovery.WANTED_AIR,k|PrinterDiscovery.ACTUAL_AIR|PrinterDiscovery.REPLACEABLE,k|PrinterDiscovery.REPLACEABLE},new int[]{1,1,1,1,1},new int[5]);
        for(int bits=0;bits<8;bits++){
            boolean wrong=(bits&1)!=0,extra=(bits&2)!=0,state=(bits&4)!=0;
            var result=PrinterDiscovery.search(page,new PrinterDiscovery.Policy(false,false,false,false,wrong,extra,state,false));
            Set<Long> expected=new HashSet<>();if(wrong){expected.add(1L);expected.add(5L);}if(extra)expected.add(3L);if(state)expected.add(2L);
            var found=new HashSet<Long>();for(var job:result.jobs()){if(job.kind()!=PrinterQueue.Kind.BREAK)throw new AssertionError("Mining-only must not place or adjust");found.add(job.position());checks++;}
            if(!found.equals(expected))throw new AssertionError("Independent mining filters or air exclusion");checks++;
        }
        var mixed=new PrinterDiscovery.Page(1,new long[]{9},new int[]{1},new int[]{2},new int[]{k|PrinterDiscovery.REPLACEABLE|PrinterDiscovery.FLUID|PrinterDiscovery.FLUID_SOURCE},new int[]{7},new int[]{3});
        var jobs=PrinterDiscovery.search(mixed,new PrinterDiscovery.Policy(true,true,true,false,false,false,false,false)).jobs();
        if(!jobs.stream().map(PrinterQueue.Job::kind).collect(java.util.stream.Collectors.toSet()).equals(Set.of(PrinterQueue.Kind.PLACE,PrinterQueue.Kind.FILL,PrinterQueue.Kind.FLUID)))throw new AssertionError("Print fill fluid must coexist");checks++;
        return checks;
    }
}
