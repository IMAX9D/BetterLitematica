package dev.betterlitematica.io;

import dev.betterlitematica.core.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;
import java.util.zip.CRC32;

/** Complete-source, bounded voxel thumbnails. No world, renderer cache or game API. */
public final class SchematicPreview {
    public static final int VERSION=2,SIDE=256;
    private static final int LOD=160,CONFIG_LOD=128,MAX_PALETTE=131072;
    private static final String FIELD="BetterLitematicaPreview";
    private SchematicPreview(){}
    /** ARGB views: front (+Z), side (+X), top (+Y), isometric (+X,+Y,+Z). */
    public record Images(int side,List<int[]> views,Vec3i size,long blocks){
        public Images{
            if(side!=SIDE||views==null||views.size()!=4||size==null||size.x()<1||size.y()<1||size.z()<1||blocks<0||blocks>SchematicImporter.MAX_CELLS)throw new IllegalArgumentException("Invalid preview images");
            var copies=new ArrayList<int[]>(4);for(var view:views){if(view==null||view.length!=side*side)throw new IllegalArgumentException("Invalid preview pixels");copies.add(view.clone());}views=List.copyOf(copies);
        }
        @Override public List<int[]> views(){return views.stream().map(int[]::clone).toList();}
    }
    /** Owns only a <=160^3 int grid and a bounded palette, never source packed arrays. */
    public static final class Geometry{
        private List<RegionGrid> regions=List.of();
        private final List<BlockStateSpec> palette;private final int[] grid;private final int nx,ny,nz;private final Vec3i size;private final long blocks;private final String digest;
        private Geometry(List<BlockStateSpec> palette,int[] grid,int nx,int ny,int nz,Vec3i size,long blocks,String digest){this.palette=List.copyOf(palette);this.grid=grid;this.nx=nx;this.ny=ny;this.nz=nz;this.size=size;this.blocks=blocks;this.digest=digest;}
        public List<BlockStateSpec> palette(){return palette;}public Vec3i size(){return size;}public long blocks(){return blocks;}public String digest(){return digest;}
        private int at(int x,int y,int z){return x<0||y<0||z<0||x>=nx||y>=ny||z>=nz?0:grid[x+nx*(z+nz*y)];}
    }
    /** Reusable immutable render data. At most 160³ full cells and 64³ interactive cells.
     * Angles and raster scratch belong to each render call, so concurrent reads are safe. */
    public static final class OrbitModel{
        private final Geometry full,drag;private final int[] colors,surfaces;private final int[] low,high;
        private OrbitModel(Geometry full,Geometry drag,int[] colors,int[] surfaces,int[] low,int[] high){this.full=full;this.drag=drag;this.colors=colors;this.surfaces=surfaces;this.low=low;this.high=high;}
    }
    private record Part(Region region,PackedBits blocks,int[] palette){}
    private record RegionGrid(Region region,RegionPlacement placement,Geometry geometry){}
    private static final PlacementTransform IDENTITY=new PlacementTransform(Vec3i.ZERO,0,false,false);
    private record Parsed(List<Part> parts,List<BlockStateSpec> palette,Vec3i min,Vec3i size,String digest){}

