package dev.betterlitematica.io;

import dev.betterlitematica.core.*;
import java.io.*;
import java.nio.file.*;
import java.nio.file.attribute.*;
import java.util.*;

/** Optional local thumbnail cache. Published entries <=128 MiB/512 files; one <=1.01 MiB scratch file.
 * All operations on an instance serialize, including eviction and atomic replacement. */
public final class PreviewCache {
    public static final long MAX_BYTES=128L<<20;
    public static final int MAX_FILES=512;
    private static final int MAGIC=0x42505256,MAX_FILE_BYTES=4*SchematicPreview.SIDE*SchematicPreview.SIDE*4+128;
    private final Path directory;private final long budget;
    public PreviewCache(Path directory){this(directory,MAX_BYTES);}
    public PreviewCache(Path directory,long maxBytes){if(directory==null||maxBytes<MAX_FILE_BYTES||maxBytes>MAX_BYTES)throw new IllegalArgumentException("Invalid preview cache budget");this.directory=directory.toAbsolutePath().normalize();budget=maxBytes;}
    private Path file(String sha){if(sha==null||!sha.matches("[a-fA-F0-9]{64}"))throw new IllegalArgumentException("Invalid source SHA-256");return directory.resolve("v"+SchematicPreview.VERSION+"-"+sha.toLowerCase(Locale.ROOT)+".blpv");}
    public synchronized SchematicPreview.Images read(String sourceSha256,Cancellation cancel)throws IOException{
        cancel.check();Path file=file(sourceSha256);if(!Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS))return null;
        try{
            if(Files.size(file)>MAX_FILE_BYTES)return null;
            try(var in=new DataInputStream(new BufferedInputStream(Files.newInputStream(file)))){
                if(in.readInt()!=MAGIC||in.readInt()!=SchematicPreview.VERSION||!in.readUTF().equalsIgnoreCase(sourceSha256))return null;
                int side=in.readInt();if(side!=SchematicPreview.SIDE)return null;
                var size=new Vec3i(in.readInt(),in.readInt(),in.readInt());long blocks=in.readLong();var views=new ArrayList<int[]>(4);
                for(int i=0;i<4;i++){int[] pixels=new int[side*side];for(int j=0;j<pixels.length;j++){if((j&1023)==0)cancel.check();pixels[j]=in.readInt();}views.add(pixels);}
                var result=new SchematicPreview.Images(side,views,size,blocks);if(in.readLong()!=SchematicPreview.checksum(result)||in.read()!=-1)return null;cancel.check();
                try{Files.setLastModifiedTime(file,FileTime.fromMillis(System.currentTimeMillis()));}catch(IOException ignored){}
                return result;
            }
        }catch(InterruptedIOException cancelled){throw cancelled;}catch(IOException|IllegalArgumentException malformed){return null;}
    }
    public synchronized void write(String sourceSha256,SchematicPreview.Images images,Cancellation cancel)throws IOException{
        Objects.requireNonNull(images);cancel.check();Path destination=file(sourceSha256);Files.createDirectories(directory);Path temporary=Files.createTempFile(directory,"preview-",".part");
        try{
            try(var out=new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(temporary)))){
                out.writeInt(MAGIC);out.writeInt(SchematicPreview.VERSION);out.writeUTF(sourceSha256.toLowerCase(Locale.ROOT));out.writeInt(images.side());out.writeInt(images.size().x());out.writeInt(images.size().y());out.writeInt(images.size().z());out.writeLong(images.blocks());
                for(var view:images.views())for(int i=0;i<view.length;i++){if((i&1023)==0)cancel.check();out.writeInt(view[i]);}out.writeLong(SchematicPreview.checksum(images));
            }
            long size=Files.size(temporary);if(size>MAX_FILE_BYTES||size>budget)throw new IOException("Preview cache entry exceeds budget");
            prune(destination,size,cancel);cancel.check();Files.move(temporary,destination,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
        }finally{Files.deleteIfExists(temporary);}
    }
    private record Entry(Path path,long size,long modified){}
    private void prune(Path replacement,long incoming,Cancellation cancel)throws IOException{
        // At most 511 candidates retained even if an external process populated this directory.
        var newest=new PriorityQueue<Entry>(Comparator.comparingLong(Entry::modified));long bytes=0;int seen=0;
        try(var stream=Files.newDirectoryStream(directory)){
            for(var path:stream){if((seen++&31)==0)cancel.check();String name=path.getFileName().toString();if(path.equals(replacement)||!name.matches("v[0-9]+-[a-f0-9]{64}\\.blpv"))continue;
                var attributes=Files.readAttributes(path,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);if(!attributes.isRegularFile())continue;
                if(!name.startsWith("v"+SchematicPreview.VERSION+"-")||attributes.size()>MAX_FILE_BYTES){Files.deleteIfExists(path);continue;}
                var entry=new Entry(path,attributes.size(),attributes.lastModifiedTime().toMillis());newest.add(entry);bytes+=entry.size();
                while(newest.size()>=MAX_FILES||bytes>budget-incoming){var old=newest.remove();Files.deleteIfExists(old.path());bytes-=old.size();}
            }
        }
    }
}
