package dev.betterlitematica.fabric;

import dev.betterlitematica.core.*;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.model.BakedModel;
import net.minecraft.client.render.model.BakedQuad;
import net.minecraft.client.texture.Sprite;
import net.minecraft.fluid.FluidState;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.random.Random;
import net.minecraft.world.BlockRenderView;
import net.minecraft.world.LightType;
import net.minecraft.world.biome.ColorResolver;
import net.minecraft.world.chunk.light.LightingProvider;
import org.lwjgl.system.MemoryUtil;
import java.util.*;
import java.util.concurrent.*;

/**
 * Builds one section's block geometry off the render thread, the way vanilla chunk builders do: an immutable
 * snapshot goes in, arena-relative vertices in native memory come out. Models, fluids and block colors are read
 * concurrently as in vanilla; the client world is read only to omit blocks that are already built. Block entity
 * renderers stay on the render thread, so their cells are only listed here.
 */
final class SectionMesher {
    static final int STRIDE=24,MAX_PART_VERTICES=16384,MAX_BLOCK_QUADS=512;
    static final long MAX_SECTION_BYTES=8L<<20;
    /** Palette flags by asset id, written on the render thread before any snapshot that uses the id is published. */
    static final short READY=1,SKIP=2,OPAQUE=4,FLUID=8,ENTITY=16,LIGHT=32,MODEL=64,INVISIBLE=128,INVALID=256;
    static final int THREADS=Math.max(1,Math.min(4,Runtime.getRuntime().availableProcessors()/2));
    static final int MAX_IN_FLIGHT=THREADS*3;
    private static final dev.betterlitematica.runtime.BoundedTaskPool POOL=new dev.betterlitematica.runtime.BoundedTaskPool(THREADS,MAX_IN_FLIGHT,"betterlitematica-mesh-");
    static dev.betterlitematica.runtime.BoundedTaskPool.Ticket<Output> submit(Input input,java.util.function.LongPredicate obsolete){
        return POOL.submit(cancelled->build(input,generation->cancelled.getAsBoolean()||obsolete.test(generation)),Output::free);
    }
    static boolean hasCapacity(){return POOL.outstanding()<MAX_IN_FLIGHT;}
    private static final Direction[] DIRECTIONS=Direction.values();
    static final class Cancelled extends RuntimeException {private static final long serialVersionUID=1L;Cancelled(){super(null,null,false,false);}}

