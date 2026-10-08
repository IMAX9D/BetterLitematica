package dev.betterlitematica.fabric;
import net.minecraft.client.gl.VertexBuffer;
import net.minecraft.client.render.BufferRenderer;
/** Pre-1.19 buffers require their VAO and vertex attributes to be selected explicitly. */
final class LegacyVertices {
 private static VertexBuffer active;
 static void bind(VertexBuffer buffer){
  unbind();BufferRenderer.unbindAll();buffer.bindVertexArray();buffer.bind();buffer.vertexFormat.startDrawing();active=buffer;
 }
 static void unbind(){
  if(active!=null){active.vertexFormat.endDrawing();active=null;}
  VertexBuffer.unbind();VertexBuffer.unbindVertexArray();
 }
}
