package dev.betterlitematica.fabric;

import com.mojang.blaze3d.systems.RenderSystem;
import dev.betterlitematica.core.*;
import dev.betterlitematica.runtime.WeightedLru;
import dev.betterlitematica.runtime.RenderResources;
import dev.betterlitematica.io.AuxiliaryData;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.VertexBuffer;
import net.minecraft.client.render.*;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.entity.*;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.*;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import org.joml.Matrix4f;
import org.joml.Quaternionf;

import java.util.*;

/** Bounded static projection of source entities; no entities are spawned into the client world. */
final class ProjectionEntities implements AutoCloseable {
    record Key(int region,int entity){}
    record Candidate(Key key,Vec3d position){}
    private record Part(VertexBuffer buffer,Identifier texture,boolean intensity,int bytes,Vec3d position,Billboard billboard) {}
    private record CpuPart(ProjectionVertices vertices,Identifier texture,boolean intensity,Vec3d position,Billboard billboard) {}
    record Billboard(int mode,float yaw,float pitch,Quaternionf inverse){
        static Quaternionf rotation(int mode,float yaw,float pitch,Camera camera){float rad=(float)(Math.PI/180);return switch(mode){case 1->new Quaternionf().rotationYXZ((float)Math.PI-camera.getYaw()*rad,pitch*rad,0);case 2->new Quaternionf().rotationYXZ(-yaw*rad,-camera.getPitch()*rad,0);case 3->new Quaternionf().rotationYXZ((float)Math.PI-camera.getYaw()*rad,-camera.getPitch()*rad,0);case 4->LegacyMatrices.joml(camera.getRotation());default->new Quaternionf();};}
        Quaternionf delta(Camera camera){return rotation(mode,yaw,pitch,camera).mul(inverse);}
    }
    private record Mesh(Vec3d position,Box bounds,List<Part> parts,long bytes,RenderResources.Group resources) implements AutoCloseable {public void close(){resources.close();}}
    private final AuxiliaryData data;private final MinecraftClient client;private final RenderResources resources;
    private final Map<Key,Box> measuredBounds=new HashMap<>();private Frustum frustum;
    private Set<Key> visible=new HashSet<>(),scratchVisible=new HashSet<>();private final Set<Key> nearbyKeys=new HashSet<>();
    private List<Candidate> preparedCandidates;private Vec3d preparedCamera;private final Matrix4f preparedView=new Matrix4f(),preparedProjection=new Matrix4f();private long boundsRevision,preparedBoundsRevision=-1;private boolean preparedActive;
    private final Set<Key> deferred=new HashSet<>();private long waitingRevision=-1;
    private final WeightedLru<Key,Mesh> meshes=new WeightedLru<>(32L<<20,512,Mesh::bytes,Mesh::close);
    private final Set<Key> failed=new HashSet<>();private final ProjectionModels capture=new ProjectionModels();
    private final BufferBuilder buffer=new BufferBuilder(4096);private String error="";
    ProjectionEntities(MinecraftClient client,AuxiliaryData data,RenderResources resources){this.client=client;this.data=data;this.resources=resources;}
    void prepare(WorldRenderContext context,List<Candidate> candidates,boolean active){
        var camera=context.camera().getPos();var view=LegacyMatrices.joml(context.matrixStack().peek().getPositionMatrix());
        if(active==preparedActive&&preparedBoundsRevision==boundsRevision&&(!active||candidates==preparedCandidates&&camera.equals(preparedCamera)&&preparedView.equals(view)&&preparedProjection.equals(LegacyMatrices.joml(context.projectionMatrix()))))return;
        preparedActive=active;preparedBoundsRevision=boundsRevision;preparedCandidates=candidates;preparedCamera=camera;preparedView.set(view);preparedProjection.set(LegacyMatrices.joml(context.projectionMatrix()));
        var next=scratchVisible;next.clear();var nearby=nearbyKeys;nearby.clear();frustum=context.frustum();
        if(active)for(var candidate:candidates){nearby.add(candidate.key());var mesh=meshes.get(candidate.key());Box bounds=mesh==null?measuredBounds.get(candidate.key()):mesh.bounds();if(bounds==null||frustum==null||frustum.isVisible(bounds))next.add(candidate.key());}
        measuredBounds.keySet().retainAll(nearby);
        if(!next.equals(visible)){deferred.clear();waitingRevision=-1;}scratchVisible=visible;visible=next;
        meshes.forEach((key,mesh)->mesh.resources().priority(next.contains(key)?RenderResources.VISIBLE:nearby.contains(key)?RenderResources.NEARBY:RenderResources.COLD));
    }
    List<Candidate> nearby(PlacementLayout layout,dev.betterlitematica.core.Vec3i camera,int radius){
        if(data==null)return List.of();var best=new PriorityQueue<Candidate>(Comparator.<Candidate>comparingDouble(c->c.position().squaredDistanceTo(camera.x(),camera.y(),camera.z())).reversed());double max=(radius+16.0)*(radius+16.0);
        for(int i=0;i<data.parts().size();i++){if(!layout.enabled(i))continue;var part=layout.part(i);var list=data.parts().get(i).entities();for(int j=0;j<list.size();j++){
            if(Thread.currentThread().isInterrupted())throw new java.util.concurrent.CancellationException();Object value=list.get(j).get("Pos");if(!(value instanceof List<?> pos)||pos.size()!=3||pos.stream().anyMatch(n->!(n instanceof Number)))continue;
            var t=part.transform();double x=((Number)pos.get(0)).doubleValue()+part.region().min().x(),y=((Number)pos.get(1)).doubleValue()+part.region().min().y(),z=((Number)pos.get(2)).doubleValue()+part.region().min().z();
            if(t.mirrorX())x=1-x;if(t.mirrorZ())z=1-z;for(int turn=0;turn<t.quarterTurns();turn++){double old=x;x=1-z;z=old;}var at=new Vec3d(x+t.origin().x(),y+t.origin().y(),z+t.origin().z());double distance=at.squaredDistanceTo(camera.x(),camera.y(),camera.z());if(!Double.isFinite(distance)||distance>max)continue;
            var next=new Candidate(new Key(i,j),at);if(best.size()<512)best.add(next);else if(distance<best.peek().position().squaredDistanceTo(camera.x(),camera.y(),camera.z())){best.poll();best.add(next);}
        }}
        var result=new ArrayList<>(best);result.sort(Comparator.comparingDouble(c->c.position().squaredDistanceTo(camera.x(),camera.y(),camera.z())));return List.copyOf(result);
    }
    int build(List<Candidate> candidates,ProjectionRenderer1201 owner,ProjectionScene scene,long deadline,int uploadBytes){
        if(data==null||System.nanoTime()>=deadline||uploadBytes<1024||waitingRevision==resources.revision())return 0;
        for(var candidate:candidates){if(System.nanoTime()>=deadline)return 0;boolean onscreen=visible.contains(candidate.key());if(!onscreen&&!resources.warm()||meshes.contains(candidate.key())||failed.contains(candidate.key())||deferred.contains(candidate.key()))continue;var p=candidate.position();if(!owner.layer().contains(new dev.betterlitematica.core.Vec3i(MathHelper.floor(p.x),MathHelper.floor(p.y),MathHelper.floor(p.z))))continue;
            RenderResources.Group group=null;
            try{
                var region=owner.layout().part(candidate.key().region());var raw=data.parts().get(candidate.key().region()).entities().get(candidate.key().entity());validateTree(raw);var tag=(NbtCompound)NbtBridge.game(raw);EntityNbtTransform.placed(tag,region.region().min(),region.transform());
                var box=new PlacementBounds(new dev.betterlitematica.core.Vec3i(MathHelper.floor(p.x)-32,MathHelper.floor(p.y)-32,MathHelper.floor(p.z)-32),new dev.betterlitematica.core.Vec3i(MathHelper.floor(p.x)+32,MathHelper.floor(p.y)+32,MathHelper.floor(p.z)+32));
                var world=new ProjectionWorld(client.world);world.view(new ProjectionBlockView(client.world,scene,scene.overlapping(box),owner.layer()),pos->null);
                Entity entity=EntityType.loadEntityWithPassengers(tag,world,e->e);if(entity==null)throw new IllegalArgumentException("实体类型不存在");int bytes=0,count=0;Box bounds=entity.getVisibilityBoundingBox().expand(4);var todo=new ArrayDeque<Entity>();todo.add(entity);var cpu=new ArrayList<CpuPart>();
                while(!todo.isEmpty()){var current=todo.removeFirst();if(++count>64)throw new IllegalArgumentException("实体乘客超过预算");current.age=Math.max(2,current.age);capture.clear();var matrices=new MatrixStack();var renderer=client.getEntityRenderDispatcher().getRenderer(current);var offset=renderer.getPositionOffset(current,1);matrices.translate(offset.x,offset.y,offset.z);renderer.render(current,current.getYaw(),1,matrices,capture,LightmapTextureManager.MAX_LIGHT_COORDINATE);
                    bytes+=capture.vertices()*24;if(bytes>2*1024*1024)throw new IllegalArgumentException("实体模型超过预算");if(bytes>uploadBytes)return 0;var billboard=billboard(current);bounds=bounds.union(current.getVisibilityBoundingBox().expand(4));
                    for(var layer:capture.layers().entrySet())if(layer.getValue().size()>0)cpu.add(new CpuPart(layer.getValue(),layer.getKey().texture(),layer.getKey().intensity(),current.getPos(),billboard));
                    todo.addAll(current.getPassengerList());
                }
                if(bytes==0)throw new IllegalArgumentException("实体未生成可见模型");
                measuredBounds.put(candidate.key(),bounds);boundsRevision++;onscreen=frustum==null||frustum.isVisible(bounds);
                if(!onscreen&&!resources.warm())continue;
                long weight=Math.max(128,bytes);
                if(!(onscreen?meshes.canPutProtecting(weight,visible):meshes.canPutWithoutEviction(weight))){deferred.add(candidate.key());continue;}
                int demand=onscreen?RenderResources.VISIBLE:RenderResources.NEARBY;group=resources.begin(demand);
                if(group==null){waitingRevision=resources.revision();return 0;}
                var leases=group.reserve(cpu.stream().mapToInt(part->part.vertices().size()*24).toArray(),demand);
                if(leases==null){group.close();waitingRevision=resources.revision();return 0;}
                var parts=new ArrayList<Part>();
                for(int i=0;i<cpu.size();i++){var part=cpu.get(i);buffer.begin(VertexFormat.DrawMode.QUADS,VertexFormats.POSITION_TEXTURE_COLOR);part.vertices().emit(buffer,0,0,0);var gpu=new VertexBuffer();leases.get(i).attach(gpu::close);try{gpu.bind();gpu.upload(buffer.end());}finally{VertexBuffer.unbind();}parts.add(new Part(gpu,part.texture(),part.intensity(),part.vertices().size()*24,part.position(),part.billboard()));}
                var mesh=new Mesh(candidate.position(),bounds,List.copyOf(parts),weight,group);boolean admitted=onscreen?meshes.tryPutProtecting(candidate.key(),mesh,visible):meshes.tryPutWithoutEviction(candidate.key(),mesh);
                if(!admitted){group.close();deferred.add(candidate.key());return bytes;}
                group.finish(demand,()->{meshes.remove(candidate.key());deferred.clear();});return bytes;
            }catch(ProjectionBlockView.Pending waiting){if(group!=null)group.close();return 0;}
            catch(RuntimeException e){if(group!=null)group.close();if(buffer.isBuilding())buffer.end().release();buffer.clear();if(failed.size()>=8192)failed.clear();failed.add(candidate.key());error="实体投影失败："+e.getMessage();}
            finally{capture.clear();}
        }return 0;
    }
    private static void validateTree(Map<String,Object> root){var todo=new ArrayDeque<Map<?,?>>();todo.add(root);int count=0;while(!todo.isEmpty()){if(++count>64)throw new IllegalArgumentException("实体乘客超过预算");var tag=todo.removeFirst();if(tag.get("Passengers") instanceof List<?> list)for(var child:list){if(!(child instanceof Map<?,?> map))throw new IllegalArgumentException("无效实体乘客");todo.add(map);if(todo.size()>64)throw new IllegalArgumentException("实体乘客超过预算");}}}
    private Billboard billboard(Entity entity){int mode=0;{net.minecraft.client.render.entity.EntityRenderer<?> renderer=client.getEntityRenderDispatcher().getRenderer(entity);if(renderer instanceof net.minecraft.client.render.entity.FlyingItemEntityRenderer<?>||renderer instanceof net.minecraft.client.render.entity.ExperienceOrbEntityRenderer||renderer instanceof net.minecraft.client.render.entity.DragonFireballEntityRenderer)mode=4;}
        return new Billboard(mode,entity.getYaw(),entity.getPitch(),Billboard.rotation(mode,entity.getYaw(),entity.getPitch(),client.getEntityRenderDispatcher().camera).invert());
    }
    void render(WorldRenderContext context,List<Candidate> candidates,float opacity){int previousTexture=RenderSystem.getShaderTexture(0);var shader=ProjectionShaders.surface();var previous=RenderSystem.getShader();var color=RenderSystem.getShaderColor().clone();var camera=context.camera().getPos();boolean bound=false;
        try{RenderSystem.setShader(ProjectionShaders::surface);RenderSystem.enableDepthTest();RenderSystem.depthMask(true);RenderSystem.disableBlend();RenderSystem.disableCull();RenderSystem.setShaderColor(1,1,1,opacity);
            for(var candidate:candidates){var mesh=meshes.get(candidate.key());if(mesh==null||context.frustum()!=null&&!context.frustum().isVisible(mesh.bounds()))continue;for(var part:mesh.parts()){var matrix=LegacyMatrices.joml(context.matrixStack().peek().getPositionMatrix()).translate((float)(part.position().x-camera.x),(float)(part.position().y-camera.y),(float)(part.position().z-camera.z)).rotate(part.billboard().delta(context.camera()));
                    if(shader.modelViewMat!=null)shader.modelViewMat.set(LegacyMatrices.minecraft(matrix));if(shader.projectionMat!=null)shader.projectionMat.set(context.projectionMatrix());if(shader.colorModulator!=null)shader.colorModulator.set(1f,1f,1f,opacity);
                    shader.getUniformOrDefault("TextureMode").set(part.intensity()?1:0);RenderSystem.setShaderTexture(0,part.texture());shader.addSampler("Sampler0",RenderSystem.getShaderTexture(0));shader.bind();bound=true;part.buffer().bind();part.buffer().drawElements();
                }
            }
        }finally{if(bound)shader.unbind();VertexBuffer.unbind();RenderSystem.setShaderTexture(0,previousTexture);RenderSystem.enableCull();RenderSystem.setShaderColor(color[0],color[1],color[2],color[3]);if(previous!=null)RenderSystem.setShader(()->previous);}
    }
    String error(){return error;}
    void sceneChanged(SceneChanges changes,List<Candidate> candidates){
        var affected=new HashSet<Key>();
        meshes.forEach((key,mesh)->{if(changes.affects(sampleBounds(mesh.position())))affected.add(key);});
        for(var candidate:candidates)if(changes.affects(sampleBounds(candidate.position())))affected.add(candidate.key());
        for(var key:affected){meshes.remove(key);measuredBounds.remove(key);}
        // Failed or resource-blocked candidates may no longer be in the current nearby list.
        // Let them retry when visited, without dropping successful unrelated entity meshes.
        failed.clear();deferred.clear();waitingRevision=-1;
        if(!affected.isEmpty()){boundsRevision++;preparedBoundsRevision=-1;}
    }
    private static PlacementBounds sampleBounds(Vec3d p){return new PlacementBounds(new dev.betterlitematica.core.Vec3i(MathHelper.floor(p.x)-32,MathHelper.floor(p.y)-32,MathHelper.floor(p.z)-32),new dev.betterlitematica.core.Vec3i(MathHelper.floor(p.x)+32,MathHelper.floor(p.y)+32,MathHelper.floor(p.z)+32));}
    void suspend(){meshes.forEach((key,mesh)->mesh.resources().priority(RenderResources.COLD));preparedBoundsRevision=-1;preparedCandidates=null;visible.clear();scratchVisible.clear();nearbyKeys.clear();}
    void invalidate(){meshes.close();failed.clear();deferred.clear();measuredBounds.clear();visible.clear();scratchVisible.clear();nearbyKeys.clear();preparedBoundsRevision=-1;preparedCandidates=null;waitingRevision=-1;error="";}
    @Override public void close(){invalidate();if(buffer.isBuilding())buffer.end().release();buffer.clear();}
}
