package dev.betterlitematica.selftest;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.function.IntUnaryOperator;
import java.util.zip.*;
/** Synthetic, independent test fixtures, not user builds. This is not a general schematic exporter. */
public final class Fixtures {
    private Fixtures(){}
    public static Map<String,Object> xyz(int x,int y,int z){return new LinkedHashMap<>(Map.of("x",x,"y",y,"z",z));}
    public static Map<String,Object> state(String name){return new LinkedHashMap<>(Map.of("Name",name));}
    public static List<Object> palette(){return List.of(state("minecraft:air"),state("minecraft:stone"),state("minecraft:glass"),new LinkedHashMap<>(Map.of("Name","minecraft:oak_stairs","Properties",Map.of("facing","east","half","bottom","shape","straight","waterlogged","false"))));}
    public static Map<String,Object> region(int x,int y,int z,int sx,int sy,int sz,IntUnaryOperator block){
        int n=Math.abs(sx*sy*sz);long[] words=new long[(n*2+63)/64];
        // Independent oracle: emit one bit at a time rather than using production PackedBits.
        for(int i=0;i<n;i++){int id=block.applyAsInt(i);for(int bit=0;bit<2;bit++)if((id&(1<<bit))!=0){int offset=i*2+bit;words[offset/64]|=1L<<(offset%64);}}
        Map<String,Object> r=new LinkedHashMap<>();r.put("Position",xyz(x,y,z));r.put("Size",xyz(sx,sy,sz));r.put("BlockStatePalette",palette());r.put("BlockStates",words);return r;
    }
    public static Map<String,Object> litematic(Map<String,Object> regions,String name){
        Map<String,Object> root=new LinkedHashMap<>();root.put("Version",6);root.put("SubVersion",1);root.put("MinecraftDataVersion",3465);root.put("Metadata",Map.of("Name",name,"Author","BetterLitematica synthetic test fixture"));root.put("Regions",regions);return root;
    }
    public static Map<String,Object> sponge(int version,int sx,int sy,int sz,IntUnaryOperator blocks,int[] paletteIds)throws IOException{
        String[] names={"minecraft:air","minecraft:stone","minecraft:glass","minecraft:oak_stairs[facing=east,half=bottom,shape=straight,waterlogged=false]"};
        Map<String,Object> palette=new LinkedHashMap<>();for(int i=0;i<names.length;i++)palette.put(names[i],paletteIds[i]);
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();
        for(int i=0;i<sx*sy*sz;i++){int v=paletteIds[blocks.applyAsInt(i)];while((v&~127)!=0){bytes.write((v&127)|128);v>>>=7;}bytes.write(v);}
        Map<String,Object> schematic=new LinkedHashMap<>();schematic.put("Version",version);schematic.put("DataVersion",3465);
        schematic.put("Width",(short)sx);schematic.put("Height",(short)sy);schematic.put("Length",(short)sz);schematic.put("Offset",new int[]{-3,2,5});schematic.put("Metadata",Map.of("Name","Sponge fixture"));
        if(version==3){schematic.put("Blocks",new LinkedHashMap<>(Map.of("Palette",palette,"Data",bytes.toByteArray())));return new LinkedHashMap<>(Map.of("Schematic",schematic));}
        schematic.put("Palette",palette);schematic.put("PaletteMax",Arrays.stream(paletteIds).max().orElse(0)+1);schematic.put("BlockData",bytes.toByteArray());return schematic;
    }
    public static void write(Path path,Map<String,Object> root)throws IOException{
        Files.createDirectories(path.toAbsolutePath().getParent());
        try(DataOutputStream out=new DataOutputStream(new GZIPOutputStream(Files.newOutputStream(path)))){out.writeByte(10);out.writeUTF("");payload(out,root);}
    }
    private static int type(Object v){
        if(v instanceof Byte)return 1;if(v instanceof Short)return 2;if(v instanceof Integer)return 3;if(v instanceof Long)return 4;
        if(v instanceof Float)return 5;if(v instanceof Double)return 6;if(v instanceof byte[])return 7;if(v instanceof String)return 8;
        if(v instanceof List<?>)return 9;if(v instanceof Map<?,?>)return 10;if(v instanceof int[])return 11;if(v instanceof long[])return 12;throw new IllegalArgumentException("Unsupported fixture tag");
    }
    private static void payload(DataOutputStream out,Object v)throws IOException{
        switch(type(v)){
            case 1->out.writeByte((Byte)v);case 2->out.writeShort((Short)v);case 3->out.writeInt((Integer)v);case 4->out.writeLong((Long)v);
            case 5->out.writeFloat((Float)v);case 6->out.writeDouble((Double)v);case 7->{byte[] a=(byte[])v;out.writeInt(a.length);out.write(a);}
            case 8->out.writeUTF((String)v);
            case 9->{List<?> list=(List<?>)v;out.writeByte(list.isEmpty()?0:type(list.get(0)));out.writeInt(list.size());for(Object a:list)payload(out,a);}
            case 10->{for(var e:((Map<?,?>)v).entrySet()){out.writeByte(type(e.getValue()));out.writeUTF((String)e.getKey());payload(out,e.getValue());}out.writeByte(0);}
            case 11->{int[] a=(int[])v;out.writeInt(a.length);for(int x:a)out.writeInt(x);}
            case 12->{long[] a=(long[])v;out.writeInt(a.length);for(long x:a)out.writeLong(x);}
            default->throw new IOException("Unknown fixture type");
        }
    }
    public static int demoBlock(int i,int sx,int sz,int sy){
        int x=i%sx,z=(i/sx)%sz,y=i/(sx*sz);
        if(y==0 || y==sy-1 && (x%4==0||z%4==0))return 1;
        if((x==0||x==sx-1||z==0||z==sz-1)&&y<sy-2)return (x+z)%7==0?1:2;
        if(z==sz/2&&x>2&&x<sx-3&&y==(x-3)%Math.max(1,sy-3))return 3;
        return 0;
    }
    public static void main(String[] args)throws Exception{
        Path dir=Path.of(args.length==0?"examples":args[0]);int sx=48,sy=24,sz=48;
        IntUnaryOperator block=i->demoBlock(i,sx,sz,sy);
        write(dir.resolve("demo.litematic"),litematic(Map.of("main",region(0,0,0,sx,sy,sz,block)),"BetterLitematica demo"));
        write(dir.resolve("demo.schem"),sponge(3,sx,sy,sz,block,new int[]{7,42,300,999}));
        System.out.println("Generated synthetic demo.litematic and demo.schem in "+dir.toAbsolutePath());
    }
}
