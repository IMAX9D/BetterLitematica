package dev.betterlitematica.fabric;

import dev.betterlitematica.core.*;
import net.minecraft.block.Blocks;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import java.util.*;

/** No geometry topology switches while the player moves: each unchanged cell owns a stable unit box. */
public final class NearbyStabilityChecks {
 private static int checks;
 private static final Object WORLD=new Object(),SOURCE=new Object();
 private static final ProjectionController.PrinterSample WANTED=new ProjectionController.PrinterSample(true,Blocks.STONE.getDefaultState());
 public static int run(){checks=0;var nearby=new NearbyProjectionHighlights();var anchor=new BlockPos(0,-40,0);var eye=new Vec3d(.5,-39.2,.5);
  for(int i=0;i<20;i++)step(nearby,eye,null);
  var initial=find(nearby,anchor);require(initial!=null,"interior marked before movement");
  var fixedList=nearby.boxes();step(nearby,eye,null);require(nearby.boxes()==fixedList,"unchanged marks reuse whole snapshot");
  for(int tick=0;tick<60;tick++){
   var next=new Vec3d(.5+2.8*Math.sin(tick*.22),-39.2,.5+1.5*Math.cos(tick*.22));step(nearby,next,null);
   require(find(nearby,anchor)==initial,"interior box identity retained during changing boundary");
   require(nearby.boxes().size()==nearby.marks().size(),"exactly one box per marker");
   for(var box:nearby.boxes())require(box.min().equals(box.max()),"every published box remains one cell");
  }
  step(nearby,eye,null);var neighboring=find(nearby,new BlockPos(1,-40,0));
  nearby.changed(anchor,Blocks.STONE.getDefaultState());require(find(nearby,anchor)==null,"completed cell disappears before next scan");
  require(find(nearby,new BlockPos(1,-40,0))==neighboring,"repair leaves adjacent box unchanged");
  step(nearby,eye,anchor);require(find(nearby,anchor)==null,"correct cell never reappears during refresh");
  nearby.changed(anchor,Blocks.AIR.getDefaultState());step(nearby,eye,null);var restored=find(nearby,anchor);
  require(restored!=null&&restored.min().equals(restored.max()),"new error appears without deferred geometry build");
  nearby.changed(anchor,Blocks.GLASS.getDefaultState());var wrong=find(nearby,anchor);
  require(wrong!=null&&wrong.group()==NearbyProjectionHighlights.Kind.WRONG.ordinal()&&wrong!=restored,"classification change replaces only affected box");
  require(find(nearby,new BlockPos(1,-40,0))==neighboring,"classification change leaves adjacent box unchanged");
  nearby.changed(anchor,Blocks.AIR.getDefaultState());var orange=find(nearby,anchor);
  int before=nearby.marks().size();var snapshot=nearby.boxes();
  nearby.chunkChanged(0,0,true);require(nearby.marks().size()==before&&nearby.boxes()==snapshot&&find(nearby,anchor)==orange,"loaded chunk refresh never clears known geometry");
  require(nearby.eventCount()>0&&nearby.eventCount()<=4096,"loaded chunk refresh queues bounded priority checks");
  for(int i=0;i<12;i++)step(nearby,eye,anchor);
  require(find(nearby,anchor)==null,"loaded chunk refresh confirms repaired block");
  step(nearby,eye,null);nearby.chunkChanged(0,0,false);
  require(nearby.marks().stream().noneMatch(m->{var p=BlockPos.fromLong(m.position());return p.getX()>>4==0&&p.getZ()>>4==0;}),"unloaded chunk drops all its marks immediately");
  require(nearby.boxes().stream().noneMatch(b->b.min().x()>>4==0&&b.min().z()>>4==0),"unloaded chunk has no stale geometry");
  step(nearby,new Vec3d(100,-39.2,100),null);
  require(find(nearby,anchor)==null,"teleport does not preserve departed cell");
  nearby.clear();require(nearby.boxes().isEmpty(),"session clear releases all boxes");return checks;
 }
 private static void step(NearbyProjectionHighlights value,Vec3d eye,BlockPos completed){int[] reads={0};value.advance(WORLD,List.of(SOURCE),LayerRange.ALL,eye,8,()->p->WANTED,p->{reads[0]++;return p.equals(completed)?Blocks.STONE.getDefaultState():Blocks.AIR.getDefaultState();},()->0L);require(reads[0]<=512,"sampling cell budget unchanged");}
 private static HighlightCuboids.Box find(NearbyProjectionHighlights value,BlockPos pos){for(var box:value.boxes())if(box.min().equals(new Vec3i(pos.getX(),pos.getY(),pos.getZ())))return box;return null;}
 private static void require(boolean condition,String message){checks++;if(!condition)throw new AssertionError(message);}
}
