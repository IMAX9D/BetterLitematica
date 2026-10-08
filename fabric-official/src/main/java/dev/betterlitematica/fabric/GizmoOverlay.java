package dev.betterlitematica.fabric;

import com.mojang.blaze3d.vertex.VertexConsumer;
import java.util.List;
import java.util.Objects;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;

/** World-space preview only; interaction and placement ownership belong to the controller. */
public final class GizmoOverlay {
    private static final int[] COLORS={0xffef6262,0xff61c779,0xff669cff};
    private static final double[] COS={1,.5,-.5,-1,-.5,.5};
    private static final double[] SIN={0,.8660254037844386,.8660254037844386,0,-.8660254037844386,-.8660254037844386};
    static final double HEAD_START=.76,HEAD_RADIUS=.09,SHAFT_RADIUS=.025;

    /** Axis indices are X=0, Y=1, Z=2; -1 means none. Bounds are world coordinates. */
    public record View(Vec3 origin,int hoveredAxis,int activeAxis,int lockedAxes,double length,
                       List<AABB> bounds,boolean dragging,int amount) {
        public View {
            Objects.requireNonNull(origin);
            bounds=List.copyOf(bounds);
            if(!Double.isFinite(origin.x)||!Double.isFinite(origin.y)||!Double.isFinite(origin.z)
                    ||!Double.isFinite(length)||length<=0)throw new IllegalArgumentException("Invalid gizmo geometry");
            if(hoveredAxis< -1||hoveredAxis>2||activeAxis< -1||activeAxis>2||(lockedAxes&~7)!=0)
                throw new IllegalArgumentException("Invalid gizmo axes");
        }
    }

    private GizmoOverlay() {}

    public static void draw(Minecraft client,ProjectionFrame context,View view) {
        if(view==null||client.level==null||client.player==null||context.matrixStack()==null)return;
        var matrices=context.matrixStack();var camera=context.camera().getPosition();var origin=view.origin();
        var buffers=OverlayBuffers.of(context);
        // A handle inside its target block must remain usable; the overlay mask still hides it behind entities.
        var faces=OverlayLayers.faces(true);var lines=OverlayLayers.lines(false);
        matrices.pushPose();
        // Keep vertex coordinates close to zero even at distant world positions.
        matrices.translate(origin.x-camera.x,origin.y-camera.y,origin.z-camera.z);
        try {
            var vertices=buffers.getBuffer(faces);var matrix=matrices.last().pose();
            for(int axis=0;axis<3;axis++){
                boolean locked=(view.lockedAxes()&(1<<axis))!=0;
                boolean active=view.dragging()&&view.activeAxis()==axis;
                boolean highlighted=active||view.hoveredAxis()==axis;
                int color=locked?0xff87919f:COLORS[axis];
                float r=((color>>>16)&255)/255f,g=((color>>>8)&255)/255f,b=(color&255)/255f;
                if(highlighted&&!locked){r+=(1-r)*.24f;g+=(1-g)*.24f;b+=(1-b)*.24f;}
                float alpha=locked?.65f:view.dragging()&&!active?.5f:1f;
                arrow(vertices,matrix,axis,view.length(),r,g,b,alpha);
            }
            buffers.endBatch(faces);
            var outline=buffers.getBuffer(lines);
            for(var box:view.bounds()){
                if(context.frustum()!=null&&!context.frustum().isVisible(box))continue;
                var local=box.move(-origin.x,-origin.y,-origin.z);
                OverlayBuffers.lineBox(matrices,outline,local,.78f,.84f,.96f,view.dragging()?.9f:.6f);
            }
        } finally {
            matrices.popPose();buffers.endBatch(lines);
        }
    }

    private static void arrow(VertexConsumer out,Matrix4f matrix,int axis,double length,float r,float g,float b,float a){
        double shaft=length*SHAFT_RADIUS,head=length*HEAD_RADIUS,join=length*HEAD_START;
        for(int i=0;i<6;i++){
            int j=(i+1)%6;double u=COS[i],v=SIN[i],u2=COS[j],v2=SIN[j];
            float shade=.82f+.16f*(float)((u+u2)*.5);
            float red=r*shade,green=g*shade,blue=b*shade;
            vertex(out,matrix,axis,0,u*shaft,v*shaft,red,green,blue,a);
            vertex(out,matrix,axis,join,u*shaft,v*shaft,red,green,blue,a);
            vertex(out,matrix,axis,join,u2*shaft,v2*shaft,red,green,blue,a);
            vertex(out,matrix,axis,0,u2*shaft,v2*shaft,red,green,blue,a);
            triangle(out,matrix,axis,join,u*head,v*head,join,u2*head,v2*head,length,0,0,red,green,blue,a);
            triangle(out,matrix,axis,join,0,0,join,u2*head,v2*head,join,u*head,v*head,r*.7f,g*.7f,b*.7f,a);
            triangle(out,matrix,axis,0,0,0,0,u*shaft,v*shaft,0,u2*shaft,v2*shaft,r*.7f,g*.7f,b*.7f,a);
        }
    }

    /** QUADS layer: repeating the last vertex gives one triangle and one degenerate triangle. */
    private static void triangle(VertexConsumer out,Matrix4f matrix,int axis,
            double t,double u,double v,double t2,double u2,double v2,double t3,double u3,double v3,
            float r,float g,float b,float a){
        vertex(out,matrix,axis,t,u,v,r,g,b,a);vertex(out,matrix,axis,t2,u2,v2,r,g,b,a);
        vertex(out,matrix,axis,t3,u3,v3,r,g,b,a);vertex(out,matrix,axis,t3,u3,v3,r,g,b,a);
    }

    private static void vertex(VertexConsumer out,Matrix4f matrix,int axis,double t,double u,double v,float r,float g,float b,float a){
        double x=axis==0?t:u,y=axis==1?t:axis==0?u:v,z=axis==2?t:v;
        // X uses Y/Z radial coordinates; Y uses X/Z; Z uses X/Y.
        out.addVertex(matrix,(float)x,(float)y,(float)z).setColor(r,g,b,a);
    }
}
