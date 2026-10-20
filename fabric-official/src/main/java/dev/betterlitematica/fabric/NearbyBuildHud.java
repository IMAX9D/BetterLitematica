package dev.betterlitematica.fabric;

import dev.betterlitematica.core.*;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.client.Minecraft;
import net.minecraft.world.item.*;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.BlockPos;
import java.util.*;

/** Section results survive movement; edits invalidate only the affected sections. */
final class NearbyBuildHud {
    record Entry(Block block,ItemStack icon,int count,int wrongBlock,int wrongState) {Entry(Block block,ItemStack icon,int count){this(block,icon,count,0,0);}}
    record Snapshot(List<Entry> entries,boolean scanning,int unknown) {static final Snapshot EMPTY=new Snapshot(List.of(),false,0);}
    private record Context(Object world,List<ProjectionController.PrinterSource> sources,LayerRange layer,AreaSelection selection,long revision,double reach){}
    private static final class Result {
        int compared,matched,unknown;
        final Map<Block,Integer> counts=new HashMap<>(),wrongBlocks=new HashMap<>(),wrongStates=new HashMap<>();
        final Map<Item,Integer> needs=new HashMap<>();
    }
    private static final class Tile {
        final Vec3i key;PlacementBounds bounds;Result result;boolean complete,boundary,queued,urgent;int revision;
        Tile(Vec3i key,PlacementBounds bounds){this.key=key;this.bounds=bounds;}
    }
    private final Minecraft client;private final ProjectionController controller;
    private Context context;private final Map<Vec3i,Tile> tiles=new LinkedHashMap<>();
    private final ArrayDeque<Tile> queue=new ArrayDeque<>(),urgent=new ArrayDeque<>();private final Result total=new Result();
    private List<PlacementLayout> layouts=List.of();private boolean pendingSource;
    private Vec3i center;private net.minecraft.world.phys.Vec3 eye;
    private PlacementBounds area,plannedArea;private SectionPlan plan;private boolean replan;
    private Tile working;private Result accumulating;private CellCursor cursor;private boolean publishedPartial;private int workRevision;
    private int incomplete,turn,tick;private net.minecraft.world.phys.Vec3 observedEye;private long rounds;private Snapshot snapshot=Snapshot.EMPTY;
    NearbyBuildHud(Minecraft client,ProjectionController controller){this.client=client;this.controller=controller;}
    Snapshot snapshot(){return snapshot;}int compared(){return total.compared;}int matched(){return total.matched;}long rounds(){return rounds;}
    Map<Item,Integer> materialNeeds(){return Collections.unmodifiableMap(total.needs);}
    private boolean complete(){return plan==null&&!replan&&incomplete==0;}
    boolean completeScan(){return complete()&&total.unknown==0;}
    void clear(){context=null;tiles.clear();queue.clear();urgent.clear();working=null;cursor=null;accumulating=null;center=null;eye=null;area=null;plannedArea=null;plan=null;replan=false;incomplete=0;layouts=List.of();pendingSource=false;total.compared=total.matched=total.unknown=0;total.counts.clear();total.wrongBlocks.clear();total.wrongStates.clear();total.needs.clear();snapshot=Snapshot.EMPTY;}

