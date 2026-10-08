package dev.betterlitematica.selftest;

import dev.betterlitematica.core.*;
import java.util.*;

/** Geometric invariants and hand-calculated power arrangements; no Minecraft dependency. */
public final class BedrockPlanChecks {
    private static int checks;
    private static void check(boolean value,String message){checks++;if(!value)throw new AssertionError(message);}
    private static void rejects(Runnable operation,Class<? extends RuntimeException> type){try{operation.run();throw new AssertionError("Expected "+type);}catch(RuntimeException expected){check(type.isInstance(expected),"Correct invalid-coordinate exception");}}
    public static int run(){
        checks=0;var origin=Vec3i.ZERO;var layouts=BedrockPlan.layouts(origin);
        check(!layouts.isEmpty()&&layouts.size()<=BedrockPlan.MAX_LAYOUTS,"Finite useful candidate set");
        check(new HashSet<>(layouts).size()==layouts.size(),"Overlapping direct/QC candidates are deduplicated");
        check(layouts.equals(BedrockPlan.layouts(origin)),"Stable order permits deterministic retry planning");
        var sides=new HashSet<Vec3i>();var faces=new HashSet<Integer>();boolean through=false,qc=false;
        for(var p:layouts){
            sides.add(p.piston());faces.add(p.initialFace());
            check(p.target().equals(origin)&&distance(p.piston(),origin)==1,"Each piston is immediately adjacent to the target");
            check(p.initialFace()==0||p.initialFace()==1,"Initial piston is vertical");
            check(BedrockPlan.offset(p.piston(),p.initialFace()).equals(p.head()),"Head belongs to its initial piston direction");
            check(BedrockPlan.offset(p.piston(),p.breakFace()).equals(origin),"Replacement piston points exactly toward the intended target");
            check(!p.head().equals(origin)&&!p.torch().equals(origin),"Existing target is neither a head nor a torch cell");
            check(new HashSet<>(List.of(p.piston(),p.head(),p.torch(),p.support())).size()==4,"Construction cells do not overlap");
            check(p.torchFace()>0&&p.torchFace()<6&&BedrockPlan.offset(p.support(),p.torchFace()).equals(p.torch()),"Torch has a real support face and is never upside down");
            if(p.throughTarget()){through=true;check(p.piston().equals(new Vec3i(0,1,0))&&p.torch().equals(new Vec3i(0,-1,0)),"Through-target power uses a torch underneath and a piston above");}
            else if(distance(p.torch(),p.piston())>1){qc=true;check(distance(p.torch(),p.piston().add(new Vec3i(0,1,0)))==1,"Non-direct power belongs to the upper QC neighborhood");}
        }
        check(sides.size()==6&&faces.equals(Set.of(0,1))&&through&&qc,"All target directions, vertical facings and special power families are represented");
        check(has(layouts,new Vec3i(1,0,0),new Vec3i(1,-1,0),1,1,false),"Standing torch below a side piston legitimately powers upward");
        check(!has(layouts,new Vec3i(1,0,0),new Vec3i(1,1,0),0,1,false),"Standing torch above a piston neither powers downward nor attaches to that piston");
        check(has(layouts,new Vec3i(1,0,0),new Vec3i(2,1,0),1,1,false),"Standing torch beside the upper piston cell provides quasi-connectivity");
        check(!has(layouts,new Vec3i(1,0,0),new Vec3i(2,1,0),1,5,false),"Wall torch does not emit toward its upper QC receiver/support");
        check(has(layouts,new Vec3i(1,0,0),new Vec3i(2,1,0),1,4,false),"Oppositely attached wall torch powers the upper QC receiver");
        var delta=new Vec3i(-123,-39,456);var shifted=BedrockPlan.layouts(delta);
        check(shifted.size()==layouts.size(),"Negative Y and coordinates do not alter candidate counts");
        for(int i=0;i<layouts.size();i++){var a=layouts.get(i);var b=shifted.get(i);check(b.piston().equals(a.piston().add(delta))&&b.head().equals(a.head().add(delta))&&b.torch().equals(a.torch().add(delta))&&b.support().equals(a.support().add(delta))&&b.initialFace()==a.initialFace()&&b.breakFace()==a.breakFace()&&b.torchFace()==a.torchFace()&&b.throughTarget()==a.throughTarget(),"Translation preserves every plan and its ordering");}
        for(int face=0;face<6;face++)check(BedrockPlan.offset(BedrockPlan.offset(delta,face),face^1).equals(delta),"Fixed direction IDs have correct opposite pairs");
        rejects(()->layouts.clear(),UnsupportedOperationException.class);
        rejects(()->BedrockPlan.offset(origin,-1),IllegalArgumentException.class);rejects(()->BedrockPlan.offset(origin,6),IllegalArgumentException.class);
        rejects(()->BedrockPlan.offset(new Vec3i(Integer.MAX_VALUE,0,0),5),ArithmeticException.class);
        rejects(()->BedrockPlan.layouts(new Vec3i(Integer.MAX_VALUE,0,0)),ArithmeticException.class);
        rejects(()->BedrockPlan.layouts(new Vec3i(0,Integer.MIN_VALUE,0)),ArithmeticException.class);
        check(!BedrockPlan.layouts(new Vec3i(30_000_000,-64,-30_000_000)).isEmpty(),"Ordinary world-border-sized coordinates remain arithmetically safe");
        int knownBedrock=PrinterDiscovery.KNOWN|PrinterDiscovery.BEDROCK;
        var obstacle=new PrinterDiscovery.Page(7,new long[]{31},new int[]{2},new int[]{1},new int[]{knownBedrock},new int[]{1|8},new int[]{-1});
        var both=new PrinterDiscovery.Policy(true,false,false,true,true,true,true,false);
        var exclusive=PrinterDiscovery.search(obstacle,both).jobs();
        check(exclusive.size()==1&&exclusive.get(0).kind()==PrinterQueue.Kind.BEDROCK&&exclusive.get(0).position()==31&&exclusive.get(0).generation()==7,
            "Mixed ordinary/native mining schedules only the piston transaction, without an earlier BREAK cooling its position");
        var ordinary=PrinterDiscovery.search(obstacle,new PrinterDiscovery.Policy(true,false,false,false,true,true,true,false)).jobs();
        check(ordinary.size()==1&&ordinary.get(0).kind()==PrinterQueue.Kind.BREAK&&ordinary.get(0).expected()==2,
            "Disabling native mining retains ordinary break behavior and the requested projection state");
        var outside=new PrinterDiscovery.Page(7,new long[]{31},new int[]{2},new int[]{1},new int[]{knownBedrock},new int[]{1},new int[]{-1});
        var outsideJobs=PrinterDiscovery.search(outside,both).jobs();
        check(outsideJobs.size()==1&&outsideJobs.get(0).kind()==PrinterQueue.Kind.BREAK,
            "Native mining does not suppress ordinary BREAK outside its own scope");
        return checks;
    }
    private static boolean has(List<BedrockPlan.Layout> all,Vec3i piston,Vec3i torch,int initial,int torchFace,boolean through){return all.stream().anyMatch(p->p.piston().equals(piston)&&p.torch().equals(torch)&&p.initialFace()==initial&&p.torchFace()==torchFace&&p.throughTarget()==through);}
    private static int distance(Vec3i a,Vec3i b){return Math.abs(a.x()-b.x())+Math.abs(a.y()-b.y())+Math.abs(a.z()-b.z());}
}
