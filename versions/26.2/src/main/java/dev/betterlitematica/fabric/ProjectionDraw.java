package dev.betterlitematica.fabric;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTextureView;
import dev.betterlitematica.core.QuadVisibility;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;
import org.joml.Matrix4f;
import org.lwjgl.system.MemoryUtil;
import java.util.*;
/** Extraction produces bounded immutable draw commands; draw never queries the game world. */
final class ProjectionDraw {
 private static final int MAX_COMMANDS=65536,MAX_RANGE_INTS=4*1024*1024,UNIFORM_BYTES=176;
 private static final List<Object> commands=new ArrayList<>();private static int ranges;
 private static ProjectionComposite composite;
 private record Draw(ProjectionGpuMesh mesh,RenderPipeline pipeline,Matrix4f view,Matrix4f projection,Identifier texture,GpuTextureView direct,float opacity,int intensity,int red,boolean mask,ProjectionComposite target,int[] ranges){}
 static void beginFrame(){clear();composite=null;EntityOverlayMask.prepare();}
 static void target(ProjectionComposite value){composite=value;}
 static void command(Runnable action){if(commands.size()>=MAX_COMMANDS)throw new IllegalStateException("投影绘制指令超过预算");commands.add(action);}
 static boolean surface(ProjectionGpuMesh mesh,Matrix4f view,Matrix4f projection,Identifier texture,boolean intensity,float opacity,QuadVisibility.Part geometry,QuadVisibility.Mask hidden,QuadVisibility.Mask wrong,boolean red){int[] selected=null;if(geometry!=null){int count=geometry.rangeCount(hidden,wrong,red);if(count==0)return false;selected=new int[count*2];for(int i=0;i<count;i++){selected[i*2]=geometry.firstQuad(i)*6;selected[i*2+1]=geometry.quadCount(i)*6;}}add(mesh,ProjectionShaders.SURFACE,view,projection,texture,null,opacity,intensity?1:0,red?1:0,false,selected);return true;}
 static void overlay(ProjectionGpuMesh mesh,Matrix4f projection,boolean face,boolean through){add(mesh,ProjectionShaders.overlay(face,through?1:0),new Matrix4f(),projection,null,null,1,0,0,through,null);}
 static void blit(ProjectionGpuMesh mesh,GpuTextureView texture,float opacity){add(mesh,ProjectionShaders.COMPOSITE,new Matrix4f(),new Matrix4f(),null,texture,opacity,0,0,false,null);}
 private static void add(ProjectionGpuMesh mesh,RenderPipeline pipeline,Matrix4f view,Matrix4f projection,Identifier texture,GpuTextureView direct,float opacity,int intensity,int red,boolean mask,int[] selected){int size=selected==null?0:selected.length;if(commands.size()>=MAX_COMMANDS||ranges+size>MAX_RANGE_INTS)throw new IllegalStateException("投影绘制指令超过预算");mesh.retain();commands.add(new Draw(mesh,pipeline,new Matrix4f(view),new Matrix4f(projection),texture,direct,opacity,intensity,red,mask,composite,selected));ranges+=size;}

