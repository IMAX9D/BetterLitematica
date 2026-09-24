package dev.betterlitematica.fabric;

import dev.betterlitematica.core.QuadVisibility;
import java.nio.IntBuffer;
import org.lwjgl.BufferUtils;
import org.lwjgl.PointerBuffer;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL14;

/** Render-thread scratch only. The caller's VAO and shared sequential EBO remain bound. */
final class ProjectionDraw {
    private static final IntBuffer COUNTS=BufferUtils.createIntBuffer(4096);
    private static final PointerBuffer OFFSETS=BufferUtils.createPointerBuffer(4096);
    private ProjectionDraw(){}
    static boolean drawRanges(int glType,int indexBytes,QuadVisibility.Part geometry,QuadVisibility.Mask mask){
        return drawRanges(glType,indexBytes,geometry,mask,null,false);
    }
    static boolean drawRanges(int glType,int indexBytes,QuadVisibility.Part geometry,QuadVisibility.Mask mask,QuadVisibility.Mask wrong,boolean red){
        if(!(glType==GL11.GL_UNSIGNED_SHORT&&indexBytes==2||glType==GL11.GL_UNSIGNED_INT&&indexBytes==4))throw new IllegalArgumentException("Quad index format");
        int ranges=geometry.rangeCount(mask,wrong,red);if(ranges==0)return false;
        COUNTS.clear();OFFSETS.clear();
        for(int i=0;i<ranges;i++){COUNTS.put(geometry.quadCount(i)*6);OFFSETS.put(geometry.firstQuad(i)*6L*indexBytes);}
        COUNTS.flip();OFFSETS.flip();GL14.glMultiDrawElements(GL11.GL_TRIANGLES,COUNTS,glType,OFFSETS);return true;
    }
}
