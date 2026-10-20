package dev.betterlitematica.runtime;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;

/** Stable, explicitly mounted roots. Source identities remain relative in saved placements. */
public final class SchematicFolders {
    private static final String PREFIX="@folder/";
    public record Folder(String id,String name,Path path) {
        public String source(){return id.isEmpty()?"":PREFIX+id;}
    }
    public record Directory(Path root,String relative,String prefix) {
        public Path read()throws IOException {
            Path base=root.toRealPath(),path=base.resolve(relative).normalize().toRealPath();
            if(!path.startsWith(base)||!Files.isDirectory(path))throw new IOException("文件夹不存在或超出挂载范围");
            return path;
        }
    }
    private final Path local,settings;
    private volatile List<Folder> mounted=List.of();
    private boolean writable;
    private volatile String problem="";
    public SchematicFolders(Path local,Path settings){this.local=local.toAbsolutePath().normalize();this.settings=settings;}
    /** Run on the ordered settings worker before any mutations. Invalid settings are preserved. */
    public synchronized void load()throws IOException {
        writable=false;
        try{loadChecked();problem="";}
        catch(IOException|IllegalArgumentException failure){
            problem="投影文件夹配置读取失败，本实例目录仍可使用；请重试或重置挂载配置";
            throw new IOException(problem,failure);
        }
    }
    /** A failed external configuration must not prevent browsing the instance's own files. */
    public void loadSafely(){try{load();}catch(IOException ignored){}}
    public String problem(){return problem;}
    /** Explicit UI recovery: retain the original bytes before replacing any directory links. */
    public synchronized void resetPreservingOriginal()throws IOException {
        if(Files.exists(settings))Files.copy(settings,settings.resolveSibling(settings.getFileName()+".backup-"+UUID.randomUUID()));
        save(List.of());mounted=List.of();writable=true;problem="";
    }
    private void loadChecked()throws IOException {
        var next=new ArrayList<Folder>();
        if(Files.exists(settings)){
            if(Files.size(settings)>262144)throw new IOException("挂载配置过大，原文件已保留");
            var values=new Properties();try(var reader=Files.newBufferedReader(settings,StandardCharsets.UTF_8)){values.load(reader);}
            if(values.size()>64)throw new IOException("挂载文件夹过多");
            try{for(String id:values.stringPropertyNames()){
                if(!UUID.fromString(id).toString().equals(id))throw new IllegalArgumentException();
                Path path=Path.of(values.getProperty(id));if(!path.isAbsolute())throw new IllegalArgumentException();
                next.add(folder(id,path.normalize()));
            }}catch(IllegalArgumentException e){throw new IOException("挂载配置损坏，原文件已保留",e);}
        }
        next.sort(Comparator.comparing(Folder::name).thenComparing(Folder::id));mounted=List.copyOf(next);writable=true;
    }
    private static Folder folder(String id,Path path){return new Folder(id,path.getFileName()==null?path.toString():path.getFileName().toString(),path);}
    public List<Folder> folders(){var all=new ArrayList<Folder>();all.add(new Folder("","本实例投影",local));all.addAll(mounted);return List.copyOf(all);}
    public static boolean external(String source){return source.startsWith(PREFIX);}
    public Directory directory(String source){
        String normalized=Objects.requireNonNull(source).replace('\\','/'),relative=normalized,prefix="";Path root=local;
        if(external(normalized)){
            int slash=normalized.indexOf('/',PREFIX.length());String id=normalized.substring(PREFIX.length(),slash<0?normalized.length():slash);
            Folder folder=mounted.stream().filter(f->f.id().equals(id)).findFirst().orElseThrow(()->new IllegalArgumentException("投影文件夹未挂载，请重新挂载原文件夹"));
            root=folder.path();prefix=folder.source()+"/";relative=slash<0?"":normalized.substring(slash+1);
        }
        if(relative.startsWith("/")||relative.contains(":")||relative.indexOf('\0')>=0)throw new IllegalArgumentException("无效投影路径");
        if(!relative.isEmpty())for(String part:relative.split("/",-1))if(part.isEmpty()||part.equals(".")||part.equals(".."))throw new IllegalArgumentException("投影路径超出文件夹范围");
        return new Directory(root,relative,prefix);
    }
    public TemporarySources.Reference reference(String source){var d=directory(source);return new TemporarySources.Reference(d.root().resolve(d.relative()),d.root(),false);}
    public String label(String source){var d=directory(source);String base=d.prefix().isEmpty()?"本实例投影":mounted.stream().filter(f->(f.source()+"/").equals(d.prefix())).findFirst().orElseThrow().name();return base+(d.relative().isEmpty()?"":" / "+d.relative());}
    public String parent(String source){var d=directory(source);if(d.relative().isEmpty())return source;int slash=d.relative().lastIndexOf('/');return slash<0?(d.prefix().isEmpty()?"":d.prefix().substring(0,d.prefix().length()-1)):d.prefix()+d.relative().substring(0,slash);}
    public CompletableFuture<SessionIo.Listing> list(SessionIo io,String source,String query){
        try{var d=directory(source);var original=io.list(d.root(),d.relative(),query);
            var mapped=original.thenApply(list->new SessionIo.Listing(list.entries().stream().map(f->new SessionIo.FileEntry(d.prefix()+f.path(),f.name(),f.directory())).toList(),list.truncated()));
            mapped.whenComplete((v,e)->{if(mapped.isCancelled())original.cancel(true);});return mapped;
        }catch(RuntimeException e){return CompletableFuture.failedFuture(e);}
    }
    public synchronized Folder mount(Path requested)throws IOException {
        if(!writable)throw new IOException("挂载配置尚未就绪，无法保存");
        Path path=requested.toRealPath();if(!Files.isDirectory(path)||!Files.isReadable(path))throw new IOException("请选择可读取的文件夹");
        if(Files.isSameFile(local,path))return folders().get(0);
        for(var existing:mounted)if(existing.path().equals(path)||Files.exists(existing.path())&&Files.isSameFile(existing.path(),path))return existing;
        if(mounted.size()>=64)throw new IOException("最多挂载 64 个文件夹");
        // Canonical path identity also reconnects saved placements after removing/readding this mount.
        String identity=path.toString();if(File.separatorChar=='\\')identity=identity.toLowerCase(Locale.ROOT);
        var folder=folder(UUID.nameUUIDFromBytes(identity.getBytes(StandardCharsets.UTF_8)).toString(),path);
        var next=new ArrayList<>(mounted);next.add(folder);save(next);mounted=List.copyOf(next);return folder;
    }
    public synchronized void remove(String id)throws IOException {
        if(!writable)throw new IOException("挂载配置不可保存");
        var next=mounted.stream().filter(f->!f.id().equals(id)).toList();save(next);mounted=next;
    }
    private void save(List<Folder> folders)throws IOException {
        Files.createDirectories(settings.getParent());var values=new Properties();for(var folder:folders)values.setProperty(folder.id(),folder.path().toString());
        Path temporary=Files.createTempFile(settings.getParent(),"folders-",".part");
        try{try(var writer=Files.newBufferedWriter(temporary,StandardCharsets.UTF_8)){values.store(writer,"BetterLitematica schematic folders");}
            try{Files.move(temporary,settings,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}catch(AtomicMoveNotSupportedException e){Files.move(temporary,settings,StandardCopyOption.REPLACE_EXISTING);}
        }finally{Files.deleteIfExists(temporary);}
    }
}