    public static Geometry readGeometry(Map<String,Object> root,Cancellation cancel)throws IOException{
        Parsed parsed=parse(root,cancel,true);var size=parsed.size();double cell=Math.max(1,Math.max(size.x(),Math.max(size.y(),size.z()))/(double)LOD);
        int nx=Math.max(1,(int)Math.ceil(size.x()/cell)),ny=Math.max(1,(int)Math.ceil(size.y()/cell)),nz=Math.max(1,(int)Math.ceil(size.z()/cell));
        int[] grid=new int[Math.multiplyExact(nx,Math.multiplyExact(ny,nz))];long blocks=0;
        for(var part:parsed.parts()){
            var r=part.region();int sx=r.size().x(),sz=r.size().z(),index=0;
            // Every source cell participates. Later non-air wins at overlaps; air never erases a region.
            for(int y=0;y<r.size().y();y++){
                int gy=Math.min(ny-1,(int)(((long)r.min().y()+y-parsed.min().y())/cell));
                for(int z=0;z<sz;z++){
                    int gz=Math.min(nz-1,(int)(((long)r.min().z()+z-parsed.min().z())/cell)),row=nx*(gz+nz*gy);
                    for(int x=0;x<sx;x++,index++){
                        if((index&8191)==0)cancel.check();int state=part.blocks().get(index);if(state>=part.palette().length)throw new IOException("Preview palette index outside source palette");
                        int id=part.palette()[state];if(id<0)continue;blocks++;
                        int gx=Math.min(nx-1,(int)(((long)r.min().x()+x-parsed.min().x())/cell));grid[row+gx]=id+1;
                    }
                }
            }
        }
        cancel.check();return new Geometry(parsed.palette(),grid,nx,ny,nz,size,blocks,parsed.digest());
    }
    /** Geometry-only overrides: locking never changes pixels or persisted cache identity. */
    public static Map<String,RegionPlacement> geometryOverrides(Map<String,RegionPlacement> values){
        if(values.size()>1024)throw new IllegalArgumentException("Preview region limit");var copy=new TreeMap<String,RegionPlacement>();
        for(var entry:values.entrySet()){String name=entry.getKey();var v=Objects.requireNonNull(entry.getValue());if(name.isBlank()||name.length()>256)throw new IllegalArgumentException("Invalid preview region");copy.put(name,new RegionPlacement(v.position(),v.quarterTurns(),v.mirrorX(),v.mirrorZ(),v.enabled(),false));}return Collections.unmodifiableMap(copy);
    }
    public static String configuredKey(String sourceSha,Map<String,RegionPlacement> values)throws IOException{
        var map=geometryOverrides(values);if(map.isEmpty())return sourceSha;var digest=sha();var out=new DataOutputStream(new DigestOutputStream(OutputStream.nullOutputStream(),digest));string(out,"configured-preview-v1");string(out,sourceSha);
        for(var entry:map.entrySet()){string(out,entry.getKey());var v=entry.getValue();vector(out,v.position());out.writeInt(v.quarterTurns());out.writeBoolean(v.mirrorX());out.writeBoolean(v.mirrorZ());out.writeBoolean(v.enabled());}out.flush();return HexFormat.of().formatHex(digest.digest());
    }
    public static Geometry readGeometry(Map<String,Object> root,Map<String,RegionPlacement> values,Cancellation cancel)throws IOException{
        var overrides=geometryOverrides(values);if(overrides.isEmpty())return readGeometry(root,cancel);var parsed=parse(root,cancel,true);
        boolean changed=false;for(var p:parsed.parts()){var original=RegionPlacement.original(p.region());var selected=overrides.getOrDefault(p.region().name(),original);if(!selected.equals(original))changed=true;}
        if(!changed)return readGeometry(root,cancel);
        // One common shrink factor keeps the SUM of retained region grids within 160^3 cells,
        // including overlapping regions. Configured grids and their single prepared pose
        // each use <=128^3 cells (combined <=16 MiB). Packed source arrays are not retained.
        double factor=1;for(var part:parsed.parts())if(overrides.getOrDefault(part.region().name(),RegionPlacement.original(part.region())).enabled())factor=Math.max(factor,Math.max(part.region().size().x(),Math.max(part.region().size().y(),part.region().size().z()))/(double)CONFIG_LOD);
        while(true){long count=0;for(var p:parsed.parts())if(overrides.getOrDefault(p.region().name(),RegionPlacement.original(p.region())).enabled()){var size=p.region().size();count+=(long)Math.ceil(size.x()/factor)*(long)Math.ceil(size.y()/factor)*(long)Math.ceil(size.z()/factor);}if(count<=CONFIG_LOD*CONFIG_LOD*CONFIG_LOD)break;factor*=1.05;cancel.check();}
        var parts=new ArrayList<RegionGrid>();long blocks=0;
        for(var part:parsed.parts()){
            cancel.check();var r=part.region();var setting=overrides.getOrDefault(r.name(),RegionPlacement.original(r));if(!setting.enabled())continue;
            int nx=(int)Math.ceil(r.size().x()/factor),ny=(int)Math.ceil(r.size().y()/factor),nz=(int)Math.ceil(r.size().z()/factor);int[] grid=new int[nx*ny*nz];long regionBlocks=0;
            for(int i=0;i<part.blocks().size();i++){if((i&8191)==0)cancel.check();int state=part.blocks().get(i);if(state>=part.palette().length)throw new IOException("Preview palette index outside source palette");int id=part.palette()[state];if(id<0)continue;regionBlocks++;int x=i%r.size().x(),z=i/r.size().x()%r.size().z(),y=i/(r.size().x()*r.size().z());int gx=(int)((long)x*nx/r.size().x()),gy=(int)((long)y*ny/r.size().y()),gz=(int)((long)z*nz/r.size().z());grid[gx+nx*(gz+nz*gy)]=id+1;}
            blocks+=regionBlocks;parts.add(new RegionGrid(r,setting,new Geometry(parsed.palette(),grid,nx,ny,nz,r.size(),regionBlocks,parsed.digest())));
        }
        var result=compose(parts,parsed.palette(),blocks,configuredKey(parsed.digest(),overrides),IDENTITY,CONFIG_LOD,cancel);result.regions=List.copyOf(parts);return result;
    }
    private static PlacementTransform regionTransform(RegionGrid part,PlacementTransform main){
        var sub=part.placement();var linear=new PlacementTransform(Vec3i.ZERO,sub.quarterTurns(),sub.mirrorX(),sub.mirrorZ()).compose(main);
        return new PlacementTransform(main.apply(sub.position()).subtract(linear.apply(part.region().anchor())),linear.quarterTurns(),linear.mirrorX(),linear.mirrorZ());
    }
    private static Geometry compose(List<RegionGrid> parts,List<BlockStateSpec> palette,long blocks,String digest,PlacementTransform main,int lod,Cancellation cancel)throws IOException{
        if(parts.isEmpty())return new Geometry(palette,new int[1],1,1,1,new Vec3i(1,1,1),0,digest);
        Vec3i min=null,max=null;var transforms=new ArrayList<PlacementTransform>(parts.size());
        try{for(var part:parts){cancel.check();var t=regionTransform(part,main);transforms.add(t);var b=PlacementBounds.clipped(part.region(),t,LayerRange.ALL);var hi=b.max().add(new Vec3i(1,1,1));min=min==null?b.min():new Vec3i(Math.min(min.x(),b.min().x()),Math.min(min.y(),b.min().y()),Math.min(min.z(),b.min().z()));max=max==null?hi:new Vec3i(Math.max(max.x(),hi.x()),Math.max(max.y(),hi.y()),Math.max(max.z(),hi.z()));}
            var size=max.subtract(min);double cell=Math.max(1,Math.max(size.x(),Math.max(size.y(),size.z()))/(double)lod);int nx=(int)Math.ceil(size.x()/cell),ny=(int)Math.ceil(size.y()/cell),nz=(int)Math.ceil(size.z()/cell);int[] grid=new int[nx*ny*nz];
            for(int partIndex=0;partIndex<parts.size();partIndex++){var part=parts.get(partIndex);var g=part.geometry();var r=part.region();var t=transforms.get(partIndex);var bx=t.apply(new Vec3i(1,0,0)).subtract(t.origin());var bz=t.apply(new Vec3i(0,0,1)).subtract(t.origin());
                for(int i=0;i<g.grid.length;i++){if((i&4095)==0)cancel.check();int id=g.grid[i];if(id==0)continue;int x=i%g.nx,z=i/g.nx%g.nz,y=i/(g.nx*g.nz);
                    // Transform cell centres about block centres; mirrors must not shift a block by one.
                    double px=r.min().x()+x*r.size().x()/(double)g.nx,py=r.min().y()+y*r.size().y()/(double)g.ny,pz=r.min().z()+z*r.size().z()/(double)g.nz;
                    double wx=bx.x()*px+bz.x()*pz+t.origin().x(),wz=bx.z()*px+bz.z()*pz+t.origin().z();int gx=Math.max(0,Math.min(nx-1,(int)((wx-min.x())/cell))),gy=Math.max(0,Math.min(ny-1,(int)((py+t.origin().y()-min.y())/cell))),gz=Math.max(0,Math.min(nz-1,(int)((wz-min.z())/cell)));grid[gx+nx*(gz+nz*gy)]=id;
                }
            }return new Geometry(palette,grid,nx,ny,nz,size,blocks,digest);
        }catch(ArithmeticException|IllegalArgumentException invalid){throw new IOException("Preview transform exceeds coordinate range",invalid);}
    }
    /** Returns render-only prepared data. World translation deliberately has no visual effect. */
    public static OrbitModel posedOrbitModel(OrbitModel model,PlacementTransform pose,Cancellation cancel)throws IOException{
        var linear=new PlacementTransform(Vec3i.ZERO,pose.quarterTurns(),pose.mirrorX(),pose.mirrorZ());cancel.check();if(linear.equals(IDENTITY)&&model.full.regions.isEmpty())return model;
        Geometry result;if(!model.full.regions.isEmpty())result=compose(model.full.regions,model.full.palette,model.full.blocks,model.full.digest,linear,CONFIG_LOD,cancel);
        else {var g=model.full;var r=new Region("preview",Vec3i.ZERO,new Vec3i(g.nx,g.ny,g.nz));var p=new RegionGrid(r,RegionPlacement.original(r),g);result=compose(List.of(p),g.palette,g.blocks,g.digest,linear,LOD,cancel);}
        return createOrbitModel(result,model.colors,cancel);
    }
    private static Parsed parse(Map<String,Object> input,Cancellation cancel,boolean paletteNeeded)throws IOException{
        cancel.check();Map<String,Object> root=SchematicFormats.canonical(input,cancel);
        int version=NbtReader.integer(root,"Version");if(version!=5&&version!=6)throw new IOException("Unsupported preview source version");
        int dataVersion=NbtReader.integer(root,"MinecraftDataVersion");var regions=NbtReader.compound(root.get("Regions"),"Regions");if(regions.isEmpty()||regions.size()>1024)throw new IOException("Invalid preview regions");
        MessageDigest digest=sha();var hash=new DigestOutputStream(OutputStream.nullOutputStream(),digest);var out=new DataOutputStream(hash);
        out.writeInt(VERSION);out.writeInt(version);out.writeInt(dataVersion);out.writeInt(regions.size());
        var global=new LinkedHashMap<BlockStateSpec,Integer>();var parts=new ArrayList<Part>();Vec3i min=null,max=null;long volume=0;byte[] wordsBuffer=new byte[8192];
        try{
            for(var entry:new TreeMap<>(regions).entrySet()){
                cancel.check();string(out,entry.getKey());var tag=NbtReader.compound(entry.getValue(),"Region");var position=SchematicDocument.vector(tag.get("Position"));var signed=SchematicDocument.vector(tag.get("Size"));
                vector(out,position);vector(out,signed);
                var size=new Vec3i(Math.toIntExact(Math.abs((long)signed.x())),Math.toIntExact(Math.abs((long)signed.y())),Math.toIntExact(Math.abs((long)signed.z())));
                var start=position.add(new Vec3i(signed.x()<0?signed.x()+1:0,signed.y()<0?signed.y()+1:0,signed.z()<0?signed.z()+1:0));var r=new Region(entry.getKey(),start,size,position);
                volume=Math.addExact(volume,r.volume());if(volume>SchematicImporter.MAX_CELLS)throw new IOException("Preview source volume exceeds limit");var end=start.add(size);
                min=min==null?start:new Vec3i(Math.min(min.x(),start.x()),Math.min(min.y(),start.y()),Math.min(min.z(),start.z()));max=max==null?end:new Vec3i(Math.max(max.x(),end.x()),Math.max(max.y(),end.y()),Math.max(max.z(),end.z()));
                if(!(tag.get("BlockStatePalette") instanceof List<?> palette)||palette.isEmpty()||palette.size()>65536)throw new IOException("Invalid preview palette");
                int[] mapping=new int[palette.size()];Arrays.fill(mapping,-1);out.writeInt(palette.size());
                for(int i=0;i<palette.size();i++){
                    if((i&255)==0)cancel.check();var state=NbtReader.compound(palette.get(i),"State");String name=NbtReader.string(state,"Name","");var props=new TreeMap<String,String>();
                    if(state.containsKey("Properties"))for(var prop:NbtReader.compound(state.get("Properties"),"Properties").entrySet()){if(!(prop.getValue() instanceof String text))throw new IOException("Invalid preview state property");props.put(prop.getKey(),text);}
                    string(out,name);out.writeInt(props.size());for(var prop:props.entrySet()){string(out,prop.getKey());string(out,prop.getValue());}
                    if(paletteNeeded){var spec=SourceVersions.state(new BlockStateSpec(name,props),dataVersion);if(visible(spec)){Integer id=global.get(spec);if(id==null){if(global.size()==MAX_PALETTE)throw new IOException("Preview combined palette exceeds limit");id=global.size();global.put(spec,id);}mapping[i]=id;}}
                }
                if(!(tag.get("BlockStates") instanceof long[] words))throw new IOException("Missing preview packed states");int bits=Math.max(2,32-Integer.numberOfLeadingZeros(palette.size()-1));var packed=PackedBits.takeOwnership(bits,Math.toIntExact(r.volume()),words);out.writeInt(words.length);
                for(int i=0;i<words.length;){cancel.check();int count=Math.min(wordsBuffer.length/8,words.length-i);for(int k=0;k<count;k++){long word=words[i++];for(int byteIndex=0;byteIndex<8;byteIndex++)wordsBuffer[k*8+byteIndex]=(byte)(word>>>(56-byteIndex*8));}out.write(wordsBuffer,0,count*8);}
                if(paletteNeeded)parts.add(new Part(r,packed,mapping));
            }
            out.flush();return new Parsed(List.copyOf(parts),List.copyOf(global.keySet()),min,max.subtract(min),HexFormat.of().formatHex(digest.digest()));
        }catch(IllegalArgumentException|ArithmeticException invalid){throw new IOException("Invalid preview geometry",invalid);}
    }
    private static boolean visible(BlockStateSpec state){return !state.isAir()&&!Set.of("minecraft:light","minecraft:barrier","minecraft:structure_void").contains(state.name());}
    private static MessageDigest sha(){try{return MessageDigest.getInstance("SHA-256");}catch(NoSuchAlgorithmException impossible){throw new AssertionError(impossible);}}
    private static void string(DataOutputStream out,String text)throws IOException{byte[] bytes=text.getBytes(StandardCharsets.UTF_8);if(bytes.length>262144)throw new IOException("Preview text exceeds limit");out.writeInt(bytes.length);out.write(bytes);}
    private static void vector(DataOutputStream out,Vec3i v)throws IOException{out.writeInt(v.x());out.writeInt(v.y());out.writeInt(v.z());}