    /** Flags for one asset id; {@code displayed} applies the placement's display filter. */
    static short flags(BlockStateSpec spec,BlockState state,boolean displayed,boolean invalid){
        short flags=READY;
        if(spec.isAir()||spec.name().equals("minecraft:structure_void")||!displayed)flags|=SKIP;
        if(invalid)flags|=INVALID;
        if(state.isOpaqueFullCube(net.minecraft.world.EmptyBlockView.INSTANCE,BlockPos.ORIGIN))flags|=OPAQUE;
        if(!state.getFluidState().isEmpty())flags|=FLUID;
        if(state.hasBlockEntity())flags|=ENTITY;
        if(state.isOf(Blocks.LIGHT))flags|=LIGHT;
        var type=state.getRenderType();if(type==net.minecraft.block.BlockRenderType.MODEL)flags|=MODEL;else if(type==net.minecraft.block.BlockRenderType.INVISIBLE)flags|=INVISIBLE;
        return flags;
    }
    /** Resolved states and flags for one placement transform; entries are immutable once flagged READY. */
    static final class Palette {
        final BlockState[] states;final short[] flags;final FrozenModel[] models;FrozenModel marker;
        Palette(int size){states=new BlockState[size];flags=new short[size];models=new FrozenModel[size];}
    }
    /** Where models, fluids and tints come from; the game supplies vanilla's, tests their own. */
    interface Geometry {
        BakedModel model(BlockState state);
        BakedModel marker();
        void fluid(BlockPos pos,BlockRenderView view,VertexConsumer out,BlockState state);
        int tint(BlockState state,int index);
    }
    /** Read-only world access for completion checks and fluid colors. */
    interface World {
        boolean loaded(int chunkX,int chunkZ);
        BlockState state(BlockPos pos);
        int color(BlockPos pos,ColorResolver resolver);
        float brightness(Direction direction,boolean shaded);
        int height();
        int bottom();
        LightingProvider lighting();
    }
    /** Everything a build reads, captured on the render thread. Sections are indexed (dx+1)+(dz+1)*3+(dy+1)*9. */
    static final class Input {
        final SectionKey key;final long generation;final PackedSection[] sections;final Palette palette;final Vec3i localBase,worldBase;final int shiftX,shiftY,shiftZ;
        final LayerRange layer;final int capAxis;final long capLo,capHi;final World world;final Geometry geometry;final Sprite[] lightSprites;
        // world = origin + lx*ex + ly*ey + lz*ez with orthonormal integer axes; the inverse is the transpose.
        final int ox,oy,oz,exx,exz,ezx,ezz;final int[][] offsets=new int[6][];
        Input(SectionKey key,long generation,PackedSection[] sections,Palette palette,PlacementTransform transform,Vec3i localBase,Vec3i worldBase,int shiftX,int shiftY,int shiftZ,
              LayerRange layer,int capAxis,long capLo,long capHi,World world,Geometry geometry,Sprite[] lightSprites){
            if(sections.length!=27||sections[13]==null)throw new IllegalArgumentException("Section neighborhood");
            this.key=key;this.generation=generation;this.sections=sections;this.palette=palette;this.localBase=localBase;this.worldBase=worldBase;this.shiftX=shiftX;this.shiftY=shiftY;this.shiftZ=shiftZ;
            this.layer=layer;this.capAxis=capAxis;this.capLo=capLo;this.capHi=capHi;this.world=world;this.geometry=geometry;this.lightSprites=lightSprites;
            var origin=transform.apply(Vec3i.ZERO);var ex=transform.apply(new Vec3i(1,0,0)).subtract(origin);var ez=transform.apply(new Vec3i(0,0,1)).subtract(origin);
            ox=origin.x();oy=origin.y();oz=origin.z();exx=ex.x();exz=ex.z();ezx=ez.x();ezz=ez.z();
            for(var side:DIRECTIONS){var local=transform.inverse(origin.add(new Vec3i(side.getOffsetX(),side.getOffsetY(),side.getOffsetZ())));offsets[side.ordinal()]=new int[]{local.x(),local.y(),local.z()};}
        }
        /** Asset id at section-relative local coordinates in -16..31, 0 where the neighborhood holds nothing. */
        int id(int x,int y,int z){
            var section=sections[((x>>4)+1)+((z>>4)+1)*3+((y>>4)+1)*9];return section==null?0:section.globalId(x&15,y&15,z&15);
        }
        boolean neighborhood(int x,int y,int z){return x>=-16&&x<32&&y>=-16&&y<32&&z>=-16&&z<32;}
    }
    /** One part's vertices in native memory, released by whoever consumes the output. */
    static final class Part {
        private long address;final int vertices;final short[] owners;
        Part(long address,int vertices,short[] owners){this.address=address;this.vertices=vertices;this.owners=owners;}
        long address(){if(address==0)throw new IllegalStateException("Vertex data released");return address;}
        void free(){if(address!=0){MemoryUtil.nmemFree(address);address=0;}}
    }
    static final class Output {
        final Input input;final ArrayList<Part> parts=new ArrayList<>();
        final QuadVisibility.Mask mask=new QuadVisibility.Mask(),wrong=new QuadVisibility.Mask();
        final RenderedCells.Builder cells=new RenderedCells.Builder();final BitSet omitted=new BitSet(4096),unsupported=new BitSet();
        int[] entityCells=new int[8];int entities;String failure="";boolean failed,cancelled;long vertices,nanos;
        Output(Input input){this.input=input;}
        void entity(int cell){if(entities==entityCells.length)entityCells=Arrays.copyOf(entityCells,entities*2);entityCells[entities++]=cell;}
        void free(){for(var part:parts)part.free();}
    }
    /** Per-thread scratch: one part of vertices, quad ownership, model and fluid capture. */
    private static final class Workspace {
        final Random random=Random.create(0);final ArrayList<FrozenModel.Quad> quads=new ArrayList<>(48);final FluidCapture fluid=new FluidCapture();
        final QuadVisibility.Builder ownership=new QuadVisibility.Builder();final BlockPos.Mutable pos=new BlockPos.Mutable();
        // Lives as long as its pool thread; the pool's threads never time out, so this is allocated at most THREADS times.
        final long address=MemoryUtil.nmemAllocChecked((long)MAX_PART_VERTICES*STRIDE);int vertices;
        void vertex(float x,float y,float z,float u,float v,int r,int g,int b,int a){
            long p=address+(long)vertices*STRIDE;MemoryUtil.memPutFloat(p,x);MemoryUtil.memPutFloat(p+4,y);MemoryUtil.memPutFloat(p+8,z);MemoryUtil.memPutFloat(p+12,u);MemoryUtil.memPutFloat(p+16,v);
            MemoryUtil.memPutByte(p+20,(byte)r);MemoryUtil.memPutByte(p+21,(byte)g);MemoryUtil.memPutByte(p+22,(byte)b);MemoryUtil.memPutByte(p+23,(byte)a);vertices++;
        }
    }
    private static final ThreadLocal<Workspace> WORKSPACE=ThreadLocal.withInitial(Workspace::new);

