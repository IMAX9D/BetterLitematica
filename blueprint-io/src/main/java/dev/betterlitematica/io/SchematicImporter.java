package dev.betterlitematica.io;

import dev.betterlitematica.core.*;
import java.io.*;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import java.util.function.Consumer;

public final class SchematicImporter {
    public static final long MAX_CELLS=536_870_912L, MAX_SOURCE_BYTES=512L<<20;

    public static final NbtReader.Limits SOURCE_LIMITS=SchematicReadLimits.fullSource();
    public record Progress(String phase,long completed,long total) {}
    public record Result(Path path,boolean cacheHit,long elapsedNanos) {}
    private record RegionData(Region region,PackedBits blocks,int[] remap) {}
    private record Parsed(BlueprintMetadata metadata,List<RegionData> regions) {}
    private SchematicImporter() {}
    public static Result importFile(Path source,Path directory,Cancellation cancel,Consumer<Progress> progress)throws IOException{
        long started=System.nanoTime();cancel.check();
        if(!Files.isRegularFile(source)||Files.size(source)>MAX_SOURCE_BYTES)throw new IOException("Source missing or exceeds 512 MiB compressed limit");
        long initialSize=Files.size(source);var initialTime=Files.getLastModifiedTime(source);
        progress.accept(new Progress("hash",0,initialSize));String digest=sha256(source,cancel);
        Path target=directory.resolve(digest+"-"+SourceVersions.cacheKey()+".bpc");
        if(Files.isRegularFile(target)){
            try(BlueprintCache existing=BlueprintCache.open(target)){
                if(existing.metadata().sourceSha256().equals(digest)){
                    progress.accept(new Progress("cached",1,1));return new Result(target,true,System.nanoTime()-started);
                }
            }catch(IOException e){cancel.check();  }
        }
        progress.accept(new Progress("parse NBT",0,1));
        Map<String,Object> root=NbtReader.read(source,SOURCE_LIMITS,cancel);
        if(root.containsKey("size")&&root.containsKey("blocks")||root.get("Blocks") instanceof byte[])root=SchematicFormats.canonical(root,cancel);
        Parsed parsed;
        try{parsed=parse(root,digest,source.getFileName().toString(),cancel);}
        catch(IllegalArgumentException|ArithmeticException e){throw new IOException("Invalid schematic: "+e.getMessage(),e);}
        long total=0;for(RegionData r:parsed.regions)total+=sectionCount(r.region);
        long done=0;
        try(BlueprintCache.Writer writer=new BlueprintCache.Writer(target,parsed.metadata)){
            SectionStrip strip=new SectionStrip(parsed.metadata.palette().size());
            for(int regionId=0;regionId<parsed.regions.size();regionId++){
                RegionData data=parsed.regions.get(regionId);
                done=strip.writeRegion(writer,regionId,data,cancel,progress,done,total);
            }

            if(Files.size(source)!=initialSize||!Files.getLastModifiedTime(source).equals(initialTime))throw new IOException("Source changed during import; retry");
            writer.commit(cancel);
        }
        progress.accept(new Progress("ready",1,1));return new Result(target,false,System.nanoTime()-started);
    }

    private static final class SectionStrip {
        private static final int WIDTH=32;
        private final int[][] cells=new int[WIDTH][];
        private final boolean[] touched=new boolean[WIDTH];
        private final PackedSection.Encoder encoder;
        SectionStrip(int paletteSize){encoder=new PackedSection.Encoder(paletteSize);}