    /** Rasterizes exposed LOD voxel faces with directional and height shading, transparent background. */
    public static Images render(Geometry geometry,int[] colors,Cancellation cancel)throws IOException{
        Objects.requireNonNull(geometry);if(colors==null||colors.length!=geometry.palette().size())throw new IllegalArgumentException("Preview palette/color mismatch");cancel.check();
        int[] low={geometry.nx,geometry.ny,geometry.nz},high={0,0,0};boolean any=false;
        for(int y=0;y<geometry.ny;y++)for(int z=0;z<geometry.nz;z++)for(int x=0;x<geometry.nx;x++){
            if((x+geometry.nx*(z+geometry.nz*y)&8191)==0)cancel.check();if(geometry.at(x,y,z)==0)continue;any=true;low[0]=Math.min(low[0],x);low[1]=Math.min(low[1],y);low[2]=Math.min(low[2],z);high[0]=Math.max(high[0],x+1);high[1]=Math.max(high[1],y+1);high[2]=Math.max(high[2],z+1);
        }
        var views=new ArrayList<int[]>(4);for(int view=0;view<3;view++){
            Raster raster=new Raster(view,low,high);if(any)for(int y=low[1];y<high[1];y++){
                cancel.check();
                for(int z=low[2];z<high[2];z++)for(int x=low[0];x<high[0];x++){
                    if((x&31)==0)cancel.check();int id=geometry.at(x,y,z);if(id==0)continue;int color=colors[id-1];
                    if(view==0&&geometry.at(x,y,z+1)==0)raster.face(x,y,z,2,shade(color,.90));
                    if(view==1&&geometry.at(x+1,y,z)==0)raster.face(x,y,z,0,shade(color,.80));
                    if(view==2&&geometry.at(x,y+1,z)==0)raster.face(x,y,z,1,color);
                }
            }
            raster.finish(cancel);views.add(raster.pixels);
        }
        views.add(renderOrbit(createOrbitModel(geometry,colors,cancel),Math.PI/4,Math.atan(1/Math.sqrt(2)),false,cancel));
        cancel.check();return new Images(SIDE,views,geometry.size(),geometry.blocks());
    }
    private static int shade(int argb,double strength){return (argb&0xff000000)|((int)(((argb>>>16)&255)*strength)<<16)|((int)(((argb>>>8)&255)*strength)<<8)|(int)((argb&255)*strength);}
    private static int faceMask(Geometry g,int x,int y,int z){return (g.at(x+1,y,z)==0?1:0)|(g.at(x,y+1,z)==0?2:0)|(g.at(x,y,z+1)==0?4:0)|(g.at(x-1,y,z)==0?8:0)|(g.at(x,y-1,z)==0?16:0)|(g.at(x,y,z-1)==0?32:0);}
    public static OrbitModel createOrbitModel(Geometry geometry,int[] colors,Cancellation cancel)throws IOException{
        Objects.requireNonNull(geometry);if(colors==null||colors.length!=geometry.palette.size())throw new IllegalArgumentException("Preview palette/color mismatch");cancel.check();
        if(!geometry.regions.isEmpty()){
            // The configured base keeps only separate source grids. The per-Orbit worker owns
            // one prepared pose; retaining an additional identity grid here doubles memory.
            var base=new Geometry(geometry.palette,new int[0],geometry.nx,geometry.ny,geometry.nz,geometry.size,geometry.blocks,geometry.digest);base.regions=geometry.regions;
            return new OrbitModel(base,base,colors.clone(),new int[0],new int[3],new int[]{1,1,1});
        }
        int[] low={geometry.nx,geometry.ny,geometry.nz},high={0,0,0};
        for(int i=0;i<geometry.grid.length;i++){
            if((i&8191)==0)cancel.check();int id=geometry.grid[i];if(id==0)continue;int x=i%geometry.nx,z=i/geometry.nx%geometry.nz,y=i/(geometry.nx*geometry.nz);
            low[0]=Math.min(low[0],x);low[1]=Math.min(low[1],y);low[2]=Math.min(low[2],z);high[0]=Math.max(high[0],x+1);high[1]=Math.max(high[1],y+1);high[2]=Math.max(high[2],z+1);
        }
        if(high[0]==0){Arrays.fill(low,0);high[0]=geometry.nx;high[1]=geometry.ny;high[2]=geometry.nz;}
        int wx=high[0]-low[0],wy=high[1]-low[1],wz=high[2]-low[2];double factor=Math.max(1,Math.max(wx,Math.max(wy,wz))/64d);
        int nx=(int)Math.ceil(wx/factor),ny=(int)Math.ceil(wy/factor),nz=(int)Math.ceil(wz/factor);int[] coarse=new int[nx*ny*nz];
        for(int y=low[1];y<high[1];y++)for(int z=low[2];z<high[2];z++)for(int x=low[0];x<high[0];x++){
            if((x&31)==0)cancel.check();int id=geometry.at(x,y,z);if(id==0)continue;int gx=(x-low[0])*nx/wx,gy=(y-low[1])*ny/wy,gz=(z-low[2])*nz/wz;coarse[gx+nx*(gz+nz*gy)]=id;
        }
        Geometry drag=new Geometry(geometry.palette,coarse,nx,ny,nz,geometry.size,geometry.blocks,geometry.digest);
        int[] exposed=new int[coarse.length];int count=0;
        for(int i=0;i<coarse.length;i++){if((i&8191)==0)cancel.check();if(coarse[i]==0)continue;int x=i%nx,z=i/nx%nz,y=i/(nx*nz),mask=faceMask(drag,x,y,z);if(mask!=0)exposed[count++]=(i<<6)|mask;}
        cancel.check();return new OrbitModel(geometry,drag,colors.clone(),Arrays.copyOf(exposed,count),low,high);
    }
    /** yaw=0 faces +Z, yaw=PI/2 faces +X; positive pitch looks from above.
     * Framing uses one fixed bounding sphere, so rotating never auto-zooms the model. */
    public static int[] renderOrbit(OrbitModel model,double yaw,double pitch,boolean dragging,Cancellation cancel)throws IOException{
        Objects.requireNonNull(model);if(!Double.isFinite(yaw)||!Double.isFinite(pitch))throw new IllegalArgumentException("Invalid preview angle");cancel.check();
        if(!model.full.regions.isEmpty())return renderOrbit(posedOrbitModel(model,IDENTITY,cancel),yaw,pitch,dragging,cancel);
        Geometry g=dragging?model.drag:model.full;Raster raster=new Raster(model,g,yaw,pitch);double[] facing={Math.sin(yaw)*Math.cos(pitch),Math.sin(pitch),Math.cos(yaw)*Math.cos(pitch)};
        int visible=0;for(int a=0;a<3;a++)if(Math.abs(facing[a])>1e-10)visible|=1<<(a+(facing[a]<0?3:0));
        if(dragging){
            for(int i=0;i<model.surfaces.length;i++){if((i&255)==0)cancel.check();int encoded=model.surfaces[i],index=encoded>>>6,mask=encoded&visible;renderCell(raster,g,model.colors,index,mask);}
        }else for(int i=0;i<g.grid.length;i++){
            if((i&2047)==0)cancel.check();if(g.grid[i]==0)continue;int x=i%g.nx,z=i/g.nx%g.nz,y=i/(g.nx*g.nz);renderCell(raster,g,model.colors,i,faceMask(g,x,y,z)&visible);
        }
        raster.finish(cancel);cancel.check();return raster.pixels;
    }
    private static void renderCell(Raster raster,Geometry g,int[] colors,int index,int mask){
        if(mask==0)return;int x=index%g.nx,z=index/g.nx%g.nz,y=index/(g.nx*g.nz),color=colors[g.grid[index]-1];
        for(int face=0;face<6;face++)if((mask&(1<<face))!=0){double brightness=switch(face){case 0->.80;case 1->1;case 2->.90;case 3->.72;case 4->.64;default->.84;};raster.face(x,y,z,face,shade(color,brightness));}
    }
    /** Raw bounded payload; outer cache owns source identity, compression, checksum and publication. */
    public static void writeOrbitModel(OrbitModel model,DataOutput out,Cancellation cancel)throws IOException{
        Objects.requireNonNull(model);cancel.check();if(!model.full.regions.isEmpty()){writeConfigured(model,out,cancel);return;}var g=model.full;out.writeInt(VERSION);out.writeInt(g.nx);out.writeInt(g.ny);out.writeInt(g.nz);out.writeInt(g.size.x());out.writeInt(g.size.y());out.writeInt(g.size.z());out.writeLong(g.blocks);out.writeUTF(g.digest);out.writeInt(model.colors.length);
        for(int i=0;i<model.colors.length;i++){if((i&1023)==0)cancel.check();out.writeInt(model.colors[i]);}
        for(int i=0;i<g.grid.length;i++){if((i&2047)==0)cancel.check();out.writeInt(g.grid[i]);}cancel.check();
    }
    public static OrbitModel readOrbitModel(DataInput in,Cancellation cancel)throws IOException{
        cancel.check();int version=in.readInt();if(version==-VERSION)return readConfigured(in,cancel);if(version!=VERSION)throw new IOException("Unsupported orbit preview version");int nx=in.readInt(),ny=in.readInt(),nz=in.readInt();
        if(nx<1||ny<1||nz<1||nx>LOD||ny>LOD||nz>LOD)throw new IOException("Orbit dimensions exceed limit");var size=new Vec3i(in.readInt(),in.readInt(),in.readInt());
        if(size.x()<1||size.y()<1||size.z()<1)throw new IOException("Invalid orbit source size");double cell=Math.max(1,Math.max(size.x(),Math.max(size.y(),size.z()))/(double)LOD);
        if(nx!=(int)Math.ceil(size.x()/cell)||ny!=(int)Math.ceil(size.y()/cell)||nz!=(int)Math.ceil(size.z()/cell))throw new IOException("Orbit dimensions do not match source");
        long blocks=in.readLong();if(blocks<0||blocks>SchematicImporter.MAX_CELLS)throw new IOException("Invalid orbit block count");String digest=in.readUTF();if(!digest.matches("[a-f0-9]{64}"))throw new IOException("Invalid orbit geometry digest");
        int count=in.readInt();if(count<0||count>MAX_PALETTE)throw new IOException("Invalid orbit palette size");int[] colors=new int[count];for(int i=0;i<count;i++){if((i&1023)==0)cancel.check();colors[i]=in.readInt();}
        int[] grid=new int[nx*ny*nz];long occupied=0;for(int i=0;i<grid.length;i++){if((i&2047)==0)cancel.check();int id=in.readInt();if(id<0||id>count)throw new IOException("Orbit cell outside palette");grid[i]=id;if(id!=0)occupied++;}
        if(occupied>blocks||occupied==0&&blocks!=0)throw new IOException("Invalid orbit occupancy");
        var geometry=new Geometry(Collections.nCopies(count,BlockStateSpec.AIR),grid,nx,ny,nz,size,blocks,digest);return createOrbitModel(geometry,colors,cancel);
    }
    // Configured payload is render-only too: palette entries are colour indices, never export data.
    private static void writeConfigured(OrbitModel model,DataOutput out,Cancellation cancel)throws IOException{
        out.writeInt(-VERSION);out.writeUTF(model.full.digest);out.writeInt(model.colors.length);for(int c:model.colors)out.writeInt(c);out.writeInt(model.full.regions.size());
        for(var part:model.full.regions){cancel.check();var r=part.region();out.writeUTF(r.name());writeVector(out,r.min());writeVector(out,r.size());writeVector(out,r.anchor());var setting=part.placement();writeVector(out,setting.position());out.writeInt(setting.quarterTurns());out.writeBoolean(setting.mirrorX());out.writeBoolean(setting.mirrorZ());var g=part.geometry();out.writeInt(g.nx);out.writeInt(g.ny);out.writeInt(g.nz);out.writeLong(g.blocks);for(int i=0;i<g.grid.length;i++){if((i&2047)==0)cancel.check();out.writeInt(g.grid[i]);}}
    }
    private static void writeVector(DataOutput out,Vec3i v)throws IOException{out.writeInt(v.x());out.writeInt(v.y());out.writeInt(v.z());}
    private static Vec3i readVector(DataInput in)throws IOException{return new Vec3i(in.readInt(),in.readInt(),in.readInt());}
    private static OrbitModel readConfigured(DataInput in,Cancellation cancel)throws IOException{
        String digest=in.readUTF();if(!digest.matches("[a-f0-9]{64}"))throw new IOException("Invalid configured digest");int colorCount=in.readInt();if(colorCount<0||colorCount>MAX_PALETTE)throw new IOException("Invalid configured palette");int[] colors=new int[colorCount];for(int i=0;i<colors.length;i++){if((i&1023)==0)cancel.check();colors[i]=in.readInt();}var palette=List.copyOf(Collections.nCopies(colorCount,BlockStateSpec.AIR));
        int count=in.readInt();if(count<1||count>1024)throw new IOException("Invalid configured regions");var parts=new ArrayList<RegionGrid>();var names=new HashSet<String>();long totalCells=0,totalBlocks=0,totalVolume=0;
        try{for(int n=0;n<count;n++){
            cancel.check();String name=in.readUTF();if(name.isBlank()||name.length()>256||!names.add(name))throw new IOException("Invalid configured region name");var r=new Region(name,readVector(in),readVector(in),readVector(in));totalVolume+=r.volume();if(totalVolume>SchematicImporter.MAX_CELLS)throw new IOException("Configured source volume exceeds limit");var position=readVector(in);int turn=in.readInt();if(turn<0||turn>3)throw new IOException("Invalid configured rotation");var setting=new RegionPlacement(position,turn,in.readBoolean(),in.readBoolean(),true,false);
            int nx=in.readInt(),ny=in.readInt(),nz=in.readInt();if(nx<1||ny<1||nz<1||nx>LOD||ny>LOD||nz>LOD||nx>r.size().x()||ny>r.size().y()||nz>r.size().z())throw new IOException("Invalid configured grid");totalCells+=(long)nx*ny*nz;if(totalCells>CONFIG_LOD*CONFIG_LOD*CONFIG_LOD)throw new IOException("Configured grids exceed budget");long blocks=in.readLong();if(blocks<0||blocks>r.volume())throw new IOException("Invalid configured blocks");totalBlocks+=blocks;int[] grid=new int[nx*ny*nz];long occupied=0;for(int i=0;i<grid.length;i++){if((i&2047)==0)cancel.check();int id=in.readInt();if(id<0||id>colorCount)throw new IOException("Invalid configured cell");grid[i]=id;if(id>0)occupied++;}if(occupied>blocks||occupied==0&&blocks!=0)throw new IOException("Invalid configured occupancy");parts.add(new RegionGrid(r,setting,new Geometry(palette,grid,nx,ny,nz,r.size(),blocks,digest)));
        }}catch(IllegalArgumentException|ArithmeticException invalid){throw new IOException("Invalid configured model",invalid);}
        var geometry=compose(parts,palette,totalBlocks,digest,IDENTITY,CONFIG_LOD,cancel);geometry.regions=List.copyOf(parts);return createOrbitModel(geometry,colors,cancel);
    }
    private static final class Raster{
        private static final int[][] CORNERS={{1,3,7,5},{2,3,7,6},{4,5,7,6},{0,4,6,2},{0,1,5,4},{0,2,3,1}};
        private static final int[] AO_X={1,-1,0,0,1,1,-1,-1},AO_Y={0,0,1,-1,1,-1,1,-1},AO_RADIUS={3,7};
        final int view;final int[] pixels=new int[SIDE*SIDE];final float[] depth=new float[SIDE*SIDE];final double scale,ox,oy;final double[][] points=new double[4][3];
        double sx=1,sy=1,sz=1,tx,ty,tz,rx,rz,vx,vy,vz,dx,dy,dz;
        Raster(int view,int[] low,int[] high){this.view=view;Arrays.fill(depth,Float.NEGATIVE_INFINITY);double minU=Double.POSITIVE_INFINITY,minV=minU,maxU=Double.NEGATIVE_INFINITY,maxV=maxU;double[] p=new double[3];for(int i=0;i<8;i++){project((i&1)==0?low[0]:high[0],(i&2)==0?low[1]:high[1],(i&4)==0?low[2]:high[2],p);minU=Math.min(minU,p[0]);maxU=Math.max(maxU,p[0]);minV=Math.min(minV,p[1]);maxV=Math.max(maxV,p[1]);}scale=(SIDE-32)/Math.max(1,Math.max(maxU-minU,maxV-minV));ox=SIDE*.5-(minU+maxU)*.5*scale;oy=SIDE*.5-(minV+maxV)*.5*scale;}
        Raster(OrbitModel model,Geometry g,double yaw,double pitch){
            view=4;Arrays.fill(depth,Float.NEGATIVE_INFINITY);if(g==model.drag){sx=(model.high[0]-model.low[0])/(double)g.nx;sy=(model.high[1]-model.low[1])/(double)g.ny;sz=(model.high[2]-model.low[2])/(double)g.nz;tx=model.low[0];ty=model.low[1];tz=model.low[2];}
            rx=Math.cos(yaw);rz=-Math.sin(yaw);vx=Math.sin(yaw)*Math.sin(pitch);vy=-Math.cos(pitch);vz=Math.cos(yaw)*Math.sin(pitch);dx=Math.sin(yaw)*Math.cos(pitch);dy=Math.sin(pitch);dz=Math.cos(yaw)*Math.cos(pitch);
            double ax=model.high[0]-model.low[0],ay=model.high[1]-model.low[1],az=model.high[2]-model.low[2];scale=(SIDE-32)/Math.max(1,Math.sqrt(ax*ax+ay*ay+az*az));
            double x=(model.high[0]+model.low[0])*.5,y=(model.high[1]+model.low[1])*.5,z=(model.high[2]+model.low[2])*.5;ox=SIDE*.5-(rx*x+rz*z)*scale;oy=SIDE*.5-(vx*x+vy*y+vz*z)*scale;
        }
        private void project(double x,double y,double z,double[] p){switch(view){case 0->{p[0]=x;p[1]=-y;p[2]=z;}case 1->{p[0]=-z;p[1]=-y;p[2]=x;}case 2->{p[0]=x;p[1]=z;p[2]=y;}case 4->{x=x*sx+tx;y=y*sy+ty;z=z*sz+tz;p[0]=rx*x+rz*z;p[1]=vx*x+vy*y+vz*z;p[2]=dx*x+dy*y+dz*z;}default->{p[0]=(x-z)*.7071067811865476;p[1]=(x+z)*.408248290463863-y*.816496580927726;p[2]=(x+y+z)*.5773502691896258;}}}
        void face(int x,int y,int z,int axis,int color){
            for(int i=0;i<4;i++){int corner=CORNERS[axis][i];project(x+(corner&1),y+((corner>>>1)&1),z+((corner>>>2)&1),points[i]);points[i][0]=points[i][0]*scale+ox;points[i][1]=points[i][1]*scale+oy;}triangle(points[0],points[1],points[2],color);triangle(points[0],points[2],points[3],color);
        }
        private static double edge(double[] a,double[] b,double x,double y){return (b[0]-a[0])*(y-a[1])-(b[1]-a[1])*(x-a[0]);}
        private void triangle(double[] a,double[] b,double[] c,int color){
            double area=edge(a,b,c[0],c[1]);if(Math.abs(area)<1e-10||(color>>>24)==0)return;
            int x0=Math.max(0,(int)Math.floor(Math.min(a[0],Math.min(b[0],c[0])))),x1=Math.min(SIDE-1,(int)Math.ceil(Math.max(a[0],Math.max(b[0],c[0]))));int y0=Math.max(0,(int)Math.floor(Math.min(a[1],Math.min(b[1],c[1])))),y1=Math.min(SIDE-1,(int)Math.ceil(Math.max(a[1],Math.max(b[1],c[1]))));
            for(int y=y0;y<=y1;y++)for(int x=x0;x<=x1;x++){double u=edge(b,c,x+.5,y+.5)/area,v=edge(c,a,x+.5,y+.5)/area,w=1-u-v;if(u< -1e-9||v< -1e-9||w< -1e-9)continue;float d=(float)(u*a[2]+v*b[2]+w*c[2]);int index=x+y*SIDE;if(d>=depth[index]){depth[index]=d;pixels[index]=color;}}
        }
        void finish(Cancellation cancel)throws IOException{
            float min=Float.POSITIVE_INFINITY,max=Float.NEGATIVE_INFINITY;for(int i=0;i<depth.length;i++){if((i&4095)==0)cancel.check();if((pixels[i]>>>24)!=0){min=Math.min(min,depth[i]);max=Math.max(max,depth[i]);}}
            if(!Float.isFinite(min))return;double span=max-min,threshold=.35/scale;
            for(int y=0;y<SIDE;y++){
                if((y&7)==0)cancel.check();for(int x=0;x<SIDE;x++){
                    int index=x+y*SIDE;if((pixels[index]>>>24)==0)continue;double d=depth[index],occlusion=0;
                    for(int radius:AO_RADIUS)for(int k=0;k<8;k++){
                        int xx=x+AO_X[k]*radius,yy=y+AO_Y[k]*radius;if(xx<0||yy<0||xx>=SIDE||yy>=SIDE)continue;int next=xx+yy*SIDE;
                        if(!Float.isFinite(depth[next]))continue;double difference=depth[next]-d;
                        if(difference>threshold)occlusion+=Math.min(1,(difference-threshold)*scale/(radius*1.5));
                    }
                    double fog=span>threshold?.64+.36*(d-min)/span:1;double shade=fog*(1-.40*occlusion/16);pixels[index]=SchematicPreview.shade(pixels[index],shade);
                }
            }
        }
    }

