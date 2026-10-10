package dev.betterlitematica.fabric;
import net.minecraft.client.render.*;
final class OverlayLayers extends RenderPhase {
    private OverlayLayers(){super("betterlitematica_overlays",()->{},()->{});}
    // Vanilla ALWAYS_DEPTH_TEST is a no-op: it inherits the previous pass's depth state.
    private static final RenderPhase.DepthTest ON_TOP=new RenderPhase.DepthTest("betterlitematica_on_top",519){
        private boolean previous;
        @Override public void startDrawing(){previous=org.lwjgl.opengl.GL11.glIsEnabled(org.lwjgl.opengl.GL11.GL_DEPTH_TEST);com.mojang.blaze3d.systems.RenderSystem.disableDepthTest();}
        @Override public void endDrawing(){if(previous)com.mojang.blaze3d.systems.RenderSystem.enableDepthTest();else com.mojang.blaze3d.systems.RenderSystem.disableDepthTest();}
    };
    private static final RenderPhase.ShaderProgram MASKED_LINES=new RenderPhase.ShaderProgram(ProjectionShaders::overlayLines),MASKED_FACES=new RenderPhase.ShaderProgram(ProjectionShaders::overlayFaces);
    private static final RenderLayer[] LINES={make(false,false),make(false,true)},FACES={make(true,false),make(true,true)};
    static RenderLayer lines(boolean through){return LINES[through?1:0];}static RenderLayer faces(boolean through){return FACES[through?1:0];}
    // Construction highlights fade out at the eye so wireframes around the player never wall off the view.
    // Gizmos and bounds keep the plain layers above and stay solid when held close.
    private static final RenderPhase.ShaderProgram NEAR_LINES=new RenderPhase.ShaderProgram(ProjectionShaders::nearLines),NEAR_LINES_DEPTH=new RenderPhase.ShaderProgram(ProjectionShaders::nearLinesDepthTested),
        NEAR_FACES=new RenderPhase.ShaderProgram(ProjectionShaders::nearFaces),NEAR_FACES_DEPTH=new RenderPhase.ShaderProgram(ProjectionShaders::nearFacesDepthTested);
    private static final RenderLayer[] NEAR_LINE_LAYERS={make("near_",false,false,NEAR_LINES_DEPTH),make("near_",false,true,NEAR_LINES)},NEAR_FACE_LAYERS={make("near_",true,false,NEAR_FACES_DEPTH),make("near_",true,true,NEAR_FACES)};
    static RenderLayer nearLines(boolean through){return NEAR_LINE_LAYERS[through?1:0];}static RenderLayer nearFaces(boolean through){return NEAR_FACE_LAYERS[through?1:0];}
    private static RenderLayer make(boolean face,boolean through){return make("",face,through,through?(face?MASKED_FACES:MASKED_LINES):(face?RenderPhase.COLOR_PROGRAM:RenderPhase.LINES_PROGRAM));}
    private static RenderLayer make(String kind,boolean face,boolean through,RenderPhase.ShaderProgram program){return RenderLayer.of("betterlitematica_overlay_"+kind+face+"_"+through,face?VertexFormats.POSITION_COLOR:VertexFormats.LINES,face?VertexFormat.DrawMode.QUADS:VertexFormat.DrawMode.LINES,32768,false,face,RenderLayer.MultiPhaseParameters.builder().program(program).transparency(RenderPhase.TRANSLUCENT_TRANSPARENCY).depthTest(through?ON_TOP:RenderPhase.LEQUAL_DEPTH_TEST).writeMaskState(RenderPhase.COLOR_MASK).cull(RenderPhase.DISABLE_CULLING).build(false));}
}
