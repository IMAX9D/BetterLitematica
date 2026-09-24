package dev.betterlitematica.runtime;

import dev.betterlitematica.core.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;

/** Immutable source snapshots plus placement settings. Versions never overwrite the original blueprint. */
public final class ProjectVersions {
    public record Version(String id,String name){}
    private ProjectVersions(){}
    private static Path directory(Path schematics,String project)throws IOException{
        if(!project.matches("[\\p{L}\\p{N}_ -]{1,80}"))throw new IOException("项目名只能包含文字、数字、空格、下划线、短横线");
        Path root=schematics.toRealPath();Path base=root.resolve("projects");Files.createDirectories(base);if(!base.toRealPath().startsWith(root))throw new IOException("项目目录越界");
        Path directory=base.resolve(project);Files.createDirectories(directory);if(!directory.toRealPath().startsWith(root))throw new IOException("项目目录越界");return directory;
    }
    public static String save(Path schematics,String project,Placement placement,LayerRange layer,float opacity)throws IOException{
        return save(schematics,project,placement,layer,opacity,true);
    }
    public static String save(Path schematics,String project,Placement placement,LayerRange layer,float opacity,boolean rendering)throws IOException{
        return save(schematics,project,placement,layer,opacity,rendering,schematics.resolve(placement.source()),schematics);
    }
    public static String save(Path schematics,String project,Placement placement,LayerRange layer,float opacity,boolean rendering,Path sourceFile,Path allowedRoot)throws IOException{
        Path root=schematics.toRealPath(),source=new TemporarySources.Reference(sourceFile,allowedRoot,false).read();if(Files.size(source)>512L<<20)throw new IOException("源文件超过快照预算");
        Path directory=directory(root,project);String version=System.currentTimeMillis()+"-"+UUID.randomUUID().toString().substring(0,8);
        String filename=source.getFileName().toString();String extension=filename.substring(filename.lastIndexOf('.'));Path target=directory.resolve(version+extension),settings=directory.resolve(version+".blps");
        long size=Files.size(source);var modified=Files.getLastModifiedTime(source);boolean committed=false,created=false;
        if(Files.exists(settings))throw new IOException("Snapshot settings already exist");
        try{
            try(InputStream in=Files.newInputStream(source);OutputStream out=Files.newOutputStream(target,StandardOpenOption.CREATE_NEW)){
                created=true;
                byte[] buffer=new byte[65536];int n;while((n=in.read(buffer))!=-1){Cancellation.THREAD.check();out.write(buffer,0,n);}
            }
            if(Files.size(source)!=size||!Files.getLastModifiedTime(source).equals(modified))throw new IOException("源文件在快照期间发生变化");
            Placement saved=placement.duplicate().source(root.relativize(target).toString().replace('\\','/')).locked(placement.locked());
            PlacementStore.write(settings,new PlacementSession(List.of(saved),saved.id(),layer,opacity,rendering));committed=true;return version;
        }finally{if(!committed&&created){Files.deleteIfExists(target);}}
    }
    public static List<String> list(Path root,String project)throws IOException{
        Path directory=directory(root,project);List<String> result=new ArrayList<>();try(var files=Files.newDirectoryStream(directory,"*.blps")){for(Path file:files){if(result.size()>=512)throw new IOException("项目版本超过 512 个，请拆分项目");result.add(file.getFileName().toString().replace(".blps",""));}}result.sort(Comparator.reverseOrder());return List.copyOf(result);
    }
    public static PlacementSession load(Path root,String project,String version)throws IOException{
        Path file=versionFile(root,project,version);if(!Files.exists(file))throw new FileNotFoundException("Version not found");var session=PlacementStore.read(file);if(session.placements().size()!=1)throw new IOException("项目版本必须包含一个摆放");for(var p:session.placements()){Path source=root.resolve(p.source()).toRealPath();if(!source.startsWith(root.toRealPath())||!Files.isRegularFile(source))throw new IOException("项目源文件越界或缺失");}return session;
    }
    public static List<String> projects(Path root)throws IOException{Path base=root.toRealPath().resolve("projects");if(!Files.isDirectory(base))return List.of();var result=new ArrayList<String>();try(var directories=Files.newDirectoryStream(base)){for(Path path:directories){if(!Files.isDirectory(path)||Files.isSymbolicLink(path))continue;if(result.size()>=512)throw new IOException("项目超过 512 个");result.add(path.getFileName().toString());}}result.sort(String.CASE_INSENSITIVE_ORDER);return List.copyOf(result);}
    public static List<Version> details(Path root,String project)throws IOException{var result=new ArrayList<Version>();for(String id:list(root,project)){Path label=versionFile(root,project,id).resolveSibling(id+".label");String name=id;if(Files.isRegularFile(label)&&!Files.isSymbolicLink(label)){if(Files.size(label)>1024)throw new IOException("版本名称超限");name=Files.readString(label,java.nio.charset.StandardCharsets.UTF_8);}result.add(new Version(id,name));}return List.copyOf(result);}
    public static void rename(Path root,String project,String version,String name)throws IOException{if(name==null||name.isBlank()||name.length()>120||name.chars().anyMatch(Character::isISOControl))throw new IOException("无效版本名称");Path settings=versionFile(root,project,version);if(!Files.isRegularFile(settings))throw new FileNotFoundException("版本已不存在");Path label=settings.resolveSibling(version+".label");if(Files.isSymbolicLink(label))throw new IOException("版本名称文件越界");Path temp=Files.createTempFile(settings.getParent(),"label-",".part");try{Files.writeString(temp,name,java.nio.charset.StandardCharsets.UTF_8);Files.move(temp,label,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}finally{Files.deleteIfExists(temp);}}
    public static void delete(Path root,String project,String version)throws IOException{Path settings=versionFile(root,project,version);Files.move(settings,settings.resolveSibling(version+".blps.deleted"));}
    private static Path versionFile(Path root,String project,String version)throws IOException{if(version==null||!version.matches("[0-9]+-[a-f0-9]{8}"))throw new IOException("Invalid version identity");Path file=directory(root,project).resolve(version+".blps");if(Files.isSymbolicLink(file)||Files.exists(file)&&!file.toRealPath().startsWith(root.toRealPath()))throw new IOException("项目版本越界");return file;}
}
