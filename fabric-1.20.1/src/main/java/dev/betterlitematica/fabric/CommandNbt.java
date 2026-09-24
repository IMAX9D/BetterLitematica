package dev.betterlitematica.fabric;
import java.util.*;
import dev.betterlitematica.core.*;
import net.minecraft.nbt.*;

/** Vanilla command decomposition; never truncates NBT or edits a pre-existing block after a failed keep. */
final class CommandNbt {
    private static final class Lines extends ArrayList<String>{int characters;}
    private CommandNbt(){}
    static List<String> block(Vec3i at,String state,NbtCompound tag,ReplaceRule rule,int limit,String storage){
        if(Thread.currentThread().isInterrupted())throw new java.util.concurrent.CancellationException();
        String pos=at.x()+" "+at.y()+" "+at.z(),mode=rule==ReplaceRule.NONE?" keep":" replace";
        String base="setblock "+pos+" "+state,whole=base+(tag==null?"":tag)+mode;
        if(whole.length()<=limit)return List.of(whole);
        if(tag==null||tag.isEmpty())throw new IllegalArgumentException("方块状态超过命令长度，请导出 mcfunction");
        var lines=new Lines();boolean keep=rule==ReplaceRule.NONE;
        add(lines,"data modify storage "+storage+" n set value {}",limit);
        String prefix="data modify storage "+storage+" ";
        for(String key:new TreeSet<>(tag.getKeys()))write(lines,prefix,"n."+NbtString.escape(key),tag.get(key),limit,0);
        add(lines,keep?"execute store success storage "+storage+" ok byte 1 run "+base+mode:base+mode,limit);
        add(lines,(keep?"execute if data storage "+storage+" {ok:1b} run ":"")+"data modify block "+pos+" {} merge from storage "+storage+" n",limit);
        add(lines,"data remove storage "+storage+" n",limit);
        if(keep)add(lines,"data remove storage "+storage+" ok",limit);
        return List.copyOf(lines);
    }
    private static void write(List<String> lines,String prefix,String path,NbtElement value,int limit,int depth){
        if(Thread.currentThread().isInterrupted())throw new java.util.concurrent.CancellationException();
        if(depth>32)throw new IllegalArgumentException("方块数据嵌套过深");String command=prefix+path+" set value "+value;
        if(command.length()<=limit){add(lines,command,limit);return;}
        if(value instanceof NbtCompound compound){add(lines,prefix+path+" set value {}",limit);for(String key:new TreeSet<>(compound.getKeys()))write(lines,prefix,path+"."+NbtString.escape(key),compound.get(key),limit,depth+1);return;}
        if(value instanceof AbstractNbtList<?> list){add(lines,prefix+path+" set value "+empty(value),limit);for(int i=0;i<list.size();i++){NbtElement child=list.get(i);String append=prefix+path+" append value "+child;if(append.length()<=limit)add(lines,append,limit);else if(child instanceof NbtCompound||child instanceof AbstractNbtList<?>){add(lines,prefix+path+" append value "+empty(child),limit);write(lines,prefix,path+"["+i+"]",child,limit,depth+1);}else throw new IllegalArgumentException("数据文本超过命令长度，请导出 mcfunction");}return;}
        throw new IllegalArgumentException("数据文本超过命令长度，请导出 mcfunction");
    }
    private static String empty(NbtElement value){return value instanceof NbtCompound?"{}":value instanceof NbtByteArray?"[B;]":value instanceof NbtIntArray?"[I;]":value instanceof NbtLongArray?"[L;]":"[]";}
    private static void add(List<String> lines,String line,int limit){if(line.length()>limit)throw new IllegalArgumentException("数据路径超过命令长度，请导出 mcfunction");if(lines.size()>=4096||((Lines)lines).characters+line.length()>1_048_576)throw new IllegalArgumentException("单个方块数据超过命令预算");((Lines)lines).characters+=line.length();lines.add(line);}
}