    /** Only our geometry-verified four-view envelope is trusted, never an unverified legacy screenshot. */
    public static Images embedded(Map<String,Object> root,Cancellation cancel)throws IOException{
        cancel.check();try{
            var target=metadataRoot(root);if(!(target.get("Metadata") instanceof Map<?,?>))return null;var metadata=NbtReader.compound(target.get("Metadata"),"Metadata");if(!metadata.containsKey(FIELD))return null;var saved=NbtReader.compound(metadata.get(FIELD),FIELD);
            if(NbtReader.integer(saved,"Version")!=VERSION)return null;
            String digest=NbtReader.string(saved,"Geometry","");if(!digest.equals(parse(root,cancel,false).digest()))return null;
            if(!(saved.get("Views") instanceof List<?> list)||list.size()!=4)return null;var views=new ArrayList<int[]>(4);for(Object value:list){cancel.check();if(!(value instanceof int[] image)||image.length!=SIDE*SIDE)return null;views.add(image);}
            var size=SchematicDocument.vector(saved.get("Size"));long blocks=number(saved,"Blocks");Images result=new Images(NbtReader.integer(saved,"Side"),views,size,blocks);if(number(saved,"CRC")!=checksum(result))return null;return result;
        }catch(InterruptedIOException cancelled){throw cancelled;}catch(IOException|IllegalArgumentException malformed){return null;}
    }
    /** Mutates metadata in this in-memory tree only. Caller chooses a new output file. */
    public static void embed(Map<String,Object> root,Images images,Cancellation cancel)throws IOException{
        Objects.requireNonNull(images);var parsed=parse(root,cancel,false);if(!parsed.size().equals(images.size()))throw new IOException("Preview/source size mismatch");
        var saved=new LinkedHashMap<String,Object>();saved.put("Version",VERSION);saved.put("Geometry",parsed.digest());saved.put("Side",images.side());saved.put("Size",LitematicExport.vector(images.size()));saved.put("Blocks",images.blocks());saved.put("Views",images.views());saved.put("CRC",checksum(images));
        var target=metadataRoot(root);var metadata=target.containsKey("Metadata")?NbtReader.compound(target.get("Metadata"),"Metadata"):new LinkedHashMap<String,Object>();metadata.put(FIELD,saved);metadata.put("PreviewImageData",images.views.get(3).clone());cancel.check();
        if(root.containsKey("Schematic")){var nested=new LinkedHashMap<>(target);nested.put("Metadata",metadata);root.put("Schematic",nested);}else root.put("Metadata",metadata);
    }
    private static Map<String,Object> metadataRoot(Map<String,Object> root)throws IOException{return root.containsKey("Schematic")?NbtReader.compound(root.get("Schematic"),"Schematic"):root;}
    private static long number(Map<String,Object> root,String key)throws IOException{Object value=root.get(key);if(!(value instanceof Number number)||value instanceof Float||value instanceof Double)throw new IOException("Invalid preview number");return number.longValue();}
    static long checksum(Images images){CRC32 crc=new CRC32();crcInt(crc,images.side());crcInt(crc,images.size().x());crcInt(crc,images.size().y());crcInt(crc,images.size().z());crcInt(crc,(int)(images.blocks()>>>32));crcInt(crc,(int)images.blocks());for(var view:images.views)for(int color:view)crcInt(crc,color);return crc.getValue();}
    private static void crcInt(CRC32 crc,int value){crc.update(value>>>24);crc.update(value>>>16);crc.update(value>>>8);crc.update(value);}
}
