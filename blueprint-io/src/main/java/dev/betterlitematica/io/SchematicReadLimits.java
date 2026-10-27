package dev.betterlitematica.io;

import java.io.*;
import java.nio.file.*;
import java.util.Properties;

/** Source-file budgets only. Generic NBT and retained metadata keep their existing limits. */
public final class SchematicReadLimits {
    public static final int DEFAULT_PERCENT=60;
    private static final System.Logger LOG=System.getLogger("BetterLitematica.SourceLoader");
    private static final int PERCENT=loadPercent();
    private static final long SOURCE_BYTES=bytesForHeap(Runtime.getRuntime().maxMemory(),PERCENT);
    static {LOG.log(System.Logger.Level.INFO,"投影源读取预算：游戏最大堆的 {0}%，上限 {1} MiB（解析分配上限，不是预留内存）。",PERCENT,SOURCE_BYTES>>20);}
    private SchematicReadLimits(){}
    public static long bytesForHeap(long heap){return bytesForHeap(heap,DEFAULT_PERCENT);}
    public static long bytesForHeap(long heap,int percent){
        if(heap<1)throw new IllegalArgumentException("Invalid heap size");
        if(percent<1||percent>90)throw new IllegalArgumentException("Invalid source memory percentage");
        // Divide before multiplying so even extreme heap values cannot overflow.
        return Math.max(1,(heap/100)*percent+(heap%100)*percent/100);
    }
    private static int loadPercent(){
        try{
            Path path=configPath();if(path==null)return DEFAULT_PERCENT;
            Properties p=new Properties();
            if(Files.exists(path)){
                if(Files.size(path)>65536)throw new IOException("配置文件过大");
                try(var reader=Files.newBufferedReader(path)){p.load(reader);}
                int value=Integer.parseInt(p.getProperty("sourceMemoryPercent",Integer.toString(DEFAULT_PERCENT)).trim());
                if(value<1||value>90)throw new IOException("sourceMemoryPercent 必须在 1 至 90 之间");
                return value;
            }
            Files.createDirectories(path.toAbsolutePath().getParent());
            Files.writeString(path,"# Source NBT allocation/decompression ceiling as percent of JVM -Xmx.\n# Not a reservation or a total rendering-memory cap. Restart to apply.\nsourceMemoryPercent=60\n",StandardOpenOption.CREATE_NEW);
        }catch(Exception e){LOG.log(System.Logger.Level.WARNING,"投影读取预算配置未能读取，使用默认 60%：{0}",e.getMessage());}
        return DEFAULT_PERCENT;
    }
    private static Path configPath()throws Exception{
        // Explicit override is useful for standalone importers and tests; never guess their working directory.
        String override=System.getProperty("betterlitematica.sourceConfig");
        if(override!=null&&!override.isBlank())return Path.of(override);
        Class<?> loader;
        try{loader=Class.forName("net.fabricmc.loader.api.FabricLoader");}
        catch(ClassNotFoundException standalone){return null;}
        Object instance=loader.getMethod("getInstance").invoke(null);
        Path directory=(Path)loader.getMethod("getConfigDir").invoke(instance);
        return directory.resolve("betterlitematica").resolve("source-loader.properties");
    }
    public static NbtReader.Limits fullSource(){return source(SOURCE_BYTES);}
    public static NbtReader.Limits source(long allocationBudget){
        if(allocationBudget<1)throw new IllegalArgumentException("Invalid allocation budget");
        return new NbtReader.Limits(SOURCE_BYTES,Math.min(allocationBudget,SOURCE_BYTES),64,4_000_000);
    }
}
