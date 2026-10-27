package dev.betterlitematica.fabric.mixin;

import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.util.Queue;
import java.util.function.Consumer;
import net.minecraft.client.renderer.SectionOcclusionGraph;
import net.minecraft.client.renderer.ViewArea;
import net.minecraft.client.renderer.chunk.CompiledSectionMesh;
import net.minecraft.client.renderer.chunk.SectionMesh;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher.RenderSection;
import net.minecraft.core.Direction;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Six-face outward traversal adapted to 26.3's extracted loading state and octree publication.
 * The engine retains its background scheduler, shared GPU buffers, transparency and both backends.
 * No live client-world query or graphics call occurs in this graph worker. */
@Mixin(SectionOcclusionGraph.class)
public abstract class TerrainVisibilityGraphMixin {
    @Unique private static final Direction[] betterlitematica$directions=Direction.values();
    @Unique private static final long betterlitematica$open=(1L<<36)-1;
    @Shadow private ViewArea viewArea;
    @Shadow private RenderSection getRelativeFrom(long camera,RenderSection section,Direction side){throw new AssertionError();}

    @Inject(method="runUpdates",at=@At("HEAD"),cancellable=true)
    private void betterlitematica$traverse(SectionOcclusionGraph.GraphStorage storage,Vec3 camera,
            Queue<SectionOcclusionGraph.Node> queue,boolean culling,Consumer<RenderSection> added,
            LongOpenHashSet empty,LongOpenHashSet loaded,CallbackInfo ci){
        ci.cancel();
        dev.betterlitematica.fabric.TerrainNativeBudget.changing();
        try{dev.betterlitematica.fabric.TerrainNativeBudget.graphWalks++;
        long cameraNode=SectionPos.of(camera).asLong();
        int cx=SectionPos.x(cameraNode),cy=SectionPos.y(cameraNode),cz=SectionPos.z(cameraNode);
        while(!queue.isEmpty()){
            dev.betterlitematica.fabric.TerrainNativeBudget.graphVisits++;
            var node=queue.remove();var section=node.section;long position=section.getSectionNode();
            long column=ChunkPos.fromSectionNode(position);
            if(!loaded.contains(column)){
                var waiting=storage.sectionsWaitingForChunkLoads.get(column);
                if(waiting==null){waiting=new LongArrayList();storage.sectionsWaitingForChunkLoads.put(column,waiting);}
                if(!waiting.contains(position))waiting.add(position);
                continue;
            }
            if(empty.contains(position)){
                if(section.sectionMesh.compareAndSet(CompiledSectionMesh.UNCOMPILED,CompiledSectionMesh.EMPTY))section.updateUploadTime();
            }else if(storage.sectionTree.add(section))added.accept(section);

            int exits=63;
            if(culling){
                long portals=betterlitematica$connections(section.getSectionMesh());
                if(portals!=betterlitematica$open){
                    exits=0;int incoming=node.sourceDirections&63;
                    // The engine records the direction of travel; its opposite is the entry face.
                    if(incoming==0)incoming=63;
                    while(incoming!=0){int d=Integer.numberOfTrailingZeros(incoming);incoming&=incoming-1;
                        exits|=(int)(portals>>>((d^1)*6))&63;
                    }
                }
            }
            int x=SectionPos.x(position),y=SectionPos.y(position),z=SectionPos.z(position);
            exits&=(y<=cy?1:0)|(y>=cy?2:0)|(z<=cz?4:0)|(z>=cz?8:0)|(x<=cx?16:0)|(x>=cx?32:0);
            while(exits!=0){
                int d=Integer.numberOfTrailingZeros(exits);exits&=exits-1;
                var side=betterlitematica$directions[d];var neighbor=getRelativeFrom(cameraNode,section,side);
                if(neighbor==null)continue;
                var existing=storage.sectionToNodeMap.get(neighbor);
                if(existing==null){
                    var next=new SectionOcclusionGraph.Node(neighbor,side,node.step+1);
                    next.setDirections(node.directions,side);
                    storage.sectionToNodeMap.put(neighbor,next);queue.add(next);
                }else if((existing.sourceDirections&(1<<d))==0){
                    existing.addSourceDirection(side);
                    // Partial updates can open a new route through an already visited section.
                    // Revisit only for new entry bits; six bits bound this work without ray stepping.
                    queue.add(existing);
                }
            }
        }
            }finally{dev.betterlitematica.fabric.TerrainNativeBudget.changed();}
    }

    @Unique private static long betterlitematica$connections(SectionMesh mesh){
        if(mesh==CompiledSectionMesh.UNCOMPILED||mesh==CompiledSectionMesh.EMPTY)return betterlitematica$open;
        if(mesh.getClass()==CompiledSectionMesh.class){
            var bits=((CompiledSectionMesh)mesh).visibilitySet.data;
            if(bits.nextClearBit(0)>=36)return betterlitematica$open;
            long encoded=0;
            for(int bit=bits.nextSetBit(0);bit>=0&&bit<36;bit=bits.nextSetBit(bit+1))encoded|=1L<<((bit%6)*6+bit/6);
            return encoded;
        }
        long encoded=0;
        for(int from=0;from<6;from++)for(int to=0;to<6;to++)
            if(mesh.facesCanSeeEachother(betterlitematica$directions[from],betterlitematica$directions[to]))encoded|=1L<<(from*6+to);
        return encoded;
    }
}
