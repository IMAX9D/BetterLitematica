package dev.betterlitematica.fabric;

import dev.betterlitematica.core.*;
import net.minecraft.block.*;
import net.minecraft.client.MinecraftClient;
import net.minecraft.item.*;
import net.minecraft.registry.Registries;
import net.minecraft.util.math.BlockPos;
import java.util.*;

/** Read-only nearby block comparison, independent of printer attempts and inventory. */
final class NearbyBuildHud {
    record Entry(Block block,ItemStack icon,int count,int wrongBlock,int wrongState) {Entry(Block block,ItemStack icon,int count){this(block,icon,count,0,0);}}
    record Snapshot(List<Entry> entries,boolean scanning,int unknown) {
        static final Snapshot EMPTY=new Snapshot(List.of(),false,0);
    }
    private record Context(Object world,List<ProjectionController.PrinterSource> sources,LayerRange layer,
                           AreaSelection selection,long revision,Vec3i center,double reach) {}
    private final MinecraftClient client;
    private final ProjectionController controller;
    private final Map<Block,Integer> counts=new HashMap<>();
    private final Map<Block,Integer> wrongBlocks=new HashMap<>(),wrongStates=new HashMap<>();
    private Context context;
    private Cursor cursor;
    private net.minecraft.util.math.Vec3d scanEye;
    private boolean completed;
    private int unknown;
    private Snapshot snapshot=Snapshot.EMPTY;
    NearbyBuildHud(MinecraftClient client,ProjectionController controller){this.client=client;this.controller=controller;}
    Snapshot snapshot(){return snapshot;}
    void clear(){context=null;cursor=null;counts.clear();wrongBlocks.clear();wrongStates.clear();unknown=0;completed=false;snapshot=Snapshot.EMPTY;}

    void tick(){
        var s=controller.options().printer;
        if(client.world==null||client.player==null||client.interactionManager==null||!s.hud||!s.missingHud||!controller.projectionRenderingEnabled()){clear();return;}
        var sources=controller.printerSources();if(sources.isEmpty()){clear();return;}
        var eye=client.player.getEyePos();var center=new Vec3i((int)Math.round(eye.x),(int)Math.round(eye.y),(int)Math.round(eye.z));
        double reach=s.range==0?client.interactionManager.getReachDistance():Math.min(s.range,client.interactionManager.getReachDistance());
        var next=new Context(client.world,sources,controller.layerRange(),controller.selection(),s.revision,center,reach);
        if(!next.equals(context)){clear();context=next;}
        if(cursor==null){
            scanEye=eye;
            cursor=new Cursor(center,(int)Math.ceil(reach+1.5));counts.clear();wrongBlocks.clear();wrongStates.clear();unknown=0;
        }
        var sample=controller.printerSampler();long started=System.nanoTime();int visited=0;
        while(cursor.hasNext()&&visited++<8192&&System.nanoTime()-started<2_000_000L){
            var at=cursor.next();if(at==null||!controller.layerRange().contains(at))continue;
            var pos=new BlockPos(at.x(),at.y(),at.z());
            if(s.shape==PrinterRange.Shape.SPHERE?PrinterReach.distanceSquared(scanEye,pos)>reach*reach:!PrinterRange.contains(at,center,reach,s.shape))continue;
            if(client.world.isOutOfHeightLimit(pos)||!client.world.getWorldBorder().contains(pos)||!inScope(at,s.printScope))continue;
            var projected=sample.apply(at);if(projected==null||!projected.inside())continue;
            if(projected.state()==null||!WorldChunks.loaded(client.world,pos)){unknown++;continue;}
            var wanted=projected.state();var actual=client.world.getBlockState(pos);add(counts,wanted,actual);
            if(!wanted.isAir()&&!wanted.equals(actual)&&!actual.isAir())(wanted.isOf(actual.getBlock())?wrongStates:wrongBlocks).merge(wanted.getBlock(),1,Integer::sum);
        }
        boolean scanning=cursor.hasNext();
        // Replace complete rounds atomically; never flash an empty prefix every scan cycle.
        if(!scanning||!completed)snapshot=new Snapshot(entries(counts).stream().map(e->new Entry(e.block(),e.icon(),e.count(),wrongBlocks.getOrDefault(e.block(),0),wrongStates.getOrDefault(e.block(),0))).toList(),scanning,unknown);
        else snapshot=new Snapshot(snapshot.entries(),true,snapshot.unknown());
        if(!scanning){cursor=null;completed=true;}
    }
    private boolean inScope(Vec3i at,PrinterSettings.Scope scope){
        if(scope==PrinterSettings.Scope.PROJECTION)return true;
        if(scope==PrinterSettings.Scope.BELOW&&at.y()>=client.player.getY()||scope==PrinterSettings.Scope.ABOVE&&at.y()<=client.player.getY())return false;
        for(var box:controller.selection().boxes()){var a=box.first();var b=box.second();if(at.x()>=Math.min(a.x(),b.x())&&at.x()<=Math.max(a.x(),b.x())&&at.y()>=Math.min(a.y(),b.y())&&at.y()<=Math.max(a.y(),b.y())&&at.z()>=Math.min(a.z(),b.z())&&at.z()<=Math.max(a.z(),b.z()))return true;}
        return false;
    }
    static void add(Map<Block,Integer> counts,BlockState wanted,BlockState actual){
        // Count unfinished positions, including wrong states. Air is demolition, not a block to build.
        if(wanted!=null&&actual!=null&&!wanted.isAir()&&!wanted.equals(actual))counts.merge(wanted.getBlock(),1,Integer::sum);
    }
    static List<Entry> entries(Map<Block,Integer> counts){
        return counts.entrySet().stream().sorted(Comparator.comparing(e->Registries.BLOCK.getId(e.getKey()).toString()))
            .map(e->new Entry(e.getKey(),icon(e.getKey()),e.getValue())).toList();
    }
    private static ItemStack icon(Block block){
        if(block==Blocks.WATER||block==Blocks.BUBBLE_COLUMN)return new ItemStack(Items.WATER_BUCKET);
        if(block==Blocks.LAVA)return new ItemStack(Items.LAVA_BUCKET);
        if(block.asItem()!=Items.AIR)return new ItemStack(block);
        var costs=BuildMaterials.forState(block.getDefaultState());
        return costs.isEmpty()?ItemStack.EMPTY:new ItemStack(costs.get(costs.size()-1).item());
    }
    /** Bounded memory even for modded long reach; the per-tick visit/time budget bounds work. */
    private static final class Cursor {
        private final Vec3i center;private final int radius,edge;private final long volume;private long index;
        Cursor(Vec3i center,int radius){this.center=center;this.radius=radius;edge=radius*2+1;volume=(long)edge*edge*edge;}
        boolean hasNext(){return index<volume;}
        Vec3i next(){long n=index++;int x=(int)(n%edge)-radius;n/=edge;int z=(int)(n%edge)-radius;n/=edge;return center.add(new Vec3i(x,(int)n-radius,z));}
    }
}
