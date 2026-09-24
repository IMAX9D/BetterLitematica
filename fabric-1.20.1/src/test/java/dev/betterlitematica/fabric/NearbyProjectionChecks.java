package dev.betterlitematica.fabric;

import dev.betterlitematica.core.*;
import net.minecraft.block.Blocks;
import net.minecraft.state.property.Properties;
import net.minecraft.util.math.*;
import java.util.*;

/** Real block-state equality and the production bounded vicinity updater; no live world. */
public final class NearbyProjectionChecks {
    private static int checks;
    private static void check(boolean value,String message){checks++;if(!value)throw new AssertionError(message);}
    public static int run(){checks=0;
        var stone=Blocks.STONE.getDefaultState();var air=Blocks.AIR.getDefaultState();var wanted=new ProjectionController.PrinterSample(true,stone);
        check(NearbyProjectionHighlights.classify(wanted,air)==NearbyProjectionHighlights.Kind.MISSING,"Expected solid/actual air is orange missing");
        check(NearbyProjectionHighlights.classify(wanted,Blocks.GLASS.getDefaultState())==NearbyProjectionHighlights.Kind.WRONG,"Wrong block is red");
        var stairs=Blocks.OAK_STAIRS.getDefaultState();check(NearbyProjectionHighlights.classify(new ProjectionController.PrinterSample(true,stairs),stairs.with(Properties.HORIZONTAL_FACING,stairs.get(Properties.HORIZONTAL_FACING).getOpposite()))==NearbyProjectionHighlights.Kind.WRONG,"Same block wrong properties are red");
        check(NearbyProjectionHighlights.classify(wanted,stone)==null,"Matching state is not highlighted");
        check(NearbyProjectionHighlights.classify(wanted,null)==null,"Unloaded actual world is not air");
        check(NearbyProjectionHighlights.classify(new ProjectionController.PrinterSample(true,null),air)==null,"Unknown projection data is not missing");
        check(NearbyProjectionHighlights.classify(new ProjectionController.PrinterSample(false,null),air)==null&&NearbyProjectionHighlights.classify(new ProjectionController.PrinterSample(true,air),stone)==NearbyProjectionHighlights.Kind.EXTRA,"Outside is ignored, expected air and solid actual is extra");
        var overlay=new NearbyProjectionHighlights();Object world=new Object(),source=new Object();var eye=new Vec3d(.5,.5,.5);int[] read={0};
        for(int i=0;i<24;i++){read[0]=0;overlay.advance(world,List.of(source),LayerRange.ALL,eye,5,()->p->wanted,p->{read[0]++;return air;},()->0L);check(read[0]<=512,"World reads remain within512-cell tick allowance");}
        check(!overlay.marks().isEmpty()&&overlay.marks().size()<=2048,"Stationary scan populates bounded nearby cache");
        for(var mark:overlay.marks())check(PrinterReach.distanceSquared(eye,BlockPos.fromLong(mark.position()))<=25,"Every stored marker is physically reachable");
        var mark=overlay.marks().iterator().next();var pos=BlockPos.fromLong(mark.position());overlay.changed(pos,stone);
        check(overlay.marks().stream().noneMatch(m->m.position()==pos.asLong()),"World block callback removes completed marker immediately");
        var second=overlay.marks().iterator().next();var secondPos=BlockPos.fromLong(second.position());overlay.changed(secondPos,Blocks.GLASS.getDefaultState());check(overlay.marks().stream().anyMatch(m->m.position()==secondPos.asLong()&&m.kind()==NearbyProjectionHighlights.Kind.WRONG),"Changed cached missing block becomes wrong without waiting for scan");
        overlay.changed(secondPos,air);check(overlay.marks().stream().anyMatch(m->m.position()==secondPos.asLong()&&m.kind()==NearbyProjectionHighlights.Kind.MISSING),"Wrong block removed becomes missing immediately");
        overlay.chunkChanged(secondPos.getX()>>4,secondPos.getZ()>>4);check(overlay.marks().stream().noneMatch(m->{var p=BlockPos.fromLong(m.position());return (p.getX()>>4)==(secondPos.getX()>>4)&&(p.getZ()>>4)==(secondPos.getZ()>>4);}),"Chunk transition drops uncertain cached markers");
        overlay.advance(world,List.of(source),LayerRange.ALL,new Vec3d(100,.5,.5),5,()->p->wanted,p->air,()->0L);
        check(overlay.marks().stream().allMatch(m->PrinterReach.distanceSquared(new Vec3d(100,.5,.5),BlockPos.fromLong(m.position()))<=25),"Teleport cannot retain highlights around old player position");
        overlay.advance(world,List.of(new Object()),LayerRange.ALL,eye,5,()->p->new ProjectionController.PrinterSample(true,null),p->air,()->0L);check(overlay.marks().isEmpty(),"Source replacement drops stale markers before unknown rescan");
        read[0]=0;long[] clock={0};overlay.advance(world,List.of(source),LayerRange.ALL,eye,5,()->p->wanted,p->{read[0]++;return air;},()->{long v=clock[0];clock[0]+=100_000;return v;});check(read[0]<=7,"Time deadline stops scan before cell quota");
        overlay.clear();check(overlay.marks().isEmpty(),"Disconnect/toggle clear releases all cache entries");
        check(Math.abs(NearbyProjectionHighlights.alpha(0)-NearbyProjectionHighlights.alpha(1_800_000_000L))<.00001,"Breathing repeats every1.8seconds");
        check(NearbyProjectionHighlights.alpha(450_000_000L)>NearbyProjectionHighlights.alpha(0)&&NearbyProjectionHighlights.alpha(1_350_000_000L)<NearbyProjectionHighlights.alpha(0),"Pulse has increasing and decreasing phases");
        for(long t=0;t<1_800_000_000L;t+=10_000_000L){float alpha=NearbyProjectionHighlights.alpha(t);check(alpha>=.239f&&alpha<=.721f,"Pulse stays visible and bounded");}
        overlay.clear();var missingAhead=new dev.betterlitematica.core.Vec3i(7,0,0);var wrongAhead=new dev.betterlitematica.core.Vec3i(0,0,7);
        for(int i=0;i<32;i++)overlay.advance(world,List.of(source),LayerRange.ALL,eye,NearbyProjectionHighlights.OBSERVATION_RADIUS,()->p->p.equals(missingAhead)||p.equals(wrongAhead)?wanted:new ProjectionController.PrinterSample(false,null),p->p.getZ()==7?Blocks.GLASS.getDefaultState():air,()->0L);
        check(overlay.marks().stream().anyMatch(m->m.kind()==NearbyProjectionHighlights.Kind.MISSING&&BlockPos.fromLong(m.position()).getX()==7),"Missing preview remains outside creative printer reach");
        check(overlay.marks().stream().anyMatch(m->m.kind()==NearbyProjectionHighlights.Kind.WRONG&&BlockPos.fromLong(m.position()).getZ()==7),"Wrong preview remains outside creative printer reach");
        overlay.clear();long[] slow={0};int[] sampled={0};
        for(int i=0;i<1000;i++)overlay.advance(world,List.of(source),LayerRange.ALL,eye,1,()->{slow[0]+=1_000_000;return p->{sampled[0]++;return wanted;};},p->air,()->slow[0]);
        check(sampled[0]>0,"Slow snapshot setup cannot permanently starve cursor progress");
        var settings=PrinterSettings.read(new com.google.gson.JsonObject());
        check(settings.highlightOnTop,"New configuration defaults to unified overlay on top");
        settings.highlightOnTop=false;
        var restored=PrinterSettings.read(settings.snapshot());
        check(!restored.highlightOnTop,"Unified depth preference persists when disabled");
        var legacy=new com.google.gson.JsonObject();legacy.addProperty("highlightThrough",false);legacy.addProperty("errorHighlightOnTop",true);
        var migrated=PrinterSettings.read(legacy);check(migrated.highlightOnTop,"An enabled legacy highlight setting migrates to on top");
        migrated.highlightOnTop=false;var saved=migrated.snapshot();check(!saved.has("highlightThrough")&&!saved.has("errorHighlightOnTop")&&!PrinterSettings.read(saved).highlightOnTop,"Old switches cannot override saved unified switch");
        overlay.clear();
        for(int i=0;i<40;i++)overlay.advance(world,List.of(source),LayerRange.ALL,eye,8,()->p->new ProjectionController.PrinterSample(true,air),p->stone,()->0L);
        check(overlay.marks().size()>2048,"Nearby cache does not truncate at2048");
        check(overlay.marks().stream().allMatch(m->m.kind()==NearbyProjectionHighlights.Kind.EXTRA),"Extras work without verifier");
        var extra=overlay.marks().iterator().next();overlay.changed(BlockPos.fromLong(extra.position()),air);
        check(overlay.marks().stream().noneMatch(m->m.position()==extra.position()),"Removed extra disappears immediately");
        overlay.advance(world,List.of(source),LayerRange.ALL,eye,2,()->p->new ProjectionController.PrinterSample(true,air),p->stone,()->0L);
        check(overlay.marks().stream().allMatch(m->PrinterReach.distanceSquared(eye,BlockPos.fromLong(m.position()))<=4),"Reducing range clears distant marks");
        return checks;
    }
    public static void main(String[] args){net.minecraft.SharedConstants.createGameVersion();net.minecraft.Bootstrap.initialize();System.out.println("NearbyProjectionChecks: "+run()+" checks");}
}
