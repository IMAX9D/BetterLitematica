package dev.betterlitematica.fabric;

import com.mojang.renderpearl.api.buffers.*;
import com.mojang.renderpearl.api.commands.*;
import com.mojang.renderpearl.api.pipeline.*;
import com.mojang.renderpearl.api.textures.*;
import java.nio.*;
import java.util.*;
import java.util.function.*;
import org.lwjgl.PointerBuffer;

/** Splits the existing solid pass only at depth snapshot boundaries, keeping all vanilla draws and bindings. */
public final class EntityDepthPass implements RenderPass,RenderPass.UniformUploader {
    private static EntityDepthPass active;
    private final CommandEncoder encoder;
    private final Supplier<String> name;
    private final GpuTextureView color,depth;
    private RenderPass delegate;
    private CompiledRenderPipeline pipeline;
    private final Map<String,Consumer<RenderPass>> uniforms=new LinkedHashMap<>();
    private final GpuBufferSlice[] vertices=new GpuBufferSlice[MAX_VERTEX_BUFFERS];
    private GpuBuffer indices;private IndexType indexType;private ByteBuffer constants;private int[] scissor;
    private final List<Supplier<String>> groups=new ArrayList<>();
    private boolean closed,constantsPushed;
    private EntityDepthPass(CommandEncoder encoder,Supplier<String> name,GpuTextureView color,GpuTextureView depth,RenderPass delegate){this.encoder=encoder;this.name=name;this.color=color;this.depth=depth;this.delegate=delegate;active=this;}
    public static RenderPass wrap(CommandEncoder encoder,Supplier<String> name,GpuTextureView color,GpuTextureView depth,RenderPass delegate){return BetterLitematicaClient.entityMaskFrameNeeded()?new EntityDepthPass(encoder,name,color,depth,delegate):delegate;}
    static void snapshot(GpuTexture target,int width,int height){
        var pass=active;if(pass==null)throw new IllegalStateException("Entity depth snapshot outside solid pass");
        for(int i=pass.groups.size()-1;i>=0;i--)pass.delegate.popDebugGroup();
        pass.delegate.close();
        try{pass.encoder.copyTextureToTexture(pass.depth.texture(),target,0,0,0,0,0,width,height);}
        finally{
            pass.delegate=pass.encoder.createRenderPass(pass.name,pass.color,Optional.empty(),pass.depth,OptionalDouble.empty());
            for(var group:pass.groups)pass.delegate.pushDebugGroup(group);
            if(pass.pipeline!=null)pass.delegate.setPipeline(pass.pipeline);
            for(var binding:pass.uniforms.values())binding.accept(pass.delegate);
            for(int i=0;i<pass.vertices.length;i++)if(pass.vertices[i]!=null)pass.delegate.setVertexBuffer(i,pass.vertices[i]);
            if(pass.indices!=null)pass.delegate.setIndexBuffer(pass.indices,pass.indexType);
            if(pass.constantsPushed)pass.delegate.pushConstants(pass.constants.duplicate());
            if(pass.scissor!=null)pass.delegate.enableScissor(pass.scissor[0],pass.scissor[1],pass.scissor[2],pass.scissor[3]);
        }
    }
    @Override public void close(){if(closed)return;closed=true;try{delegate.close();}finally{if(active==this)active=null;uniforms.clear();groups.clear();if(constants!=null)org.lwjgl.system.MemoryUtil.memFree(constants);constants=null;}}
    @Override public void pushDebugGroup(Supplier<String> name){if(groups.size()>=64)throw new IllegalStateException("Solid pass debug groups exceed budget");groups.add(name);delegate.pushDebugGroup(name);}
    @Override public void popDebugGroup(){groups.removeLast();delegate.popDebugGroup();}
    @Override public void writeTimestamp(GpuQueryPool pool,int index){delegate.writeTimestamp(pool,index);}
    @Override public void setPipeline(CompiledRenderPipeline value){pipeline=value;constantsPushed=false;delegate.setPipeline(value);}
    private void remember(String name,Consumer<RenderPass> binding){if(!uniforms.containsKey(name)&&uniforms.size()>=256)throw new IllegalStateException("Too many solid-pass uniforms");uniforms.put(name,binding);}
    @Override public void setUniform(String name,GpuTextureView texture,GpuSampler sampler){remember(name,p->p.setUniform(name,texture,sampler));delegate.setUniform(name,texture,sampler);}
    @Override public void setUniform(String name,GpuBuffer value){remember(name,p->p.setUniform(name,value));delegate.setUniform(name,value);}
    @Override public void setUniform(String name,GpuBufferSlice value){remember(name,p->p.setUniform(name,value));delegate.setUniform(name,value);}
    @Override public void pushConstants(ByteBuffer value){int size=value.remaining();if(size>65536)throw new IllegalArgumentException("Push constants exceed budget");if(constants==null||constants.capacity()<size){if(constants!=null)org.lwjgl.system.MemoryUtil.memFree(constants);constants=org.lwjgl.system.MemoryUtil.memAlloc(size).order(value.order());}constants.clear().limit(size);constants.put(value.duplicate()).flip();constantsPushed=true;delegate.pushConstants(value);}
    @Override public void enableScissor(int x,int y,int width,int height){scissor=new int[]{x,y,width,height};delegate.enableScissor(x,y,width,height);}
    @Override public void disableScissor(){scissor=null;delegate.disableScissor();}
    @Override public void setVertexBuffer(int slot,GpuBufferSlice value){vertices[slot]=value;delegate.setVertexBuffer(slot,value);}
    @Override public void setIndexBuffer(GpuBuffer value,IndexType type){indices=value;indexType=type;delegate.setIndexBuffer(value,type);}
    @Override public void drawIndexed(int count,int instances,int first,int base,int firstInstance){delegate.drawIndexed(count,instances,first,base,firstInstance);}
    @Override public void multiDrawIndexed(IntBuffer draws,int offset,int count,int stride){delegate.multiDrawIndexed(draws,offset,count,stride);}
    @Override public void multiDrawIndexed(PointerBuffer offsets,IntBuffer counts,IntBuffer bases,int count){delegate.multiDrawIndexed(offsets,counts,bases,count);}
    @Override public void drawIndexedIndirect(GpuBufferSlice buffer,int count){delegate.drawIndexedIndirect(buffer,count);}
    @Override public <T> void drawMultipleIndexed(Collection<RenderPass.Draw<T>> draws,GpuBuffer buffer,IndexType type,Collection<String> uniforms,T context){
        var observed=new ArrayList<RenderPass.Draw<T>>(draws.size());
        for(var draw:draws){var uploader=draw.uniformUploaderConsumer();observed.add(new RenderPass.Draw<>(draw.slot(),draw.vertexBuffer(),draw.indexBuffer(),draw.indexType(),draw.firstIndex(),draw.indexCount(),draw.baseVertex(),(value,ignored)->{vertices[draw.slot()]=draw.vertexBuffer().slice();indices=draw.indexBuffer()==null?buffer:draw.indexBuffer();indexType=draw.indexType()==null?type:draw.indexType();if(uploader!=null)uploader.accept(value,this);}));}
        delegate.drawMultipleIndexed(observed,buffer,type,uniforms,context);
    }
    @Override public void draw(int count,int instances,int first,int firstInstance){delegate.draw(count,instances,first,firstInstance);}
    @Override public void multiDraw(IntBuffer draws,int offset,int count,int stride){delegate.multiDraw(draws,offset,count,stride);}
    @Override public void multiDraw(IntBuffer first,IntBuffer count,int drawCount){delegate.multiDraw(first,count,drawCount);}
    @Override public void drawIndirect(GpuBufferSlice buffer,int count){delegate.drawIndirect(buffer,count);}
}