    void tick(){
        var s=controller.options().printer;
        if(client.level==null||client.player==null||client.gameMode==null||!s.hud||(!s.missingHud&&controller.printer().state()==PrinterEngine.State.STOPPED)){clear();return;}
        var sources=controller.printerSources();if(sources.isEmpty()){clear();return;}
        double reach=Math.min(256,s.range==0?client.player.blockInteractionRange():Math.min(s.range,client.player.blockInteractionRange()));
        var next=new Context(client.level,sources,controller.layerRange(),controller.selection(),s.revision,reach);
        if(!next.equals(context)){
            clear();context=next;var known=new ArrayList<PlacementLayout>();
            for(var source:sources)if(source.renderer()!=null)known.add(source.renderer().layout());else if(source.metadata()!=null)known.add(new PlacementLayout(source.placement(),source.metadata().regions()));else pendingSource=true;
            layouts=List.copyOf(known);
        }
        var nextEye=client.player.getEyePosition();var nextCenter=new Vec3i((int)Math.round(nextEye.x),(int)Math.round(nextEye.y),(int)Math.round(nextEye.z));
        boolean moved=center==null||++tick%4==0&&!nextEye.equals(observedEye);eye=nextEye;center=nextCenter;if(moved)observedEye=nextEye;
        int radius=(int)Math.ceil(reach+1.5);
        if(center.y()-radius>=(client.level.getMaxY()+1)||center.y()+radius<client.level.getMinY()){clear();return;}
        area=new PlacementBounds(new Vec3i(center.x()-radius,Math.max(client.level.getMinY(),center.y()-radius),center.z()-radius),new Vec3i(center.x()+radius,Math.min((client.level.getMaxY()+1)-1,center.y()+radius),center.z()+radius));
        var layer=controller.layerRange();int[] lo={area.min().x(),area.min().y(),area.min().z()},hi={area.max().x(),area.max().y(),area.max().z()};int axis=layer.axis().ordinal();lo[axis]=Math.max(lo[axis],layer.min());hi[axis]=Math.min(hi[axis],layer.max());
        if(lo[axis]>hi[axis]){clear();return;}area=new PlacementBounds(new Vec3i(lo[0],lo[1],lo[2]),new Vec3i(hi[0],hi[1],hi[2]));
        if(moved){
            reconcile(area,this::relevant,this::fullyInside);
        }
        if(plan==null&&replan){plan=new SectionPlan(center,area);plannedArea=area;replan=false;}
        long started=System.nanoTime(),deadline=started+2_000_000L;
        for(int i=0;plan!=null&&i<512&&System.nanoTime()-started<400_000L;i++){
            if(!plan.hasNext()){plan=null;if(!plannedArea.equals(area))replan=true;break;}
            var key=plan.next();if(tiles.containsKey(key))continue;var bounds=clip(key);if(bounds==null||!relevant(bounds))continue;
            var tile=new Tile(key,bounds);tile.boundary=!fullyInside(bounds);tiles.put(key,tile);incomplete++;enqueue(tile);
        }
        var sample=controller.printerSampler();int visited=0,completed=0;
        while(visited<8192&&System.nanoTime()<deadline){
            if(working==null){
                if(completed>=Math.max(1,tiles.size()))break;
                boolean priority=!urgent.isEmpty()&&(queue.isEmpty()||(++turn&3)!=0);
                if(priority){working=urgent.removeFirst();working.urgent=false;}
                else if(!queue.isEmpty()){working=queue.removeFirst();working.queued=false;}
                if(working==null)break;
                if(tiles.get(working.key)!=working){working=null;continue;}
                cursor=new CellCursor(working.bounds);accumulating=new Result();workRevision=working.revision;publishedPartial=working.result==null;if(publishedPartial)working.result=accumulating;
            }
            var at=cursor.next();visited++;
            if(inside(at)&&inScope(at,s.printScope)){
                var pos=new BlockPos(at.x(),at.y(),at.z());
                if(client.level.getWorldBorder().isWithinBounds(pos)){
                    var projected=sample.apply(at);
                    if(projected!=null&&projected.inside()){
                        if(projected.state()==null||!WorldChunks.loaded(client.level,pos)){accumulating.unknown++;if(publishedPartial)total.unknown++;}
                        else observe(accumulating,projected.state(),client.level.getBlockState(pos),publishedPartial);
                    }
                }
            }
            if(!cursor.hasNext()){
                if(!publishedPartial){merge(total,working.result,-1);merge(total,accumulating,1);working.result=accumulating;}
                if(workRevision==working.revision&&!working.complete){working.complete=true;incomplete--;}
                enqueue(working);working=null;cursor=null;accumulating=null;completed++;
                if(complete())rounds++;
            }
        }
        snapshot=new Snapshot(entries(total.counts).stream().map(e->new Entry(e.block(),e.icon(),e.count(),total.wrongBlocks.getOrDefault(e.block(),0),total.wrongStates.getOrDefault(e.block(),0))).toList(),!complete()||total.unknown>0,total.unknown);
    }
    private void reconcile(PlacementBounds nextArea,java.util.function.Predicate<PlacementBounds> relevant,java.util.function.Predicate<PlacementBounds> fullyInside){
        area=nextArea;
            // Interior results are unchanged by walking. Replace only the boundary and departed sections.
            for(var iterator=tiles.values().iterator();iterator.hasNext();){var tile=iterator.next();var clipped=clip(tile.key);
                if(clipped==null||!relevant.test(clipped)){drop(tile);iterator.remove();continue;}
                boolean boundary=!fullyInside.test(clipped);
                if(!tile.bounds.equals(clipped)||tile.boundary||boundary){dropResult(tile);tile.bounds=clipped;invalidate(tile,true);}
                tile.boundary=boundary;
            }
            queue.removeIf(t->tiles.get(t.key)!=t);urgent.removeIf(t->tiles.get(t.key)!=t);replan=true;
    }
    private void observe(Result into,BlockState wanted,BlockState actual,boolean publish){
        boolean matches=PrinterRules.constructionMatches(actual,wanted);into.compared++;if(matches)into.matched++;
        if(publish){total.compared++;if(matches)total.matched++;}
        if(matches)return;
        if(!wanted.isAir()){increase(into.counts,wanted.getBlock(),1);if(publish)increase(total.counts,wanted.getBlock(),1);}
        for(var cost:BuildMaterials.forState(wanted)){increase(into.needs,cost.item(),cost.count());if(publish)increase(total.needs,cost.item(),cost.count());}
        if(!wanted.isAir()&&!actual.isAir()){boolean wrong=wanted.is(actual.getBlock());increase(wrong?into.wrongStates:into.wrongBlocks,wanted.getBlock(),1);if(publish)increase(wrong?total.wrongStates:total.wrongBlocks,wanted.getBlock(),1);}
    }
    private static <T> void increase(Map<T,Integer> values,T key,int amount){values.compute(key,(k,v)->{int next=(v==null?0:v)+amount;return next==0?null:next;});}
    private static <T> void merge(Map<T,Integer> into,Map<T,Integer> from,int sign){from.forEach((key,value)->increase(into,key,sign*value));}
    private static void merge(Result into,Result from,int sign){if(from==null)return;into.compared+=sign*from.compared;into.matched+=sign*from.matched;into.unknown+=sign*from.unknown;merge(into.counts,from.counts,sign);merge(into.wrongBlocks,from.wrongBlocks,sign);merge(into.wrongStates,from.wrongStates,sign);merge(into.needs,from.needs,sign);}
    private void enqueue(Tile tile){if(!tile.queued){tile.queued=true;queue.addLast(tile);}}
    private void invalidate(Tile tile,boolean restart){tile.revision++;if(tile.complete){tile.complete=false;incomplete++;}if(restart&&working==tile){working=null;cursor=null;accumulating=null;}if(!tile.urgent){tile.urgent=true;urgent.addLast(tile);}enqueue(tile);}
    private void dropResult(Tile tile){merge(total,tile.result,-1);tile.result=null;}
    private void drop(Tile tile){dropResult(tile);if(!tile.complete)incomplete--;if(working==tile){working=null;cursor=null;accumulating=null;}}
    void changed(BlockPos pos){var tile=tiles.get(new Vec3i(pos.getX()>>4,pos.getY()>>4,pos.getZ()>>4));if(tile!=null)invalidate(tile,false);}
    void chunkChanged(int x,int z){if(area==null)return;for(int y=area.min().y()>>4;y<=area.max().y()>>4;y++){var tile=tiles.get(new Vec3i(x,y,z));if(tile!=null)invalidate(tile,false);}}
    private PlacementBounds clip(Vec3i key){int x=key.x()<<4,y=key.y()<<4,z=key.z()<<4;var min=new Vec3i(Math.max(x,area.min().x()),Math.max(y,area.min().y()),Math.max(z,area.min().z()));var max=new Vec3i(Math.min(x+15,area.max().x()),Math.min(y+15,area.max().y()),Math.min(z+15,area.max().z()));return min.x()>max.x()||min.y()>max.y()||min.z()>max.z()?null:new PlacementBounds(min,max);}
    private boolean relevant(PlacementBounds bounds){
        var nearest=new Vec3i(Math.max(bounds.min().x(),Math.min(bounds.max().x(),(int)Math.floor(eye.x))),Math.max(bounds.min().y(),Math.min(bounds.max().y(),(int)Math.floor(eye.y))),Math.max(bounds.min().z(),Math.min(bounds.max().z(),(int)Math.floor(eye.z))));
        var s=controller.options().printer;
        if(s.shape==PrinterRange.Shape.SPHERE?PrinterReach.distanceSquared(eye,new BlockPos(nearest.x(),nearest.y(),nearest.z()))>context.reach()*context.reach():!PrinterRange.contains(new Vec3i(Math.max(bounds.min().x(),Math.min(bounds.max().x(),center.x())),Math.max(bounds.min().y(),Math.min(bounds.max().y(),center.y())),Math.max(bounds.min().z(),Math.min(bounds.max().z(),center.z()))),center,context.reach(),s.shape))return false;
        if(pendingSource)return true;for(var layout:layouts)if(layout.intersects(bounds))return true;return false;
    }
    private boolean inside(Vec3i at){if(!controller.layerRange().contains(at))return false;var s=controller.options().printer;return s.shape==PrinterRange.Shape.SPHERE?PrinterReach.distanceSquared(eye,new BlockPos(at.x(),at.y(),at.z()))<=context.reach()*context.reach():PrinterRange.contains(at,center,context.reach(),s.shape);}
    private boolean fullyInside(PlacementBounds b){for(int corner=0;corner<8;corner++)if(!inside(new Vec3i((corner&1)==0?b.min().x():b.max().x(),(corner&2)==0?b.min().y():b.max().y(),(corner&4)==0?b.min().z():b.max().z())))return false;return true;}
    private boolean inScope(Vec3i at,PrinterSettings.Scope scope){if(scope==PrinterSettings.Scope.PROJECTION)return true;if(scope==PrinterSettings.Scope.BELOW&&at.y()>=client.player.getY()||scope==PrinterSettings.Scope.ABOVE&&at.y()<=client.player.getY())return false;for(var box:controller.selection().boxes()){var a=box.first();var b=box.second();if(at.x()>=Math.min(a.x(),b.x())&&at.x()<=Math.max(a.x(),b.x())&&at.y()>=Math.min(a.y(),b.y())&&at.y()<=Math.max(a.y(),b.y())&&at.z()>=Math.min(a.z(),b.z())&&at.z()<=Math.max(a.z(),b.z()))return true;}return false;}
    static void add(Map<Block,Integer> counts,BlockState wanted,BlockState actual){if(wanted!=null&&actual!=null&&!wanted.isAir()&&!PrinterRules.constructionMatches(actual,wanted))counts.merge(wanted.getBlock(),1,Integer::sum);}
    static List<Entry> entries(Map<Block,Integer> counts){return counts.entrySet().stream().sorted(Comparator.comparing(e->BuiltInRegistries.BLOCK.getKey(e.getKey()).toString())).map(e->new Entry(e.getKey(),icon(e.getKey()),e.getValue())).toList();}
    private static ItemStack icon(Block block){if(block==Blocks.WATER||block==Blocks.BUBBLE_COLUMN)return new ItemStack(Items.WATER_BUCKET);if(block==Blocks.LAVA)return new ItemStack(Items.LAVA_BUCKET);if(block.asItem()!=Items.AIR)return new ItemStack(block);var costs=BuildMaterials.forState(block.defaultBlockState());return costs.isEmpty()?ItemStack.EMPTY:new ItemStack(costs.get(costs.size()-1).item());}
    private static final class SectionPlan {
        final Vec3i center;final int radius,edge;final long volume;long index;
        SectionPlan(Vec3i at,PlacementBounds area){center=new Vec3i(at.x()>>4,at.y()>>4,at.z()>>4);radius=Math.max(Math.max(Math.max(center.x()-(area.min().x()>>4),(area.max().x()>>4)-center.x()),Math.max(center.y()-(area.min().y()>>4),(area.max().y()>>4)-center.y())),Math.max(center.z()-(area.min().z()>>4),(area.max().z()>>4)-center.z()));edge=radius*2+1;volume=(long)edge*edge*edge;}
        boolean hasNext(){return index<volume;}
        private static int centered(int n){return n==0?0:(n%2==1?(n+1)/2:-n/2);}
        Vec3i next(){long n=index++;int x=centered((int)(n%edge));n/=edge;int z=centered((int)(n%edge));n/=edge;return center.add(new Vec3i(x,centered((int)n),z));}
    }
    private static final class CellCursor {
        final PlacementBounds bounds;final int nx,nz,volume;int index;
        CellCursor(PlacementBounds bounds){this.bounds=bounds;nx=bounds.max().x()-bounds.min().x()+1;nz=bounds.max().z()-bounds.min().z()+1;volume=nx*nz*(bounds.max().y()-bounds.min().y()+1);}
        boolean hasNext(){return index<volume;}
        Vec3i next(){int n=index++;return bounds.min().add(new Vec3i(n%nx,n/(nx*nz),(n/nx)%nz));}
    }
}