    /** Builds the whole section; checks {@code cancelled} between cells. Never throws: failures are recorded in the output. */
    static Output build(Input in,java.util.function.LongPredicate cancelled){
        var out=new Output(in);var ws=WORKSPACE.get();ws.vertices=0;ws.ownership.clear();long started=System.nanoTime();
        try{mesh(in,out,ws,cancelled);flush(in,out,ws);}
        catch(Cancelled stop){out.free();out.parts.clear();out.cancelled=true;}
        // Any throwable becomes a failed output: the render thread must always hear back from a started build.
        catch(Throwable e){out.free();out.parts.clear();out.failed=true;out.failure="区段模型生成失败："+e;}
        finally{ws.vertices=0;ws.ownership.clear();out.nanos=System.nanoTime()-started;}
        return out;
    }
    private static void mesh(Input in,Output out,Workspace ws,java.util.function.LongPredicate cancelled){
        var center=in.sections[13];var flags=in.palette.flags;var states=in.palette.states;var view=new View(in);
        int lastChunkX=Integer.MIN_VALUE,lastChunkZ=Integer.MIN_VALUE,scanned=0;boolean chunkLoaded=false;
        for(int i=center.nextNonZero(0);i<PackedSection.VOLUME;i=center.nextNonZero(i+1)){
            if((scanned++&63)==0&&cancelled.test(in.generation))throw new Cancelled();
            int x=i&15,z=(i>>>4)&15,y=i>>>8,id=center.globalId(i);short flag=flags[id];
            if((flag&READY)==0)throw new IllegalStateException("Palette entry not prepared: "+id);
            if((flag&SKIP)!=0)continue;
            int lx=in.localBase.x()+x,ly=in.localBase.y()+y,lz=in.localBase.z()+z;
            int wx=in.ox+lx*in.exx+lz*in.ezx,wy=in.oy+ly,wz=in.oz+lx*in.exz+lz*in.ezz;
            if(!contains(in.layer,wx,wy,wz))continue;
            BlockState state=states[id];boolean model=(flag&MODEL)!=0;int culled=0;
            // Faces hidden by opaque projection neighbours never draw; a fully enclosed block needs no world lookup either.
            if(model){for(var side:DIRECTIONS)if(culled(in,x,y,z,wx,wy,wz,state,side))culled|=1<<side.ordinal();if(culled==63)continue;}
            int chunkX=wx>>4,chunkZ=wz>>4;if(chunkX!=lastChunkX||chunkZ!=lastChunkZ){lastChunkX=chunkX;lastChunkZ=chunkZ;chunkLoaded=in.world.loaded(chunkX,chunkZ);}
            BlockState actual=chunkLoaded?in.world.state(ws.pos.set(wx,wy,wz)):null;
            boolean invalid=(flag&INVALID)!=0;var comparison=invalid?Comparison.UNKNOWN:PrinterRules.comparison(chunkLoaded,actual,state);
            boolean complete=comparison==Comparison.MATCH;out.mask.set(i,complete);out.wrong.set(i,comparison==Comparison.WRONG_BLOCK||comparison==Comparison.WRONG_STATE);
            if(complete){out.omitted.set(i);out.cells.record(i,Block.getRawIdFromState(state));continue;}
            float ax=wx-in.worldBase.x()+in.shiftX,ay=wy-in.worldBase.y()+in.shiftY,az=wz-in.worldBase.z()+in.shiftZ;
            if((flag&LIGHT)!=0){room(in,out,ws,4);light(in,ws,state,ax,ay,az);ws.ownership.append(i,4);continue;}
            ws.quads.clear();ws.fluid.clear();
            try{
                if((flag&FLUID)!=0)in.geometry.fluid(ws.pos.set(wx,wy,wz),view,ws.fluid,state);
                if((flag&INVISIBLE)==0&&model){
                    FrozenModel blockModel=in.palette.models[id];boolean randomized=blockModel.randomized();
                    long seed=randomized?state.getRenderingSeed(ws.pos.set(wx,wy,wz)):0;
                    for(var side:DIRECTIONS){if((culled&(1<<side.ordinal()))!=0)continue;if(randomized)ws.random.setSeed(seed);blockModel.append(side.ordinal(),ws.random,ws.quads);}
                    if(randomized)ws.random.setSeed(seed);blockModel.append(6,ws.random,ws.quads);
                    if(ws.quads.isEmpty()){out.unsupported.set(id);var marker=in.palette.marker;
                        for(var side:DIRECTIONS){ws.random.setSeed(0);marker.append(side.ordinal(),ws.random,ws.quads);}ws.random.setSeed(0);marker.append(6,ws.random,ws.quads);}

                }
                if((flag&ENTITY)!=0)out.entity(i);
            }catch(Cancelled stop){throw stop;}
            catch(RuntimeException e){out.unsupported.set(id);out.failure="Unsupported model: "+state+" (block omitted)";continue;}
            int vertices=ws.quads.size()*4+ws.fluid.size();if(vertices==0)continue;
            room(in,out,ws,vertices);
            for(var quad:ws.quads)quad(ws,quad,ax,ay,az);
            ws.fluid.emit(ws,ax-(wx&15),ay-(wy&15),az-(wz&15));
            ws.ownership.append(i,vertices);
        }
    }
    private static boolean contains(LayerRange layer,int x,int y,int z){int v=switch(layer.axis()){case X->x;case Y->y;case Z->z;};return v>=layer.min()&&v<=layer.max();}
    /** Same rule as the render-thread builder: only displayed, opaque or self-hiding neighbours inside the layer cull. */
    private static boolean culled(Input in,int x,int y,int z,int wx,int wy,int wz,BlockState state,Direction side){
        if(in.capAxis>=0&&side.getAxis().ordinal()==in.capAxis){int c=in.capAxis==0?wx:in.capAxis==1?wy:wz;if(c>=in.capLo&&c<=in.capHi)return false;}
        if(in.layer.mode()!=LayerRange.Mode.ALL&&!contains(in.layer,wx+side.getOffsetX(),wy+side.getOffsetY(),wz+side.getOffsetZ()))return false;
        int[] delta=in.offsets[side.ordinal()];int id=in.id(x+delta[0],y+delta[1],z+delta[2]);short flag=in.palette.flags[id];
        if((flag&READY)==0)throw new IllegalStateException("Palette entry not prepared: "+id);
        if((flag&SKIP)!=0)return false;var neighbor=in.palette.states[id];
        if(neighbor.isAir()||neighbor.isOf(Blocks.STRUCTURE_VOID))return false;
        return (flag&OPAQUE)!=0||state.isSideInvisible(neighbor,side);
    }
    private static void room(Input in,Output out,Workspace ws,int vertices){
        if(vertices>MAX_PART_VERTICES)throw new IllegalArgumentException("Block geometry exceeds one part");
        if(ws.vertices+vertices>MAX_PART_VERTICES)flush(in,out,ws);
        if((out.vertices+ws.vertices+vertices)*(long)STRIDE>MAX_SECTION_BYTES)throw new IllegalArgumentException("区段模型超过资源预算");
    }
    /** Copies the current part out of the scratch buffer at its exact size. */
    private static void flush(Input in,Output out,Workspace ws){
        if(ws.vertices==0)return;int vertices=ws.vertices;short[] owners=ws.ownership.build(vertices);
        long address=MemoryUtil.nmemAlloc((long)vertices*STRIDE);if(address==0)throw new OutOfMemoryError("Projection vertex buffer");
        MemoryUtil.memCopy(ws.address,address,(long)vertices*STRIDE);out.parts.add(new Part(address,vertices,owners));out.vertices+=vertices;
        for(short cell:owners)if(cell>=0){int id=in.sections[13].globalId(cell);if((in.palette.flags[id]&INVALID)==0)out.cells.record(cell,Block.getRawIdFromState(in.palette.states[id]));}
        ws.vertices=0;ws.ownership.clear();
    }
    private static void quad(Workspace ws,FrozenModel.Quad quad,float x,float y,float z){
        int[] data=quad.data;
        for(int v=0;v<4;v++){
            int at=v*6;long p=ws.address+(long)ws.vertices++*STRIDE;
            MemoryUtil.memPutFloat(p,x+Float.intBitsToFloat(data[at]));MemoryUtil.memPutFloat(p+4,y+Float.intBitsToFloat(data[at+1]));MemoryUtil.memPutFloat(p+8,z+Float.intBitsToFloat(data[at+2]));
            MemoryUtil.memPutInt(p+12,data[at+3]);MemoryUtil.memPutInt(p+16,data[at+4]);MemoryUtil.memPutInt(p+20,data[at+5]);
        }
    }
    /** Four coincident centers; alpha=0 tags a marker and RG encodes view-space corners for the vertex shader. */
    private static void light(Input in,Workspace ws,BlockState state,float x,float y,float z){
        var sprite=in.lightSprites[state.get(net.minecraft.state.property.Properties.LEVEL_15)];if(sprite==null)throw new IllegalStateException("Light marker sprite not prepared");
        for(int vertex=0;vertex<4;vertex++){boolean right=vertex==1||vertex==2,top=vertex>=2;
            ws.vertex(x+.5f,y+.5f,z+.5f,right?sprite.getMaxU():sprite.getMinU(),top?sprite.getMinV():sprite.getMaxV(),right?255:0,top?255:0,255,0);}
    }
    /** Captures fluid vertices with the same float-to-byte rounding as BufferBuilder. */
    private static final class FluidCapture implements VertexConsumer {
        private static final int FLOATS=9,MAX=16384;private float[] data=new float[FLOATS*64];private int size;private double x,y,z;private float u,v,r=1,g=1,b=1,a=1;private boolean fixed;
        void clear(){size=0;fixed=false;r=g=b=a=1;}
        int size(){return size;}
        void emit(Workspace ws,float dx,float dy,float dz){for(int i=0;i<size;i++){int at=i*FLOATS;ws.vertex(data[at]+dx,data[at+1]+dy,data[at+2]+dz,data[at+3],data[at+4],(int)(data[at+5]*255f),(int)(data[at+6]*255f),(int)(data[at+7]*255f),(int)(data[at+8]*255f));}}
        @Override public VertexConsumer vertex(double x,double y,double z){this.x=x;this.y=y;this.z=z;return this;}
        @Override public VertexConsumer color(int r,int g,int b,int a){if(!fixed){this.r=r/255f;this.g=g/255f;this.b=b/255f;this.a=a/255f;}return this;}
        @Override public VertexConsumer texture(float u,float v){this.u=u;this.v=v;return this;}
        @Override public VertexConsumer overlay(int u,int v){return this;}
        @Override public VertexConsumer light(int u,int v){return this;}
        @Override public VertexConsumer normal(float x,float y,float z){return this;}
        @Override public void next(){if(size==MAX)throw new IllegalArgumentException("模型顶点超过单方块预算");int at=size++*FLOATS;if(at+FLOATS>data.length)data=Arrays.copyOf(data,Math.min(FLOATS*MAX,data.length*2));data[at]=(float)x;data[at+1]=(float)y;data[at+2]=(float)z;data[at+3]=u;data[at+4]=v;data[at+5]=r;data[at+6]=g;data[at+7]=b;data[at+8]=Math.max(1/255f,a);}
        @Override public void fixedColor(int r,int g,int b,int a){fixed=false;color(r,g,b,a);fixed=true;}
        @Override public void unfixColor(){fixed=false;}
    }
    /** Fluid neighbours from the snapshot, in world coordinates; full-bright like the render-thread view. */
    private static final class View implements BlockRenderView {
        private final Input in;View(Input in){this.in=in;}
        @Override public BlockState getBlockState(BlockPos pos){
            if(!contains(in.layer,pos.getX(),pos.getY(),pos.getZ()))return Blocks.AIR.getDefaultState();
            int dx=pos.getX()-in.ox,dz=pos.getZ()-in.oz;int lx=dx*in.exx+dz*in.exz-in.localBase.x(),ly=pos.getY()-in.oy-in.localBase.y(),lz=dx*in.ezx+dz*in.ezz-in.localBase.z();
            if(!in.neighborhood(lx,ly,lz))return Blocks.AIR.getDefaultState();
            int id=in.id(lx,ly,lz);short flag=in.palette.flags[id];if((flag&READY)==0||(flag&SKIP)!=0)return Blocks.AIR.getDefaultState();return in.palette.states[id];
        }
        @Override public FluidState getFluidState(BlockPos pos){return getBlockState(pos).getFluidState();}
        @Override public BlockEntity getBlockEntity(BlockPos pos){return null;}
        @Override public float getBrightness(Direction direction,boolean shaded){return in.world.brightness(direction,shaded);}
        // Light levels are overridden below; vanilla region views hand out the same provider to builder threads.
        @Override public LightingProvider getLightingProvider(){return in.world.lighting();}
        @Override public int getColor(BlockPos pos,ColorResolver resolver){return in.world.color(pos,resolver);}
        @Override public int getLightLevel(LightType type,BlockPos pos){return 15;}
        @Override public int getBaseLightLevel(BlockPos pos,int ambient){return 15;}
        @Override public int getHeight(){return in.world.height();}
        @Override public int getBottomY(){return in.world.bottom();}
    }
}
