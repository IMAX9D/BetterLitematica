package dev.betterlitematica.fabric;

import dev.betterlitematica.core.Cancellation;
import dev.betterlitematica.io.NbtReader;
import dev.betterlitematica.io.SchematicPreview;
import java.io.*;
import java.util.*;
import java.util.zip.*;

/** Optional render data in Metadata; the standard litematic regions and version are untouched. */
final class EmbeddedPreviewCache {
    private static final String FIELD="BetterLitematicaPreview",ORBIT="Orbit";
    private static final int MAX_BYTES=20<<20;
    record Cached(SchematicPreview.Images images,SchematicPreview.OrbitModel model){}
    private EmbeddedPreviewCache(){}

    static Cached read(Map<String,Object> root,Cancellation cancel)throws IOException{
        var images=SchematicPreview.embedded(root,cancel);if(images==null)return null;
        SchematicPreview.OrbitModel model=null;
        try{
            var metadata=metadata(root);var saved=NbtReader.compound(metadata.get(FIELD),FIELD);
            if(saved.get(ORBIT) instanceof Map<?,?>){
                var orbit=NbtReader.compound(saved.get(ORBIT),ORBIT);
                if(NbtReader.integer(orbit,"Version")==SchematicPreview.VERSION&&orbit.get("Data") instanceof byte[] bytes&&bytes.length>0&&bytes.length<=MAX_BYTES){
                    int length=NbtReader.integer(orbit,"Length");
                    if(length>0&&length<=MAX_BYTES&&orbit.get("CRC") instanceof Long crc&&crc==checksum(bytes)){
                        try(var zip=new GZIPInputStream(new ByteArrayInputStream(bytes),65536);var limited=new LimitedInput(zip,length,cancel);var in=new DataInputStream(limited)){
                            var decoded=SchematicPreview.readOrbitModel(in,cancel);
                            if(in.read()==-1&&limited.count==length&&matches(decoded,NbtReader.string(saved,"Geometry",""),images))model=decoded;
                        }
                    }
                }
            }
        }catch(InterruptedIOException cancelled){throw cancelled;}
        catch(IOException|IllegalArgumentException|ArithmeticException malformed){/* Discard only the optional model. */}
        cancel.check();return new Cached(images,model);
    }

    static boolean matches(SchematicPreview.OrbitModel model,String digest,SchematicPreview.Images images){
        return model!=null&&model.digest().equals(digest)&&(images==null||model.size().equals(images.size())&&model.blocks()==images.blocks());
    }

    /** Publish metadata only after encoding and validation finish, including cancellation checks. */
    static void write(Map<String,Object> root,Cached cached,boolean preserveEmptyThumbnail,Cancellation cancel)throws IOException{
        Objects.requireNonNull(cached);Objects.requireNonNull(cached.model());cancel.check();
        var copy=new LinkedHashMap<>(root);SchematicPreview.embed(copy,cached.images(),cancel);
        var metadata=metadata(copy);var saved=NbtReader.compound(metadata.get(FIELD),FIELD);
        if(!matches(cached.model(),NbtReader.string(saved,"Geometry",""),cached.images()))throw new IOException("预览缓存与投影不一致，请重新生成");
        var bytes=new BoundedBytes();
        try(var zip=new GZIPOutputStream(bytes,65536);var bounded=new LimitedOutput(zip,cancel);var out=new DataOutputStream(bounded)){
            SchematicPreview.writeOrbitModel(cached.model(),out,cancel);out.flush();
            var orbit=new LinkedHashMap<String,Object>();orbit.put("Version",SchematicPreview.VERSION);orbit.put("Length",bounded.count);saved.put(ORBIT,orbit);
        }
        byte[] payload=bytes.toByteArray();var orbit=NbtReader.compound(saved.get(ORBIT),ORBIT);orbit.put("Data",payload);orbit.put("CRC",checksum(payload));saved.put(ORBIT,orbit);metadata.put(FIELD,saved);
        Object original=metadata(root).get("PreviewImageData");
        if(original instanceof int[] pixels&&(pixels.length>0||preserveEmptyThumbnail)&&!generatedThumbnail(root,pixels))metadata.put("PreviewImageData",pixels.clone());
        cancel.check();publishMetadata(root,metadata);
    }

    static void remove(Map<String,Object> root)throws IOException{var metadata=metadata(root);metadata.remove(FIELD);publishMetadata(root,metadata);}
    private static boolean generatedThumbnail(Map<String,Object> root,int[] pixels)throws IOException{
        Object previous=metadata(root).get(FIELD);
        if(!(previous instanceof Map<?,?> envelope)||!(envelope.get("Views") instanceof List<?> views)||views.size()!=4||!(views.get(3) instanceof int[] isometric))return false;
        return Arrays.equals(pixels,isometric);
    }
    private static Map<String,Object> target(Map<String,Object> root)throws IOException{return root.containsKey("Schematic")?NbtReader.compound(root.get("Schematic"),"Schematic"):root;}
    private static Map<String,Object> metadata(Map<String,Object> root)throws IOException{var target=target(root);return target.containsKey("Metadata")?NbtReader.compound(target.get("Metadata"),"Metadata"):new LinkedHashMap<>();}
    private static void publishMetadata(Map<String,Object> root,Map<String,Object> metadata)throws IOException{
        if(root.containsKey("Schematic")){var nested=target(root);nested.put("Metadata",metadata);root.put("Schematic",nested);}else root.put("Metadata",metadata);
    }
    private static long checksum(byte[] bytes){var crc=new CRC32();crc.update(bytes);return crc.getValue();}
    private static final class BoundedBytes extends ByteArrayOutputStream {
        BoundedBytes(){super(65536);}
        private void reserve(int n){if(n>MAX_BYTES-count)throw new IllegalArgumentException("预览缓存超过保存上限");}
        @Override public synchronized void write(int b){reserve(1);super.write(b);}
        @Override public synchronized void write(byte[] b,int off,int len){reserve(len);super.write(b,off,len);}
    }
    private static final class LimitedOutput extends FilterOutputStream {
        private final Cancellation cancel;private int count;
        LimitedOutput(OutputStream out,Cancellation cancel){super(out);this.cancel=cancel;}
        private void add(int n)throws IOException{cancel.check();if(n>MAX_BYTES-count)throw new IOException("预览缓存超过保存上限");count+=n;}
        @Override public void write(int b)throws IOException{add(1);out.write(b);}
        @Override public void write(byte[] b,int off,int len)throws IOException{add(len);out.write(b,off,len);}
    }
    private static final class LimitedInput extends FilterInputStream {
        private final int limit;private final Cancellation cancel;private int count;
        LimitedInput(InputStream in,int limit,Cancellation cancel){super(in);this.limit=limit;this.cancel=cancel;}
        private void add(int n)throws IOException{cancel.check();if(n>0){if(n>limit-count)throw new IOException("预览缓存解压超过上限");count+=n;}}
        @Override public int read()throws IOException{int value=in.read();add(value<0?0:1);return value;}
        @Override public int read(byte[] b,int off,int len)throws IOException{int n=in.read(b,off,len);add(n);return n;}
    }
}
