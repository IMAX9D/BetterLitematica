package dev.betterlitematica.runtime;

import dev.betterlitematica.core.Cancellation;
import java.io.*;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;

/** Exact-byte import, including embedded previews; publishing never replaces another schematic. */
public final class SchematicFileImport {
    private SchematicFileImport(){}
    public static String copy(Path requested,Path directory)throws IOException {
        Path source=requested.toRealPath();
        String name=source.getFileName().toString();int dot=name.lastIndexOf('.');
        if(dot<1||!Set.of("litematic","schem","schematic","nbt").contains(name.substring(dot+1).toLowerCase(Locale.ROOT)))
            throw new IOException("请选择投影文件");
        var before=Files.readAttributes(source,BasicFileAttributes.class);
        if(!before.isRegularFile()||before.size()==0)throw new IOException("投影文件为空或无法读取");
        Files.createDirectories(directory);Path root=directory.toRealPath();
        if(source.getParent().equals(root))return name;
        Path temporary=Files.createTempFile(root,"import-",".part");
        try {
            try(var in=Files.newInputStream(source);var out=Files.newOutputStream(temporary)){
                byte[] buffer=new byte[65536];int count;
                while((count=in.read(buffer))!=-1){Cancellation.THREAD.check();out.write(buffer,0,count);}
            }
            var after=Files.readAttributes(source,BasicFileAttributes.class);
            if(before.size()!=after.size()||!before.lastModifiedTime().equals(after.lastModifiedTime())||!Objects.equals(before.fileKey(),after.fileKey()))
                throw new IOException("复制时源文件发生变化，请重试");
            for(int suffix=0;suffix<10000;suffix++){
                Cancellation.THREAD.check();
                String targetName=suffix==0?name:name.substring(0,dot)+" ("+suffix+")"+name.substring(dot);
                Path target=root.resolve(targetName);
                try{Files.move(temporary,target);return targetName;}
                catch(FileAlreadyExistsException exists){/* Try a new name, preserving every existing file. */}
            }
            throw new IOException("同名投影过多，请先重命名文件");
        }finally{Files.deleteIfExists(temporary);}
    }
}
