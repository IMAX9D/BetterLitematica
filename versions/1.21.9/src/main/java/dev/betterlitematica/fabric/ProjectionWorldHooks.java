package dev.betterlitematica.fabric;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.*;
import net.minecraft.client.util.math.MatrixStack;
import org.joml.Matrix4f;

/** Render-thread lifecycle bridge; each hook is pinned to the corresponding vanilla draw stage. */
public final class ProjectionWorldHooks {
    private static ProjectionController controller;
    private static ProjectionRenderContext context;
    private ProjectionWorldHooks(){}
    static void install(ProjectionController controller){ProjectionWorldHooks.controller=controller;}
    public static void begin(Camera camera,RenderTickCounter ticks,Matrix4f position,Matrix4f projection){
        if(BetterLitematicaClient.interactions!=null)BetterLitematicaClient.interactions.frame();
        EntityOverlayMask.reset();
        context=new ProjectionRenderContext(camera,ticks,new MatrixStack(),new Matrix4f(position),new Matrix4f(projection),null);
    }
    public static void frustum(Frustum frustum){if(context!=null)context=context.withFrustum(frustum);}
    public static void beforeEntities(){if(controller!=null&&context!=null)EntityOverlayMask.before(MinecraftClient.getInstance(),controller.entityOverlayMaskNeeded(context));}
    public static void afterEntities(){if(context!=null)EntityOverlayMask.after(MinecraftClient.getInstance());}
    public static void finishMain(){if(controller!=null&&context!=null){controller.render(context);controller.capturePreview();}}
    public static void end(){EntityOverlayMask.reset();context=null;}
}