        long writeRegion(BlueprintCache.Writer writer,int regionId,RegionData source,Cancellation cancel,
                         Consumer<Progress> progress,long done,long total)throws IOException {
            Vec3i size=source.region().size();
            int sectionsX=(size.x()+15)>>>4;
            int nonAir=0;
            boolean zeroIsAir=source.remap()[0]==0;
            for(int y0=0;y0<size.y();y0+=16){
                for(int z0=0;z0<size.z();z0+=16){
                    for(int first=0;first<sectionsX;first+=WIDTH){
                        int sections=Math.min(WIDTH,sectionsX-first);
                        int x0=first<<4, width=Math.min(sections<<4,size.x()-x0);
                        for(int y=0;y<16 && y0+y<size.y();y++){
                            cancel.check();
                            for(int z=0;z<16 && z0+z<size.z();z++){
                                int row=((y0+y)*size.z()+z0+z)*size.x()+x0;
                                int end=row+width;
                                for(int i=row;i<end;){
                                    int local=source.blocks().get(i);
                                    if(local==0 && zeroIsAir){
                                        i=source.blocks().nextNonZero(i+1,end);
                                        continue;
                                    }
                                    if(local>=source.remap().length)throw new IOException("Block index outside source palette");
                                    int global=source.remap()[local];
                                    int x=i++-row;
                                    if(global==0)continue;
                                    int slot=x>>>4;
                                    if(cells[slot]==null)cells[slot]=new int[PackedSection.VOLUME];
                                    cells[slot][(y<<8)|(z<<4)|(x&15)]=global;
                                    touched[slot]=true;
                                    writer.count(regionId,global);
                                    nonAir++;
                                }
                            }
                        }
                        for(int slot=0;slot<sections;slot++){
                            if(!touched[slot])continue;
                            cancel.check();
                            writer.add(new SectionKey(regionId,first+slot,y0>>>4,z0>>>4),encoder.encode(cells[slot]));
                            Arrays.fill(cells[slot],0);
                            touched[slot]=false;
                        }
                        done+=sections;
                        progress.accept(new Progress("index sections",done,total));
                    }
                }
            }

            writer.count(regionId,0,source.region().volume()-nonAir);
            return done;
        }
    }
    private static long sectionCount(Region r){return ((r.size().x()+15L)/16)*((r.size().y()+15L)/16)*((r.size().z()+15L)/16);}
    public static String sha256(Path file,Cancellation cancel)throws IOException{
        MessageDigest hash;
        try{hash=MessageDigest.getInstance("SHA-256");}catch(NoSuchAlgorithmException e){throw new IllegalStateException(e);}
        try(InputStream in=Files.newInputStream(file)){byte[] buffer=new byte[64*1024];int n;while((n=in.read(buffer))!=-1){cancel.check();hash.update(buffer,0,n);}}
        return HexFormat.of().formatHex(hash.digest());
    }
    private static Parsed parse(Map<String,Object> root,String digest,String filename,Cancellation cancel)throws IOException{
        LinkedHashMap<BlockStateSpec,Integer> global=new LinkedHashMap<>();global.put(BlockStateSpec.AIR,0);
        List<RegionData> regions=new ArrayList<>();List<String> warnings=new ArrayList<>();
        warnings.add("Block previews and full source details are loaded separately; exports always read the source file.");
        if(SourceVersions.cacheKey().equals("raw"))warnings.add("This standalone importer has no game data fixer installed.");
        String name;int dataVersion,formatVersion;var skipped=new HashSet<String>();
        if(root.containsKey("Regions")){
            int format=NbtReader.integer(root,"Version");
            SourceVersions.requireLitematicVersion(format);formatVersion=format;
            dataVersion=NbtReader.integer(root,"MinecraftDataVersion");
            Map<String,Object> meta=optionalCompound(root,"Metadata");name=NbtReader.string(meta,"Name",filename);
            Map<String,Object> regionTags=NbtReader.compound(root.get("Regions"),"Regions");
            if(regionTags.isEmpty()||regionTags.size()>BlueprintMetadata.MAX_REGIONS)throw new IOException("Invalid region count");
            long cumulativeVolume=0;
            for(String regionName:new TreeSet<>(regionTags.keySet())){
                cancel.check();Map<String,Object> tag=NbtReader.compound(regionTags.get(regionName),regionName);
                Vec3i position=vector(tag.get("Position"),"Position"),signedSize=vector(tag.get("Size"),"Size");
                Vec3i size=new Vec3i(positive(signedSize.x()),positive(signedSize.y()),positive(signedSize.z()));
                Vec3i min=position.add(new Vec3i(signedSize.x()<0?signedSize.x()+1:0,signedSize.y()<0?signedSize.y()+1:0,signedSize.z()<0?signedSize.z()+1:0));
                Region region=new Region(regionName,min,size,position);int volume=volume(region);
                cumulativeVolume+=volume;if(cumulativeVolume>MAX_CELLS)throw new IOException("Total imported region volume exceeds limit");
                if(!(tag.get("BlockStatePalette") instanceof List<?> palette)||palette.isEmpty()||palette.size()>65536)throw new IOException("Invalid litematic palette");
                int[] remap=new int[palette.size()];
                for(int i=0;i<remap.length;i++){
                    Map<String,Object> state=NbtReader.compound(palette.get(i),"BlockStatePalette");
                    if((i&255)==0)cancel.check();var read=SourceVersions.readState(state,dataVersion);
                    if(read.skipped())skipped.add(NbtReader.string(state,"Name",""));
                    remap[i]=register(global,read.state());
                }
                int bits=Math.max(2,32-Integer.numberOfLeadingZeros(remap.length-1));
                if(!(tag.get("BlockStates") instanceof long[] words))throw new IOException("Missing litematic BlockStates");
                if(words.length!=PackedBits.wordCount(bits,volume))throw new IOException("Litematic packed length mismatch");
                regions.add(new RegionData(region,PackedBits.takeOwnership(bits,volume,words),remap));
            }
        }else{
            Map<String,Object> schematic=root.containsKey("Schematic")?NbtReader.compound(root.get("Schematic"),"Schematic"):root;
            int format=NbtReader.integer(schematic,"Version");if(format!=2&&format!=3)throw new IOException("Only Sponge .schem v2/v3 and .litematic v5/v6 are supported");
            formatVersion=0;
            dataVersion=NbtReader.integer(schematic,"DataVersion");
            name=NbtReader.string(optionalCompound(schematic,"Metadata"),"Name",filename);
            Vec3i size=new Vec3i(unsignedShort(schematic,"Width"),unsignedShort(schematic,"Height"),unsignedShort(schematic,"Length"));
            int[] offset={0,0,0};if(schematic.containsKey("Offset")){
                if(!(schematic.get("Offset") instanceof int[] a)||a.length!=3)throw new IOException("Offset must have three integers");offset=a;
            }
            Region region=new Region("main",new Vec3i(offset[0],offset[1],offset[2]),size);int volume=volume(region);
            Map<String,Object> blocks=format==3?NbtReader.compound(schematic.get("Blocks"),"Blocks"):schematic;
            Map<String,Object> palette=NbtReader.compound(blocks.get("Palette"),"Palette");
            if(palette.isEmpty()||palette.size()>65536)throw new IOException("Invalid Sponge palette size");
            Map<Integer,Integer> remap=new HashMap<>();
            for(String state:new TreeSet<>(palette.keySet())){
                int external=NbtReader.integer(palette,state);if(external<0)throw new IOException("Negative Sponge palette id");
                var spec=BlockStateSpec.parse(state);var read=SourceVersions.readState(spec,dataVersion);if(read.skipped())skipped.add(spec.name());
                if(remap.put(external,register(global,read.state()))!=null)throw new IOException("Duplicate Sponge palette id");
            }
            Object raw=blocks.get(format==3?"Data":"BlockData");if(!(raw instanceof byte[] bytes))throw new IOException("Missing Sponge block data");
            VarInts decoder=new VarInts(bytes);int bits=Math.max(1,32-Integer.numberOfLeadingZeros(global.size()-1));PackedBits packed;
            try{packed=PackedBits.generate(bits,volume,i->{
                try{if((i&4095)==0)cancel.check();int external=decoder.next();Integer id=remap.get(external);if(id==null)throw new IOException("Sponge index outside palette");return id;}
                catch(IOException e){throw new UncheckedIOException(e);}
            });}catch(UncheckedIOException e){throw e.getCause();}
            if(!decoder.finished())throw new IOException("Trailing Sponge block data");
            int[] identity=new int[global.size()];for(int i=0;i<identity.length;i++)identity[i]=i;regions.add(new RegionData(region,packed,identity));
        }
        long totalVolume=0;
        for(int i=0;i<regions.size();i++){
            totalVolume=Math.addExact(totalVolume,regions.get(i).region.volume());
            if(totalVolume>MAX_CELLS)throw new IOException("Import exceeds 512 Mi cells");
        }
        var compatibility=SourceVersions.compatibleLoading()?new ProjectionCompatibility(formatVersion,dataVersion,SourceVersions.gameVersion(),skipped.size()):ProjectionCompatibility.NONE;
        BlueprintMetadata metadata=new BlueprintMetadata(name,SourceVersions.version(dataVersion),digest,new ArrayList<>(global.keySet()),regions.stream().map(RegionData::region).toList(),warnings,compatibility);
        return new Parsed(metadata,List.copyOf(regions));
    }
    private static int register(LinkedHashMap<BlockStateSpec,Integer> global,BlockStateSpec spec)throws IOException{
        Integer id=global.get(spec);if(id!=null)return id;if(global.size()>=65536)throw new IOException("Global palette limit exceeded");int result=global.size();global.put(spec,result);return result;
    }
    private static int positive(int n)throws IOException{long v=Math.abs((long)n);if(v<1||v>1_048_576)throw new IOException("Invalid region dimension");return (int)v;}
    private static int volume(Region r)throws IOException{long n=r.volume();if(n>MAX_CELLS)throw new IOException("Region exceeds 512 Mi cells");return (int)n;}
    private static int unsignedShort(Map<String,Object> tag,String key)throws IOException{
        Object value=tag.get(key);if(!(value instanceof Short n))throw new IOException("Expected unsigned-short dimension: "+key);int v=n&65535;if(v==0)throw new IOException("Zero dimension");return v;
    }
    private static Vec3i vector(Object value,String field)throws IOException{Map<String,Object>tag=NbtReader.compound(value,field);return new Vec3i(NbtReader.integer(tag,"x"),NbtReader.integer(tag,"y"),NbtReader.integer(tag,"z"));}
    private static Map<String,Object> optionalCompound(Map<String,Object> tag,String field)throws IOException{return tag.containsKey(field)?NbtReader.compound(tag.get(field),field):Map.of();}
    private static final class VarInts {
        private final byte[] data;private int offset;
        VarInts(byte[] data){this.data=data;}
        int next()throws IOException{
            int result=0;
            for(int i=0;i<5;i++){
                if(offset==data.length)throw new EOFException("Truncated Sponge varint");int b=data[offset++]&255;
                if(i==4&&(b&0xf8)!=0)throw new IOException("Sponge varint exceeds positive signed int range");
                result|=(b&127)<<(7*i);if((b&128)==0)return result;
            }
            throw new IOException("Sponge varint too long");
        }
        boolean finished(){return offset==data.length;}
    }
}
