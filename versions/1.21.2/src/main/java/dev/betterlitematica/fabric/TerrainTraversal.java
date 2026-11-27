package dev.betterlitematica.fabric;

import dev.betterlitematica.fabric.TerrainChunkHandle;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.client.render.*;
import net.minecraft.client.render.chunk.ChunkBuilder;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.util.math.*;
import org.joml.FrustumIntersection;
import org.joml.Matrix4f;
import java.util.Arrays;
import java.util.BitSet;

public final class TerrainTraversal {
    private static final Direction[] DIRECTIONS=Direction.values();
    private static final int ALL=63;
    private static final double MARGIN=4;
    private static final float EXTENT=5.25f;
    private ChunkBuilder.BuiltChunk[] source;
    private ChunkBuilder.BuiltChunk[] infos;
    private ChunkBuilder.ChunkData[] data;
    private long[] connections;
    private int[] queue,horizontalSteps,columns,originY,columnX,columnZ,columnMask;
    private long[] visitsByFrame;
    private byte[] verticalMask;
    private boolean[] loaded,relevantColumns,frontierColumns;
    private int frame,side,height,plane,head,tail,sectionX,sectionY,sectionZ;
    private double cameraX,cameraY,cameraZ,viewX,viewY,viewZ;
    private float range;
    private Frustum view;
    private FrustumIntersection intersection;
    private final Matrix4f cachedMatrix=new Matrix4f();
    private final ObjectArrayList<ChunkBuilder.BuiltChunk> active=new ObjectArrayList<>();
    private int[] candidates;
    private int[] rankBySection;
    private final BitSet drawableRanks=new BitSet();
    private BitSet changedSections=new BitSet();
    private TerrainSectionChanges sectionChanges;
    private long unlocatedRevision;
    private int candidateCount,cachedX,cachedY,cachedZ;
    private double cachedCameraX,cachedCameraY,cachedCameraZ,cachedViewX,cachedViewY,cachedViewZ;
    private long cachedTopology;
    private long activeMembership,activeDirty,activeAuditAt,pruneAt;
    private long columnRevision=-1, columnAuditAt;
    private boolean storageMoved=true;
    public void storageMoved(){storageMoved=true;}
    private boolean cached,cachedCulling;
    public long visits,drawables,walkNanos,walks,reuses,cacheDataChanges,walkDataChanges,totalVisits,candidateReads;
    public long missColumns,missTopology,missView,missSection,missMovement;
    public void clear(){source=null;infos=null;data=null;connections=visitsByFrame=null;queue=horizontalSteps=columns=originY=columnX=columnZ=columnMask=candidates=rankBySection=null;verticalMask=null;loaded=relevantColumns=frontierColumns=null;view=null;intersection=null;frame=0;cached=false;candidateCount=0;active.clear();drawableRanks.clear();sectionChanges=null;changedSections=new BitSet();activeAuditAt=pruneAt=0;}
    private void attach(BuiltChunkStorage storage,int distance,int verticalSections){
        if(source==storage.chunks)return;
        clear();source=storage.chunks;storageMoved=true;side=distance*2+1;height=verticalSections;plane=side*height;
        if(source.length!=plane*side)throw new IllegalStateException("地形区段尺寸不一致");
        int n=source.length;infos=new ChunkBuilder.BuiltChunk[n];data=new ChunkBuilder.ChunkData[n];connections=new long[n];
        visitsByFrame=new long[n];queue=new int[n];candidates=new int[n];loaded=new boolean[side*side];relevantColumns=new boolean[side*side];frontierColumns=new boolean[side*side];horizontalSteps=new int[side*side*4];
        columns=new int[n];originY=new int[n];verticalMask=new byte[n];columnX=new int[side*side];columnZ=new int[side*side];columnMask=new int[side*side];
        rankBySection=new int[n];sectionChanges=new TerrainSectionChanges(n);
        for(var chunk:source){
            if(chunk instanceof TerrainSectionChanges.Owner owner)owner.betterlitematica$watch(sectionChanges);
            else{sectionChanges=null;break;}
        }
        for(int z=0;z<side;z++)for(int y=0;y<height;y++)for(int x=0;x<side;x++){
            int i=(z*height+y)*side+x;
            columns[i]=z*side+x;originY[i]=source[i].getOrigin().getY();verticalMask[i]=(byte)(60|(y>0?1:0)|(y+1<height?2:0));
        }
        for(int z=0;z<side;z++)for(int x=0;x<side;x++){
            int b=(z*side+x)*4;
            horizontalSteps[b]=z==0?(side-1)*plane:-plane;horizontalSteps[b+1]=z+1==side?-(side-1)*plane:plane;
            horizontalSteps[b+2]=x==0?side-1:-1;horizontalSteps[b+3]=x+1==side?1-side:1;
        }
    }
    public void collect(BuiltChunkStorage storage,ClientWorld world,Vec3d camera,Frustum frustum,int distance,boolean occlusion,ObjectArrayList<ChunkBuilder.BuiltChunk> output){
        long started=System.nanoTime();attach(storage,distance,world.countVerticalSections());
        long membership=TerrainDataUpdates.revision(),dirty=TerrainRebuildUpdates.revision();
        if(++frame==0){Arrays.fill(visitsByFrame,0);frame=1;}head=tail=0;output.clear();visits=drawables=0;
        cameraX=camera.x;cameraY=camera.y;cameraZ=camera.z;
        sectionX=(int)Math.floor(camera.x/16);sectionY=(int)Math.floor(camera.y/16);sectionZ=(int)Math.floor(camera.z/16);
        range=distance*16f+(float)(MARGIN*Math.sqrt(2));view=frustum;intersection=null;
        if(frustum.getClass()==Frustum.class&&frustum instanceof TerrainView f){intersection=f.betterlitematica$intersection();viewX=f.betterlitematica$x();viewY=f.betterlitematica$y();viewZ=f.betterlitematica$z();}
        boolean columnsChanged=false;
        var manager=world.getChunkManager();
        long revision=manager instanceof TerrainColumnRevision tracked?tracked.betterlitematica$columnRevision():-1;

        if(storageMoved||revision<0||columnRevision!=revision||started-columnAuditAt>=50_000_000L){
            storageMoved=false;columnRevision=revision;columnAuditAt=started;
            for(int z=0;z<side;z++)for(int x=0;x<side;x++){
                int column=z*side+x;var origin=source[z*plane+x].getOrigin();
                columnsChanged|=columnX[column]!=origin.getX()||columnZ[column]!=origin.getZ();columnX[column]=origin.getX();columnZ[column]=origin.getZ();
                boolean available=manager.isChunkLoaded(origin.getX()>>4,origin.getZ()>>4);
                columnsChanged|=loaded[column]!=available&&relevantColumns[column];loaded[column]=available;
            }
        }
        long topology=TerrainDataUpdates.topologyStamp();
        long unlocated=TerrainSectionChanges.unlocatedRevision();
        if(sectionChanges!=null){changedSections.clear();changedSections=sectionChanges.drain(changedSections);}
        if(!columnsChanged&&(pruneAt==0||started<pruneAt)&&canReuse(frustum,occlusion,topology)){

            boolean beforeAudit=candidateCount>4096&&started-activeAuditAt<50_000_000L;
            boolean tracked=sectionChanges!=null&&unlocated==unlocatedRevision&&beforeAudit;
            boolean shortPath=beforeAudit&&unlocated==unlocatedRevision&&topology==cachedTopology&&membership==activeMembership&&dirty==activeDirty;
            boolean valid;
            if(tracked)valid=appendChanges(output,occlusion,started);
            else if(shortPath){appendActive(output);valid=true;}
            else valid=appendCached(output,occlusion,started);
            if(valid&&tracked&&sectionChanges.hasPending()){

                output.clear();drawables=0;tracked=shortPath=false;
                valid=appendCached(output,occlusion,started);
            }
            if(valid&&topology==TerrainDataUpdates.topologyStamp()){

                cachedTopology=topology;
                if(!tracked&&!shortPath){
                    rememberActive(output,membership,dirty,started);
                    unlocatedRevision=unlocated;
                }
                reuses++;view=null;intersection=null;return;
            }
        }
        if(cached){
            if(columnsChanged)missColumns++;
            if(topology==Long.MIN_VALUE||topology!=cachedTopology)missTopology++;
            if(sectionX!=cachedX||sectionY!=cachedY||sectionZ!=cachedZ)missSection++;
            if(intersection==null||!cachedMatrix.equals(((TerrainView)frustum).betterlitematica$matrix()))missView++;
            if(Math.abs(cameraX-cachedCameraX)>MARGIN||Math.abs(cameraY-cachedCameraY)>MARGIN||Math.abs(cameraZ-cachedCameraZ)>MARGIN
                    ||Math.abs(viewX-cachedViewX)>MARGIN||Math.abs(viewY-cachedViewY)>MARGIN||Math.abs(viewZ-cachedViewZ)>MARGIN)missMovement++;
        }
        for(int rank=0;rank<candidateCount;rank++)rankBySection[candidates[rank]]=0;
        drawableRanks.clear();output.clear();drawables=0;candidateCount=0;cached=false;pruneAt=0;Arrays.fill(relevantColumns,false);Arrays.fill(frontierColumns,false);

        for(int z=0;z<side;z++)for(int x=0;x<side;x++){
            int c=z*side+x,n=c+(z==0?(side-1)*side:-side),s=c+(z+1==side?-(side-1)*side:side),w=c+(x==0?side-1:-1),e=c+(x+1==side?1-side:1);
            columnMask[c]=3|(loaded[n]&&columnZ[n]==columnZ[c]-16?4:0)|(loaded[s]&&columnZ[s]==columnZ[c]+16?8:0)
                    |(loaded[w]&&columnX[w]==columnX[c]-16?16:0)|(loaded[e]&&columnX[e]==columnX[c]+16?32:0);
        }
        int bottom=world.getBottomSectionCoord(),top=world.getTopSectionCoord()+1;
        if(sectionY>=bottom&&sectionY<top){
            var start=storage.getRenderedChunk(new BlockPos(sectionX*16,sectionY*16,sectionZ*16));
            if(start!=null&&start.getOrigin().getX()==sectionX*16&&start.getOrigin().getZ()==sectionZ*16){
                relevantColumns[columns[start.index]]=true;if(loaded[columns[start.index]])enqueue(start.index,0);
            }
        }else{

            int y=sectionY<bottom?bottom:top-1,face=sectionY<bottom?0:1;var pos=new BlockPos.Mutable();
            for(int radius=0;radius<=distance*2;radius++)for(int dz=-Math.min(distance,radius);dz<=Math.min(distance,radius);dz++){
                int dx=radius-Math.abs(dz);if(dx>distance)continue;
                seed(storage,pos,sectionX-dx,y,sectionZ+dz,1<<face);if(dx!=0)seed(storage,pos,sectionX+dx,y,sectionZ+dz,1<<face);
            }
        }

        int read=0,write=tail;long stamp=(long)frame<<6;var states=visitsByFrame;var work=queue;
        while(read<write){
            int id=work[read++],column=columns[id],x=columnX[column],y=originY[id],z=columnZ[column];var chunk=source[id];visits++;
            relevantColumns[column]=true;
            if(!visible(x,y,z,chunk))continue;var current=chunk.getData();
            int rank=candidateCount++;candidates[rank]=id;rankBySection[id]=rank+1;
            if(append(id,chunk,current,output))drawableRanks.set(rank);
            int exits=ALL;
            if(occlusion){if(data[id]!=current){walkDataChanges++;data[id]=current;connections[id]=encode(current);}exits=exits(connections[id],(int)states[id]&ALL);}
            exits&=outward(x>>4,y>>4,z>>4,sectionX,sectionY,sectionZ)&verticalMask[id];

            if(!frontierColumns[column]){
                frontierColumns[column]=true;
                for(int d=0;d<4;d++)relevantColumns[columns[id+horizontalSteps[column*4+d]]]=true;
            }
            exits&=columnMask[column];
            while(exits!=0){
                int direction=Integer.numberOfTrailingZeros(exits);exits&=exits-1;
                int next=id+(direction==0?-side:direction==1?side:horizontalSteps[column*4+direction-2]);
                long previous=states[next],face=1L<<(direction^1);
                if((previous&~63L)!=stamp){states[next]=stamp|face;work[write++]=next;}else states[next]=previous|face;
            }
        }
        if(intersection!=null&&topology!=Long.MIN_VALUE&&topology==TerrainDataUpdates.topologyStamp()){
            cachedMatrix.set(((TerrainView)frustum).betterlitematica$matrix());
            cachedX=sectionX;cachedY=sectionY;cachedZ=sectionZ;cachedCulling=occlusion;cachedTopology=topology;
            cachedCameraX=cameraX;cachedCameraY=cameraY;cachedCameraZ=cameraZ;cachedViewX=viewX;cachedViewY=viewY;cachedViewZ=viewZ;cached=true;
        }
        rememberActive(output,membership,dirty,started);
        unlocatedRevision=unlocated;
        view=null;intersection=null;walkNanos+=System.nanoTime()-started;walks++;totalVisits+=visits;
    }
    private void rememberActive(ObjectArrayList<ChunkBuilder.BuiltChunk> output,long membership,long dirty,long time){
        active.clear();active.addAll(output);activeMembership=membership;activeDirty=dirty;activeAuditAt=time;
    }
    private void appendActive(ObjectArrayList<ChunkBuilder.BuiltChunk> output){
        int count=0;
        for(int i=0,size=active.size();i<size;i++){
            var info=active.get(i);var chunk=((TerrainChunkHandle)info).betterlitematica$chunk();var current=chunk.getData();
            if(chunk.needsRebuild()||current.hasNonEmptyLayers()||!current.getBlockEntities().isEmpty()){output.add(info);active.set(count++,info);drawables++;}
            else drawableRanks.clear(rankBySection[chunk.index]-1);
        }
        active.size(count);
    }
    private boolean canReuse(Frustum frustum,boolean occlusion,long topology){
        return cached&&intersection!=null&&topology!=Long.MIN_VALUE&&occlusion==cachedCulling
                &&sectionX==cachedX&&sectionY==cachedY&&sectionZ==cachedZ
                &&Math.abs(cameraX-cachedCameraX)<=MARGIN&&Math.abs(cameraY-cachedCameraY)<=MARGIN&&Math.abs(cameraZ-cachedCameraZ)<=MARGIN
                &&Math.abs(viewX-cachedViewX)<=MARGIN&&Math.abs(viewY-cachedViewY)<=MARGIN&&Math.abs(viewZ-cachedViewZ)<=MARGIN
                &&cachedMatrix.equals(((TerrainView)frustum).betterlitematica$matrix());
    }
    private boolean appendCached(ObjectArrayList<ChunkBuilder.BuiltChunk> output,boolean occlusion,long time){
        for(int i=0;i<candidateCount;i++){
            candidateReads++;
            int id=candidates[i];var chunk=source[id];var current=chunk.getData();
            if(occlusion&&!sameReachability(id,current,time))return false;
            drawableRanks.set(i,append(id,chunk,current,output));
        }return true;
    }
    private boolean sameReachability(int id,ChunkBuilder.ChunkData current,long time){
        if(data[id]==current)return true;
        cacheDataChanges++;long next=encode(current),previous=connections[id];

        if((next&~previous)!=0)return false;
        if(next!=previous&&pruneAt==0)pruneAt=time+200_000_000L;
        data[id]=current;connections[id]=next;
        return true;
    }
    private boolean appendChanges(ObjectArrayList<ChunkBuilder.BuiltChunk> output,boolean occlusion,long time){
        boolean membershipChanged=false;
        for(int id=changedSections.nextSetBit(0);id>=0;id=changedSections.nextSetBit(id+1)){
            int rank=rankBySection[id]-1;
            if(rank<0)continue;
            var chunk=source[id];var current=chunk.getData();candidateReads++;
            if(occlusion&&!sameReachability(id,current,time))return false;
            boolean draw=drawable(chunk,current);
            if(draw!=drawableRanks.get(rank)){drawableRanks.set(rank,draw);membershipChanged=true;}
        }
        if(!membershipChanged){appendActive(output);return true;}

        for(int rank=drawableRanks.nextSetBit(0);rank>=0;rank=drawableRanks.nextSetBit(rank+1)){
            int id=candidates[rank];var chunk=source[id];
            if(!append(id,chunk,chunk.getData(),output))drawableRanks.clear(rank);
        }
        active.clear();active.addAll(output);
        return true;
    }
    private static boolean drawable(ChunkBuilder.BuiltChunk chunk,ChunkBuilder.ChunkData current){
        return chunk.needsRebuild()||current.hasNonEmptyLayers()||!current.getBlockEntities().isEmpty();
    }
    private boolean append(int id,ChunkBuilder.BuiltChunk chunk,ChunkBuilder.ChunkData current,ObjectArrayList<ChunkBuilder.BuiltChunk> output){
        if(!drawable(chunk,current))return false;
        var info=infos[id];if(info==null)infos[id]=info=chunk;output.add(info);drawables++;return true;
    }
    private void seed(BuiltChunkStorage storage,BlockPos.Mutable pos,int x,int y,int z,int incoming){
        var chunk=storage.getRenderedChunk(pos.set(x*16,y*16,z*16));if(chunk!=null&&chunk.getOrigin().equals(pos)){
            relevantColumns[columns[chunk.index]]=true;if(loaded[columns[chunk.index]])enqueue(chunk.index,incoming);
        }
    }
    private void enqueue(int id,int face){
        long stamp=(long)frame<<6,previous=visitsByFrame[id];
        if((previous&~63L)!=stamp){visitsByFrame[id]=stamp|face;queue[tail++]=id;}else visitsByFrame[id]=previous|face;
    }
    private boolean visible(int x,int y,int z,ChunkBuilder.BuiltChunk chunk){
        double dx=Math.max(x-cameraX,Math.max(0,cameraX-(x+16))),dz=Math.max(z-cameraZ,Math.max(0,cameraZ-(z+16))),dy=Math.max(y-cameraY,Math.max(0,cameraY-(y+16)));
        if(dx*dx+dz*dz>range*range||dy>range)return false;
        if(intersection!=null){float ax=(float)(x-viewX),ay=(float)(y-viewY),az=(float)(z-viewZ);return intersection.testAab(ax-EXTENT,ay-EXTENT,az-EXTENT,ax+16+EXTENT,ay+16+EXTENT,az+16+EXTENT);}
        return view.isVisible(((TerrainExpandedBounds)chunk).betterlitematica$expandedBounds(chunk.getBoundingBox()));
    }
    static long encode(ChunkBuilder.ChunkData data){

        if(data==ChunkBuilder.ChunkData.UNPROCESSED||data==ChunkBuilder.ChunkData.EMPTY||data==TerrainEmptyData.INSTANCE)return (1L<<36)-1;
        if(data.getClass()==ChunkBuilder.ChunkData.class&&data.occlusionGraph!=null
                &&data.occlusionGraph.getClass()==net.minecraft.client.render.chunk.ChunkOcclusionData.class){
            var visible=data.occlusionGraph.visibility;
            if(visible.nextClearBit(0)>=36)return (1L<<36)-1;
            long bits=0;
            for(int bit=visible.nextSetBit(0);bit>=0&&bit<36;bit=visible.nextSetBit(bit+1))bits|=1L<<((bit%6)*6+bit/6);
            return bits;
        }
        long bits=0;for(int a=0;a<6;a++)for(int b=0;b<6;b++)if(data.isVisibleThrough(DIRECTIONS[a],DIRECTIONS[b]))bits|=1L<<(a*6+b);return bits;
    }
    static int exits(long connections,int incoming){
        if(connections==(1L<<36)-1)return ALL;
        if(connections==0)return 0;
        int result=0,faces=incoming==0?ALL:incoming;
        while(faces!=0){int face=Integer.numberOfTrailingZeros(faces);faces&=faces-1;result|=(int)(connections>>>(face*6))&ALL;}return result;
    }
    static int outward(int x,int y,int z,int cx,int cy,int cz){return (y<=cy?1:0)|(y>=cy?2:0)|(z<=cz?4:0)|(z>=cz?8:0)|(x<=cx?16:0)|(x>=cx?32:0);}
}
