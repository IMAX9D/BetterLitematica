package dev.betterlitematica.selftest;

import dev.betterlitematica.core.*;
import java.util.*;

/** Deterministic low-quota moving scans; no clocks, Minecraft client, threads or world writes. */
public final class PrinterMovementChecks {
    private PrinterMovementChecks(){}
    private static final class Checks {int count;void require(boolean value,String message){count++;if(!value)throw new AssertionError(message);}}
    private static Set<Vec3i> expected(Vec3i center,double radius,PrinterRange.Shape shape){var result=new HashSet<Vec3i>();int r=(int)Math.ceil(radius);for(int y=-r;y<=r;y++)for(int z=-r;z<=r;z++)for(int x=-r;x<=r;x++){var pos=center.add(new Vec3i(x,y,z));if(PrinterRange.contains(pos,center,radius,shape))result.add(pos);}return result;}
    private static Set<Vec3i> drain(PrinterScan scan,int limit,Checks checks){var values=new HashSet<Vec3i>();int calls=0;while(scan.hasNext()){checks.require(calls++<limit,"Scan terminates within finite cells and rows");var value=scan.next();if(value!=null)values.add(value);}return values;}
    public static int run(){
        var checks=new Checks();var origin=new Vec3i(-17,32,-33);
        for(var shape:PrinterRange.Shape.values())for(String order:List.of("XYZ","XZY","YXZ","YZX","ZXY","ZYX"))for(int reverse=0;reverse<8;reverse++){
            var scan=new PrinterScan(origin,2.5,shape,order,(reverse&1)!=0,(reverse&2)!=0,(reverse&4)!=0);scan.beginTick(origin,0);
            checks.require(drain(scan,400,checks).equals(expected(origin,2.5,shape)),"Stationary scan preserves all ordered/reversed range cells");
            checks.require(scan.completedRounds()==1&&!scan.hasNext(),"One complete sweep cannot spin-restart inside its tick");
            scan.beginTick(origin,0);checks.require(!scan.hasNext(),"Same tick does not restart an exhausted sweep");scan.beginTick(origin,1);checks.require(scan.hasNext(),"Following tick rescans world changes");
        }
        for(var shape:PrinterRange.Shape.values())for(var delta:List.of(new Vec3i(1,0,0),new Vec3i(0,-1,0),new Vec3i(0,0,1),new Vec3i(1,1,-1),new Vec3i(-2,1,0))){
            var scan=new PrinterScan(origin,3.5,shape,"XYZ",false,false,false);scan.beginTick(origin,0);drain(scan,1000,checks);
            var moved=origin.add(delta);scan.beginTick(moved,0);var added=expected(moved,3.5,shape);added.removeAll(expected(origin,3.5,shape));
            checks.require(drain(scan,1000,checks).equals(added),"Movement frontier equals exact new-minus-old shape, including diagonal motion");
        }
        for(var shape:PrinterRange.Shape.values()){
            var scan=new PrinterScan(Vec3i.ZERO,3,shape,"XYZ",false,false,false);scan.beginTick(new Vec3i(1,0,0),1);
            checks.require(new Vec3i(4,0,0).equals(scan.next()),"New central frontier is discovered before the old low-coordinate prefix");
            var moving=new PrinterScan(Vec3i.ZERO,5,shape,"XYZ",false,false,false);var seen=new HashSet<Vec3i>();var legacySeen=new HashSet<Vec3i>();
            var common=expected(Vec3i.ZERO,5,shape);common.retainAll(expected(new Vec3i(1,0,0),5,shape));
            for(int tick=0;tick<180;tick++){
                var center=new Vec3i(tick&1,0,0);moving.beginTick(center,tick);var legacy=new PrinterRange(center,5,shape,"XYZ",false,false,false);
                for(int cell=0;cell<64;cell++){
                    if(moving.hasNext()){var value=moving.next();if(value!=null){checks.require(PrinterRange.contains(value,center,5,shape),"Moving scan never returns outside current range");seen.add(value);}}
                    if(legacy.hasNext()){var value=legacy.next();if(value!=null)legacySeen.add(value);}
                }
                checks.require(moving.pendingFrontiers()<=2,"Continuous movement keeps at most two lazy frontiers");
            }
            checks.require(seen.containsAll(common),"Persistent interior sweep completes despite crossing a block boundary every tick");
            checks.require(!legacySeen.containsAll(common),"Regression workload actually starves when the cursor restarts each movement");
            checks.require(moving.completedRounds()>0,"Movement never perpetually resets the full sweep");
            var advancing=new PrinterScan(Vec3i.ZERO,3,shape,"XYZ",false,false,false);var firstSeen=new HashMap<Vec3i,Integer>();
            for(int tick=1;tick<=126;tick++){
                var center=new Vec3i(tick,0,0);advancing.beginTick(center,tick);
                for(int cell=0;cell<96&&advancing.hasNext();cell++){var value=advancing.next();if(value!=null){checks.require(PrinterRange.contains(value,center,3,shape),"Forward motion discards stale unreachable samples");firstSeen.putIfAbsent(value,tick);}}
                checks.require(advancing.pendingFrontiers()<=2,"Forward-motion backlog remains constant size");
            }
            for(int entered=1;entered<=120;entered++){Integer observed=firstSeen.get(new Vec3i(entered+3,0,0));checks.require(observed!=null&&observed<=entered+6,"New forward-center cells are scanned before leaving reach: "+shape+" / "+entered);}
            var far=new Vec3i(10000,100,10000);advancing.beginTick(far,127);var teleported=drain(advancing,1500,checks);
            checks.require(teleported.equals(expected(far,3,shape)),"Teleport drops only wholly unreachable scans and covers destination");
        }
        // Moving changes the work frontier, not the engine's generation/queue/cooldown ownership.
        var queue=new PrinterQueue(16);var pacing=new PrinterPacing();pacing.begin(10,0,4,6,1,1);pacing.dispatched(42,false);
        var retained=new PrinterQueue.Job(7,42,1,0,PrinterQueue.Kind.PLACE);queue.offer(retained,1);var scan=new PrinterScan(Vec3i.ZERO,5,PrinterRange.Shape.CUBE,"XYZ",false,false,false);
        for(int tick=11;tick<16;tick++){scan.beginTick(new Vec3i(tick&1,0,0),tick);pacing.begin(tick,0,4,6,1,1);checks.require(queue.size()==1&&pacing.cooling(42),"Range motion leaves queued task and its cooldown owned");}
        checks.require(queue.poll(1,64).equals(retained),"Reachable queued job keeps its original generation and material bucket");
        return checks.count;
    }
    public static void main(String[] args){System.out.println("PASS PrinterMovementChecks: "+run()+" checks");}
}
