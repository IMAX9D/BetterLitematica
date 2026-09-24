package dev.betterlitematica.runtime;

import dev.betterlitematica.core.AreaSelection;
import dev.betterlitematica.io.SelectionStore;
import java.io.*;
import java.nio.file.*;
import java.util.*;

/** Named selection definitions; active edits never mutate a stored template. */
public final class SelectionLibrary {
    private SelectionLibrary(){}
    private static Path file(Path directory,String name)throws IOException{
        if(name==null||!name.matches("[\\p{L}\\p{N}_ .-]{1,100}")||name.equals(".")||name.equals("..")||name.endsWith(".")||name.endsWith(" "))throw new IOException("无效名称");
        Path root=directory.toAbsolutePath().normalize();Files.createDirectories(root);Path result=root.resolve(name+".selection.nbt");
        if(Files.isSymbolicLink(result)||Files.exists(result)&&!result.toRealPath().startsWith(root.toRealPath()))throw new IOException("选区文件越界");return result;
    }
    public static List<String> list(Path directory)throws IOException{
        if(!Files.isDirectory(directory))return List.of();var names=new ArrayList<String>();try(var files=Files.newDirectoryStream(directory,"*.selection.nbt")){for(Path path:files){if(!Files.isRegularFile(path)||Files.isSymbolicLink(path))continue;if(names.size()>=512)throw new IOException("选区库超过 512 项");String name=path.getFileName().toString();names.add(name.substring(0,name.length()-14));}}names.sort(String.CASE_INSENSITIVE_ORDER);return List.copyOf(names);
    }
    public static AreaSelection load(Path directory,String name)throws IOException{Path path=file(directory,name);if(!Files.isRegularFile(path))throw new FileNotFoundException("选区已不存在");return SelectionStore.read(path);}
    public static void save(Path directory,String name,AreaSelection value)throws IOException{SelectionStore.writeNew(file(directory,name),value);}
    public static void rename(Path directory,String name,String next)throws IOException{Path source=file(directory,name),target=file(directory,next);if(source.equals(target))return;Files.move(source,target);}
    public static void delete(Path directory,String name)throws IOException{Files.delete(file(directory,name));}
}
