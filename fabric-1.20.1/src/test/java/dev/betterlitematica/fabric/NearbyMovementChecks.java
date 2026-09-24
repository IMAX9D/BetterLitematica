package dev.betterlitematica.fabric;

import dev.betterlitematica.core.*;
import net.minecraft.block.Blocks;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import java.util.*;

/** Exact visible-boundary, event and asynchronous-source retry regressions, no active world. */
public final class NearbyMovementChecks {
 private static int checks;
 private static final Object WORLD=new Object(),SOURCE=new Object();
 private static final ProjectionController.PrinterSample WANTED=new ProjectionController.PrinterSample(true,Blocks.STONE.getDefaultState());
 private static final ProjectionController.PrinterSample OUTSIDE=new ProjectionController.PrinterSample(false,null);
 public static int run(){checks=0;
  arrival(new Vec3d(.5,-39.2,.5),new Vec3d(1.5,-39.2,.5),new BlockPos(9,-40,0),"positive X boundary");
  arrival(new Vec3d(.5,-39.2,.5),new Vec3d(.5,-39.2,1.5),new BlockPos(0,-40,9),"positive Z boundary");
  arrival(new Vec3d(.80,-39.2,.5),new Vec3d(.82,-39.2,.5),new BlockPos(8,-39,4),"same-cell 0.02 movement");
  arrival(new Vec3d(-20.8,-39.2,-20.5),new Vec3d(-19.8,-39.2,-20.5),new BlockPos(-12,-40,-21),"negative world coordinates");
  var random=new Random(528);for(int run=0;run<24;run++){
   var before=new Vec3d(random.nextDouble()*2-1,-39+random.nextDouble(),random.nextDouble()*2-1);
   var after=before.add(random.nextDouble()-.5,random.nextDouble()-.5,random.nextDouble()-.5);
   var scan=new NearbyScan(before,3);scan.begin(after);var found=new HashSet<Vec3i>();int[] rows={10000};
   while(scan.hasFrontier()){var at=scan.frontier(rows);if(at!=null)found.add(at);}
   for(int y=-44;y<=-34;y++)for(int z=-5;z<=5;z++)for(int x=-5;x<=5;x++){
    var p=new BlockPos(x,y,z);boolean entered=PrinterReach.distanceSquared(after,p)<=9&&PrinterReach.distanceSquared(before,p)>9;
    require(found.contains(new Vec3i(x,y,z))==entered,"exact fractional/negative AABB sphere frontier");
   }
  }
  var overlay=new NearbyProjectionHighlights();int[] reads={0};
  for(int t=0;t<40;t++){
   var eye=new Vec3d(.5+t,-39.2,.5);reads[0]=0;
   overlay.advance(WORLD,List.of(SOURCE),LayerRange.ALL,eye,8,()->p->WANTED,p->{reads[0]++;return Blocks.AIR.getDefaultState();},()->0L);
   require(reads[0]<=512,"continuous movement sampling budget");
   if(t>0){var edge=new BlockPos(t+8,-40,0);require(has(overlay,edge),"continuous flight front edge has no sweep starvation");require(shown(overlay,edge),"new marks reach geometry immediately");}
  }
  overlay.clear();var eye=new Vec3d(.5,-39.2,.5);var event=new BlockPos(-7,-40,0);boolean[] changed={false};
  overlay.advance(WORLD,List.of(SOURCE),LayerRange.ALL,eye,8,()->p->WANTED,p->Blocks.STONE.getDefaultState(),()->0L);
  require(!has(overlay,event),"matched position has no previous marker");overlay.changed(event,Blocks.AIR.getDefaultState());
  overlay.advance(WORLD,List.of(SOURCE),LayerRange.ALL,eye,8,()->p->WANTED,p->p.equals(event)?Blocks.AIR.getDefaultState():Blocks.STONE.getDefaultState(),()->0L);
  require(has(overlay,event)&&shown(overlay,event),"uncached changed position bypasses full sweep");
  overlay.changed(event,Blocks.STONE.getDefaultState());require(!shown(overlay,event),"removed mark cannot leave old merged box visible");
  overlay.clear();var unknownPos=new BlockPos(8,-40,0);boolean[] loaded={false};
  for(int i=0;i<3;i++)overlay.advance(WORLD,List.of(SOURCE),LayerRange.ALL,eye,8,()->p->p.equals(vec(unknownPos))?new ProjectionController.PrinterSample(true,loaded[0]?Blocks.STONE.getDefaultState():null):OUTSIDE,p->Blocks.AIR.getDefaultState(),()->0L);
  require(!has(overlay,unknownPos)&&overlay.unknownCount()>0,"unknown is pending, never air");loaded[0]=true;
  for(int i=0;i<3&&!has(overlay,unknownPos);i++)overlay.advance(WORLD,List.of(SOURCE),LayerRange.ALL,eye,8,()->p->p.equals(vec(unknownPos))?WANTED:OUTSIDE,p->Blocks.AIR.getDefaultState(),()->0L);
  require(has(overlay,unknownPos),"source arrival retries without full sweep");
  overlay.clear();for(int i=0;i<30;i++)overlay.advance(WORLD,List.of(SOURCE),LayerRange.ALL,eye,32,()->p->new ProjectionController.PrinterSample(true,null),p->Blocks.AIR.getDefaultState(),()->0L);
  require(overlay.unknownCount()<=4096,"unknown queue is bounded at maximum range");
  var slow=new NearbyProjectionHighlights();long[] clock={0};int[] visits={0};
  slow.advance(WORLD,List.of(SOURCE),LayerRange.ALL,eye,8,()->{clock[0]+=10_000_000;return p->WANTED;},p->{visits[0]++;return Blocks.AIR.getDefaultState();},()->{long now=clock[0];clock[0]+=100_000;return now;});
  require(visits[0]>1&&visits[0]<=7,"snapshot preparation cannot consume sampling allowance, sample time remains bounded");
  overlay.clear();overlay.advance(WORLD,List.of(new Object()),LayerRange.ALL,eye,8,()->p->new ProjectionController.PrinterSample(true,null),p->Blocks.AIR.getDefaultState(),()->0L);require(overlay.marks().isEmpty(),"source generation prevents old markers");
  return checks;
 }
 private static void arrival(Vec3d from,Vec3d to,BlockPos target,String name){
  require(PrinterReach.distanceSquared(from,target)>64&&PrinterReach.distanceSquared(to,target)<=64,name+" setup");
  var overlay=new NearbyProjectionHighlights();for(int i=0;i<24;i++)overlay.advance(WORLD,List.of(SOURCE),LayerRange.ALL,from,8,()->p->p.equals(vec(target))?WANTED:OUTSIDE,p->Blocks.AIR.getDefaultState(),()->0L);
  require(!has(overlay,target),name+" not prematurely visible");
  int ticks=0;while(ticks++<2&&!has(overlay,target))overlay.advance(WORLD,List.of(SOURCE),LayerRange.ALL,to,8,()->p->p.equals(vec(target))?WANTED:OUTSIDE,p->Blocks.AIR.getDefaultState(),()->0L);
  require(has(overlay,target),name+" appears within two ticks");require(shown(overlay,target),name+" geometry not queued behind merging");
 }
 private static Vec3i vec(BlockPos pos){return new Vec3i(pos.getX(),pos.getY(),pos.getZ());}
 private static boolean has(NearbyProjectionHighlights value,BlockPos pos){return value.marks().stream().anyMatch(m->m.position()==pos.asLong());}
 private static boolean shown(NearbyProjectionHighlights value,BlockPos p){for(var b:value.boxes())if(p.getX()>=b.min().x()&&p.getX()<=b.max().x()&&p.getY()>=b.min().y()&&p.getY()<=b.max().y()&&p.getZ()>=b.min().z()&&p.getZ()<=b.max().z())return true;return false;}
 private static void require(boolean value,String label){checks++;if(!value)throw new AssertionError(label);}
}
