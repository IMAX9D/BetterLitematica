package dev.betterlitematica.fabric;

import dev.betterlitematica.core.*;
import java.util.*;
import java.util.function.Function;

/** Pure incremental production-state checks; no Minecraft world is constructed. */
public final class ToolSelectionResizeChecks {
    private static int checks;
    private static void check(boolean condition,String message){checks++;if(!condition)throw new AssertionError(message);}
    private static AreaSelection area(Vec3i a,Vec3i b){return new AreaSelection(List.of(new SelectionBox("a",a,b)),"a",new Vec3i(12,7,-3),false);}
    private static AreaSelection run(AreaSelection original,boolean grow,Function<Vec3i,Boolean> cells,int budget){
        var operation=new ToolSelectionResize(original,grow);int[] calls={0};boolean complete=false;
        for(int tick=0;tick<100000&&!complete;tick++){calls[0]=0;complete=operation.tick(p->{calls[0]++;return cells.apply(p);},budget,Long.MAX_VALUE);check(calls[0]<=budget,"Each slice respects its actual world-query budget");}
        check(complete,"Bounded test volume finishes");return operation.result();
    }
    public static int run(){checks=0;
        var origin=new Vec3i(-10,-39,-8);var seed=area(origin,origin);var before=seed;
        var diagonal=Set.of(origin,origin.add(new Vec3i(1,1,1)));
        var grown=run(seed,true,diagonal::contains,1);check(grown.current().region().min().equals(origin)&&grown.current().region().size().equals(new Vec3i(2,2,2)),"Growth includes diagonal adjacency on successive six-face scans");check(seed==before&&seed.current().region().volume()==1,"The original is never mutated by partial or complete scans");check(grown.origin().equals(seed.origin()),"Resizing preserves the independent selection origin");
        grown=run(seed,true,p->p.equals(origin)||p.equals(origin.add(new Vec3i(2,0,0))),3);check(grown.current().equals(seed.current()),"An empty separating shell stops growth before a detached block");
        var solid=new Region("solid",new Vec3i(-9,-40,-7),new Vec3i(3,2,4));
        for(int signs=0;signs<8;signs++){
            int[] lo={-12,-43,-10},hi={-3,-35,0};var first=new Vec3i((signs&1)==0?lo[0]:hi[0],(signs&2)==0?lo[1]:hi[1],(signs&4)==0?lo[2]:hi[2]);var second=new Vec3i((signs&1)==0?hi[0]:lo[0],(signs&2)==0?hi[1]:lo[1],(signs&4)==0?hi[2]:lo[2]);
            var trimmed=run(area(first,second),false,solid::contains,7);check(trimmed.current().region().min().equals(solid.min())&&trimmed.current().region().size().equals(solid.size()),"Shrink removes only empty layers around occupied content");
            var a=trimmed.current().first();var b=trimmed.current().second();check((a.x()<=b.x())==((signs&1)==0)&&(a.y()<=b.y())==((signs&2)==0)&&(a.z()<=b.z())==((signs&4)==0),"Original corner orientation is retained on every axis");
        }
        var empty=run(area(new Vec3i(-3,-39,-1),new Vec3i(3,-33,5)),false,p->false,4);check(empty.current().region().volume()==1,"Completely empty selections collapse safely to one cell without inversion");
        var single=run(seed,false,p->false,1);check(single.current().equals(seed.current()),"A single empty cell remains a valid box");
        var hollowGrow=run(seed,true,p->false,2);check(hollowGrow.current().equals(seed.current()),"Empty grow does not translate or invert its source");
        var pending=new ToolSelectionResize(seed,true);int[] calls={0};check(!pending.tick(p->{calls[0]++;return true;},1,System.nanoTime()-1)&&calls[0]==0,"An expired deadline performs no world queries");
        check(!pending.tick(p->{calls[0]++;return true;},0,Long.MAX_VALUE)&&calls[0]==0,"Zero allowance performs no world queries");
        Vec3i[] unknown={null};check(!pending.tick(p->{unknown[0]=p;return null;},16,Long.MAX_VALUE)&&pending.status().contains("等待"),"Unknown data pauses the face instead of treating it as air");
        Vec3i[] retried={null};check(!pending.tick(p->{retried[0]=p;return null;},16,Long.MAX_VALUE)&&Objects.equals(unknown[0],retried[0]),"The exact unresolved cell is retried without rescanning the prefix");
        boolean blocked=false;try{pending.result();}catch(IllegalStateException expected){blocked=true;}check(blocked,"Partial bounds cannot be published as a completed result");
        boolean done=false;for(int i=0;i<1000&&!done;i++)done=pending.tick(diagonal::contains,5,Long.MAX_VALUE);check(done&&pending.result().current().region().size().equals(new Vec3i(2,2,2)),"Loading the missing data resumes to the same complete result");
        var infinite=run(area(Vec3i.ZERO,Vec3i.ZERO),true,p->true,2);check(infinite.current().region().min().equals(new Vec3i(-256,-256,-256))&&infinite.current().region().size().equals(new Vec3i(513,513,513)),"Infinite occupied space stops after precisely 256 rounds");
        var other=new SelectionBox("b",new Vec3i(80,90,100),new Vec3i(81,91,101));var multiple=new AreaSelection(List.of(seed.current(),other),"a",seed.origin(),false);var result=run(multiple,true,diagonal::contains,5);check(result.boxes().stream().filter(b->b.name().equals("b")).findFirst().orElseThrow().equals(other),"Only the selected box changes in a multi-box selection");
        return checks;
    }
    public static void main(String[] args){System.out.println("Tool selection resize checks passed: "+run());}
}
