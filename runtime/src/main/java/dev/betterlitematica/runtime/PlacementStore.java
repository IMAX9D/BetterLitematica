package dev.betterlitematica.runtime;

import dev.betterlitematica.core.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.CRC32;

/** Small versioned settings file. Never stores or edits source blueprint data. Call from an IO worker. */
public final class PlacementStore {
    private static final int MAGIC = 0x424c5053, VERSION = 3, MAX_BYTES = 8 * 1024 * 1024;
    private PlacementStore() {}
    public static PlacementSession read(Path file) throws IOException {
        if (!Files.exists(file)) return PlacementSession.EMPTY;
        if (Files.size(file) > MAX_BYTES) throw new IOException("Placement settings exceed limit");
        byte[] bytes;
        try (InputStream input = Files.newInputStream(file)) { bytes = input.readNBytes(MAX_BYTES + 1); }
        return decode(bytes);
    }
    public static PlacementSession decode(byte[] bytes) throws IOException {
        if (bytes.length < 12 || bytes.length > MAX_BYTES) throw new IOException("Invalid placement settings size");
        CRC32 crc = new CRC32(); crc.update(bytes, 0, bytes.length - 8);
        try (var in = new DataInputStream(new ByteArrayInputStream(bytes))) {
            int magic=in.readInt(),version=in.readInt();
            if (magic != MAGIC || version < 1 || version > VERSION) throw new IOException("Unsupported placement settings format");
            UUID selected = in.readBoolean() ? uuid(in) : null;
            int axis = in.readUnsignedByte();
            if (axis >= LayerRange.Axis.values().length) throw new IOException("Invalid layer axis");
            LayerRange layer = new LayerRange(LayerRange.Axis.values()[axis], in.readInt(), in.readInt());
            float opacity = in.readFloat(); boolean rendering = in.readBoolean(); int count = in.readInt();
            if (count < 0 || count > PlacementSession.MAX_PLACEMENTS) throw new IOException("Invalid placement count");
            List<Placement> entries = new ArrayList<>();
            for (int i = 0; i < count; i++) {
                UUID id = uuid(in); String name = in.readUTF(), source = in.readUTF();
                var position = new Vec3i(in.readInt(), in.readInt(), in.readInt());
                int turns = in.readUnsignedByte();
                if (turns > 3) throw new IOException("Invalid rotation");
                var transform = new PlacementTransform(position, turns, in.readBoolean(), in.readBoolean());
                boolean enabled=in.readBoolean(),locked=in.readBoolean();float alpha=version>=2?in.readFloat():opacity;
                boolean render=true;int axes=0;ReplaceRule overlap=ReplaceRule.ALL;Map<String,RegionPlacement> regions=new LinkedHashMap<>();
                if(version>=3){render=in.readBoolean();axes=in.readUnsignedByte();int mode=in.readUnsignedByte();if(mode>=ReplaceRule.values().length)throw new IOException("Invalid overlap rule");overlap=ReplaceRule.values()[mode];int n=in.readInt();if(n<0||n>1024)throw new IOException("Invalid region override count");for(int j=0;j<n;j++){String key=in.readUTF();var pos=new Vec3i(in.readInt(),in.readInt(),in.readInt());int rotation=in.readUnsignedByte();if(rotation>3)throw new IOException("Invalid region rotation");var value=new RegionPlacement(pos,rotation,in.readBoolean(),in.readBoolean(),in.readBoolean(),in.readBoolean());if(regions.put(key,value)!=null)throw new IOException("Duplicate region override");}}
                entries.add(new Placement(id,name,source,transform,enabled,locked,alpha,regions,render,axes,overlap));
            }
            if (in.readLong() != crc.getValue() || in.read() != -1) throw new IOException("Placement settings checksum mismatch");
            return new PlacementSession(entries, selected, layer, opacity, rendering);
        } catch (IllegalArgumentException e) { throw new IOException("Invalid placement settings", e); }
    }
    public static void write(Path file, PlacementSession session) throws IOException {
        byte[] bytes=encode(session);
        Path absolute = file.toAbsolutePath(); Files.createDirectories(absolute.getParent());
        Path temporary = Files.createTempFile(absolute.getParent(), "placements-", ".part");
        try {
            Files.write(temporary, bytes);
            Files.move(temporary, absolute, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally { Files.deleteIfExists(temporary); }
    }
    public static byte[] encode(PlacementSession session) throws IOException {
        var bytes = new ByteArrayOutputStream();
        try (var out = new DataOutputStream(bytes)) {
            out.writeInt(MAGIC); out.writeInt(VERSION); out.writeBoolean(session.selected() != null);
            if (session.selected() != null) uuid(out, session.selected());
            out.writeByte(session.layer().axis().ordinal()); out.writeInt(session.layer().min()); out.writeInt(session.layer().max());
            out.writeFloat(session.opacity()); out.writeBoolean(session.rendering()); out.writeInt(session.placements().size());
            for (Placement entry : session.placements()) {
                uuid(out, entry.id()); out.writeUTF(entry.name()); out.writeUTF(entry.source());
                Vec3i p = entry.transform().origin(); out.writeInt(p.x()); out.writeInt(p.y()); out.writeInt(p.z());
                out.writeByte(entry.transform().quarterTurns()); out.writeBoolean(entry.transform().mirrorX()); out.writeBoolean(entry.transform().mirrorZ());
                out.writeBoolean(entry.enabled()); out.writeBoolean(entry.locked()); out.writeFloat(entry.opacity());
                out.writeBoolean(entry.renderBlocks());out.writeByte(entry.lockedAxes());out.writeByte(entry.overlapRule().ordinal());out.writeInt(entry.regions().size());
                for(var override:entry.regions().entrySet()){out.writeUTF(override.getKey());var r=override.getValue();out.writeInt(r.position().x());out.writeInt(r.position().y());out.writeInt(r.position().z());out.writeByte(r.quarterTurns());out.writeBoolean(r.mirrorX());out.writeBoolean(r.mirrorZ());out.writeBoolean(r.enabled());out.writeBoolean(r.locked());}
            }
            CRC32 crc = new CRC32(); crc.update(bytes.toByteArray()); out.writeLong(crc.getValue());
        }
        if (bytes.size() > MAX_BYTES) throw new IOException("Placement settings exceed limit");
        return bytes.toByteArray();
    }
    private static UUID uuid(DataInput in) throws IOException { return new UUID(in.readLong(), in.readLong()); }
    private static void uuid(DataOutput out, UUID id) throws IOException { out.writeLong(id.getMostSignificantBits()); out.writeLong(id.getLeastSignificantBits()); }
}
