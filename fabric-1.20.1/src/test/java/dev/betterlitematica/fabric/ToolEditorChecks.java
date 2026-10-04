package dev.betterlitematica.fabric;

import dev.betterlitematica.core.*;
import java.util.*;
import net.minecraft.block.*;
import net.minecraft.state.property.Properties;
import net.minecraft.util.math.Direction;

/** Registry-backed edit predicates and actual bounded source-domain scan, without a game world. */
public final class ToolEditorChecks {
    private static int checks;
    private static void check(boolean value,String message){checks++;if(!value)throw new AssertionError(message);}
    public static int run(){
        checks=0;var stone=Blocks.STONE.getDefaultState();var air=Blocks.AIR.getDefaultState();var glass=Blocks.GLASS.getDefaultState();
        var north=Blocks.OAK_STAIRS.getDefaultState().with(Properties.HORIZONTAL_FACING,Direction.NORTH);var east=north.with(Properties.HORIZONTAL_FACING,Direction.EAST);
        check(SchematicEditor.toolReplacement(east,north,air,false,false,false)==null,"All-state deletion excludes other orientations");
        check(SchematicEditor.toolReplacement(north,north,air,false,false,false).isAir(),"Matching complete state deletes");
        check(SchematicEditor.toolReplacement(east,north,air,true,false,false).isAir(),"Type deletion includes alternate state");
        check(SchematicEditor.toolReplacement(east,north,air,false,true,false)==null,"Except-type deletion preserves all states of the protected type");
        check(SchematicEditor.toolReplacement(stone,north,air,false,true,false).isAir(),"Except-type deletion removes other nonair types");
        check(SchematicEditor.toolReplacement(air,north,air,false,true,false)==null,"Except deletion does not record implicit air");
        var replacement=Blocks.BIRCH_STAIRS.getDefaultState();var mapped=SchematicEditor.toolReplacement(east,north,replacement,true,false,false);
        check(mapped.isOf(Blocks.BIRCH_STAIRS)&&mapped.get(Properties.HORIZONTAL_FACING)==Direction.EAST,"Type replacement preserves compatible direction");
        var wet=east.with(Properties.WATERLOGGED,true);mapped=SchematicEditor.toolReplacement(wet,north,replacement,true,false,false);
        check(mapped.get(Properties.WATERLOGGED),"Type replacement preserves compatible waterlogging");
        check(SchematicEditor.toolReplacement(east,north,stone,true,false,false)==stone,"Incompatible destination properties are ignored");
        check(SchematicEditor.toolReplacement(east,north,replacement,false,false,false)==null,"Full-state replacement does not widen into block-type replacement");
        check(SchematicEditor.toolReplacement(air,stone,glass,false,false,true)==glass,"Fill-air uses implicit source air");
        check(SchematicEditor.toolReplacement(stone,stone,glass,false,false,true)==null,"Fill-air preserves occupied cells");
        check(SchematicEditor.toolReplacement(stone,stone,stone,false,false,false)==null,"No-op replacement consumes no transaction budget");
        var regions=List.of(new Region("a",new Vec3i(-17,-2,5),new Vec3i(35,3,19)),new Region("b",new Vec3i(50,0,10),new Vec3i(2,1,2)));
        var placement=new Placement(UUID.randomUUID(),"test","test.litematic",new PlacementTransform(new Vec3i(7,4,-3),1,true,false),true,false);
        var layout=new PlacementLayout(placement,regions);var parts=List.of(layout.part(0),layout.part(1));var scan=new SchematicEditor.ToolScan(parts,LayerRange.ALL);var seen=new HashSet<SchematicEdits.Address>();
        while(!scan.cursor.done()){long before=scan.cursor.processed();scan.advance((part,local)->{check(part.region().contains(local),"Source coordinate is within original region");check(seen.add(new SchematicEdits.Address(part.section(local),part.cell(local))),"Source-qualified cells never overlap");return null;},7,Long.MAX_VALUE);check(scan.cursor.processed()-before<=7,"One slice respects work budget");}
        check(seen.size()==regions.stream().mapToLong(Region::volume).sum(),"Complete domains include every tail and implicit air cell");
        var stalled=new SchematicEditor.ToolScan(parts,LayerRange.ALL);boolean pending=false;try{stalled.advance((part,local)->{throw new ProjectionBlockView.Pending();},8,Long.MAX_VALUE);}catch(ProjectionBlockView.Pending expected){pending=true;}
        check(pending&&stalled.cursor.processed()==0&&stalled.changes.isEmpty(),"Unknown data keeps exact cursor and stages no mutation");
        var first=stalled.cursor.local();stalled.advance((part,local)->{check(local.equals(first),"Retry resumes held unknown cell");return request(part,local);},1,Long.MAX_VALUE);
        check(stalled.cursor.processed()==1&&stalled.changes.size()==1,"Loaded retry records once");
        var sliced=new SchematicEditor.ToolScan(parts,new LayerRange(LayerRange.Axis.Y,3,3));var included=new HashSet<SchematicEdits.Address>();
        while(!sliced.cursor.done())sliced.advance((part,local)->{check(part.transform().apply(local).y()==3,"Layer predicate uses world-transformed position");included.add(new SchematicEdits.Address(part.section(local),part.cell(local)));return null;},512,Long.MAX_VALUE);
        check(included.size()==35*19,"World layer restricts complete-source traversal");
        var big=new Region("large",Vec3i.ZERO,new Vec3i(SchematicEdits.MAX_TRANSACTION+1,1,1));var largeLayout=new PlacementLayout(placement,List.of(big));var bounded=new SchematicEditor.ToolScan(List.of(largeLayout.part(0)),LayerRange.ALL);boolean rejected=false;
        try{while(!bounded.cursor.done())bounded.advance(ToolEditorChecks::request,2048,Long.MAX_VALUE);}catch(IllegalArgumentException expected){rejected=true;}
        check(rejected&&bounded.changes.size()==SchematicEdits.MAX_TRANSACTION,"Transaction overflow is rejected without unbounded allocation");
        check(bounded.cursor.processed()==SchematicEdits.MAX_TRANSACTION,"Overflow is not silently skipped or marked complete");
        var emptyDeadline=new SchematicEditor.ToolScan(parts,LayerRange.ALL);emptyDeadline.advance((part,local)->{throw new AssertionError("expired slice read");},2048,0);check(emptyDeadline.cursor.processed()==0,"Expired deadline performs no source read");
        var metadata=new BlueprintMetadata("test",3465,"0".repeat(64),List.of(BlockStateSpec.AIR,BlockStateSpec.parse("minecraft:stone")),List.of(new Region("a",Vec3i.ZERO,new Vec3i(2,1,1))),List.of());
        var original=new SchematicEdits(metadata,new long[]{0,2});var candidate=original.fork();candidate.apply(List.of(new SchematicEdits.Request(new SectionKey(0,0,0,0),0,1,BlockStateSpec.AIR,false,false,true),new SchematicEdits.Request(new SectionKey(0,0,0,0),1,1,BlockStateSpec.AIR,false,false,true)));
        check(!original.dirty()&&candidate.dirty(),"Staged transaction leaves original draft unchanged");candidate.undo();check(!candidate.dirty(),"Whole admitted edit undoes in one action");candidate.redo();check(candidate.snapshot().sections().get(new SectionKey(0,0,0,0)).size()==2,"Redo restores the complete admitted edit");
        for(int turn=0;turn<4;turn++)for(boolean mirror:new boolean[]{false,true}){var t=new PlacementTransform(new Vec3i(5,7,-2),turn,mirror,false);var world=new StateResolver1201(List.of(StateResolver1201.spec(east)),t).resolve(0);var restored=StateResolver1201.unplace(world,t);check(restored.equals(east),"Changed orientation is stored back in source coordinates");}
        return checks;
    }
    private static SchematicEdits.Request request(PlacementLayout.Part part,Vec3i local){return new SchematicEdits.Request(part.section(local),part.cell(local),1,BlockStateSpec.AIR,false,false,true);}
}