 private static final class Batch implements AutoCloseable {
  final com.mojang.blaze3d.pipeline.RenderTarget target;
  final com.mojang.blaze3d.systems.CommandEncoder encoder=RenderSystem.getDevice().createCommandEncoder();
  final com.mojang.blaze3d.systems.RenderPass pass;
  Batch(com.mojang.blaze3d.pipeline.RenderTarget target){this.target=target;pass=encoder.createRenderPass(()->"BetterLitematica projection",target.getColorTextureView(),Optional.empty(),target.getDepthTextureView(),OptionalDouble.empty());}
  @Override public void close(){pass.close();}
 }
 static void flush(){
  var client=Minecraft.getInstance();int alignment=RenderSystem.getDevice().getDeviceInfo().limits().minUniformOffsetAlignment();
  int stride=(UNIFORM_BYTES+alignment-1)/alignment*alignment;
  int draws=(int)commands.stream().filter(Draw.class::isInstance).count();
  if(draws==0){try{for(var value:commands)if(value instanceof Runnable action)action.run();}finally{clear();}return;}
  var data=MemoryUtil.memCalloc(Math.multiplyExact(draws,stride));GpuBuffer uniforms=null;Batch batch=null;
  try{
   int index=0;
   for(var value:commands)if(value instanceof Draw draw){
    // Grow shared indices before opening a render pass; later draws only bind them.
    RenderSystem.getSequentialBuffer(draw.mesh.mode).getBuffer(draw.mesh.indexCount);
    int at=index++*stride;draw.view.get(at,data);draw.projection.get(at+64,data);
    data.putFloat(at+128,1).putFloat(at+132,1).putFloat(at+136,1).putFloat(at+140,draw.opacity);
    data.putInt(at+144,draw.intensity).putInt(at+148,draw.red).putInt(at+152,draw.mask&&EntityOverlayMask.ready()?1:0);
    data.putFloat(at+160,ClientUi.target(client).width).putFloat(at+164,ClientUi.target(client).height);
   }
   uniforms=RenderSystem.getDevice().createBuffer(()->"BetterLitematica frame transforms",GpuBuffer.USAGE_UNIFORM,data);index=0;
   for(var value:commands){
    if(value instanceof Runnable action){if(batch!=null){batch.close();batch=null;}action.run();continue;}
    var draw=(Draw)value;var target=draw.target==null?ClientUi.target(client):draw.target.target();
    if(batch==null||batch.target!=target){if(batch!=null)batch.close();batch=new Batch(target);}
    var pass=batch.pass;pass.setPipeline(draw.pipeline);
    pass.setUniform("Projection",uniforms.slice((long)index++*stride,UNIFORM_BYTES));pass.setVertexBuffer(0,draw.mesh.vertices.slice());
    var indices=RenderSystem.getSequentialBuffer(draw.mesh.mode);var indexBuffer=indices.getBuffer(draw.mesh.indexCount);pass.setIndexBuffer(indexBuffer,indices.type());
    if(draw.texture!=null){var texture=client.getTextureManager().getTexture(draw.texture);pass.bindTexture("Sampler0",texture.getTextureView(),texture.getSampler());}
    else if(draw.direct!=null)pass.bindTexture("Sampler0",draw.direct,RenderSystem.getSamplerCache().getClampToEdge(com.mojang.blaze3d.textures.FilterMode.NEAREST));
    if(draw.pipeline!=ProjectionShaders.SURFACE&&draw.pipeline!=ProjectionShaders.COMPOSITE)EntityOverlayMask.bind(pass);
    if(draw.ranges==null){pass.drawIndexed(draw.mesh.indexCount,1,0,0,0);}
    else if(draw.ranges.length==2){pass.drawIndexed(draw.ranges[1],1,draw.ranges[0],0,0);}
    else{
     var selected=new ArrayList<com.mojang.blaze3d.systems.RenderPass.Draw<Void>>(draw.ranges.length/2);
     for(int i=0;i<draw.ranges.length;i+=2)selected.add(new com.mojang.blaze3d.systems.RenderPass.Draw<>(0,draw.mesh.vertices,indexBuffer,indices.type(),draw.ranges[i],draw.ranges[i+1],0));
     pass.drawMultipleIndexed(selected,indexBuffer,indices.type(),List.of(),null);
    }
   }
  }finally{try{if(batch!=null)batch.close();}finally{MemoryUtil.memFree(data);if(uniforms!=null){var release=uniforms;RenderSystem.queueFencedTask(release::close);}clear();}}
 }
 private static void clear(){for(var value:commands)if(value instanceof Draw draw)draw.mesh.release();commands.clear();ranges=0;}
}
