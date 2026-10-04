package dev.betterlitematica.io;

import dev.betterlitematica.core.Cancellation;
import java.io.*;
import java.nio.file.*;
import java.nio.file.attribute.*;
import java.util.*;
import java.util.zip.*;

/** Discardable, CRC-checked render models. Separate from lossless schematic data. */
public final class OrbitPreviewCache {
    public static final long MAX_BYTES=64L<<20;
    public static final int MAX_FILES=16;
    private static final int MAGIC=0x424f5242,MAX_PAYLOAD=20<<20;
    private final Path directory;
    public OrbitPreviewCache(Path directory){this.directory=Objects.requireNonNull(directory).toAbsolutePath().normalize();}
    private Path file(String sha){if(sha==null||!sha.matches("[a-fA-F0-9]{64}"))throw new IllegalArgumentException("Invalid source SHA-256");return directory.resolve("v"+SchematicPreview.VERSION+"-"+sha.toLowerCase(Locale.ROOT)+".blpo");}
    public synchronized SchematicPreview.OrbitModel read(String sha,Cancellation cancel)throws IOException{
        cancel.check();Path path=file(sha);if(!Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS)||Files.size(path)>MAX_PAYLOAD)return null;
        try(var raw=Files.newInputStream(path);var zip=new GZIPInputStream(raw,65536);
            var in=new DataInputStream(new BufferedInputStream(new LimitedInput(zip,cancel),65536))){
            if(in.readInt()!=MAGIC||in.readInt()!=SchematicPreview.VERSION||!in.readUTF().equalsIgnoreCase(sha))return null;
            var model=SchematicPreview.readOrbitModel(in,cancel);
            if(in.read()!=-1)return null; // Also validates the gzip CRC/trailer.
            cancel.check();try{Files.setLastModifiedTime(path,FileTime.fromMillis(System.currentTimeMillis()));}catch(IOException ignored){}
            return model;
        }catch(InterruptedIOException cancelled){throw cancelled;}catch(IOException|IllegalArgumentException malformed){return null;}
    }
    public synchronized void write(String sha,SchematicPreview.OrbitModel model,Cancellation cancel)throws IOException{
        Objects.requireNonNull(model);cancel.check();Path target=file(sha);Files.createDirectories(directory);Path temp=Files.createTempFile(directory,"orbit-",".part");
        try{
            try(var raw=Files.newOutputStream(temp);var zip=new GZIPOutputStream(raw,65536);var out=new DataOutputStream(new BufferedOutputStream(zip,65536))){
                out.writeInt(MAGIC);out.writeInt(SchematicPreview.VERSION);out.writeUTF(sha.toLowerCase(Locale.ROOT));SchematicPreview.writeOrbitModel(model,out,cancel);
            }
            long size=Files.size(temp);if(size>MAX_PAYLOAD)throw new IOException("Orbit cache entry exceeds budget");
            prune(target,size,cancel);cancel.check();Files.move(temp,target,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
        }finally{Files.deleteIfExists(temp);}
    }
    private record Entry(Path path,long size,long modified){}
    private void prune(Path replacement,long incoming,Cancellation cancel)throws IOException{
        var newest=new PriorityQueue<Entry>(Comparator.comparingLong(Entry::modified));long bytes=0;int seen=0;
        try(var paths=Files.newDirectoryStream(directory)){
            for(var path:paths){if((seen++&31)==0)cancel.check();String name=path.getFileName().toString();if(path.equals(replacement)||!name.matches("v[0-9]+-[a-f0-9]{64}\\.blpo"))continue;
                var attr=Files.readAttributes(path,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);if(!attr.isRegularFile())continue;
                if(!name.startsWith("v"+SchematicPreview.VERSION+"-")||attr.size()>MAX_PAYLOAD){Files.deleteIfExists(path);continue;}
                var entry=new Entry(path,attr.size(),attr.lastModifiedTime().toMillis());newest.add(entry);bytes+=entry.size();
                while(newest.size()>=MAX_FILES||bytes>MAX_BYTES-incoming){var old=newest.remove();Files.deleteIfExists(old.path());bytes-=old.size();}
            }
        }
    }
    private static final class LimitedInput extends FilterInputStream {
        private final Cancellation cancel;private long read;
        LimitedInput(InputStream in,Cancellation cancel){super(in);this.cancel=cancel;}
        private void count(long n)throws IOException{cancel.check();if(n>0&&(read+=n)>MAX_PAYLOAD)throw new IOException("Orbit cache decompression budget exceeded");}
        @Override public int read()throws IOException{int value=in.read();count(value<0?0:1);return value;}
        @Override public int read(byte[] bytes,int offset,int length)throws IOException{int count=in.read(bytes,offset,length);count(count);return count;}
    }
}
