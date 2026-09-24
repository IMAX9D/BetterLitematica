package dev.betterlitematica.io;

import dev.betterlitematica.core.*;
import java.util.*;

/** Complete captured-region envelope. Call after ownership has moved from the capture task to IO. */
public final class LitematicExport {
    public record Capture(Region region, List<BlockStateSpec> palette, int[] blocks,
                          List<Map<String,Object>> blockEntities, List<Map<String,Object>> entities,
                          List<Map<String,Object>> blockTicks, List<Map<String,Object>> fluidTicks) {}
    private LitematicExport() {}
    public static Map<String,Object> create(String name, String author, int dataVersion, Vec3i origin, List<Capture> captures) {
        if (captures.isEmpty() || captures.size() > 128) throw new IllegalArgumentException("Invalid capture region count");
        Map<String,Object> regions = new LinkedHashMap<>(); long volume = 0, nonAir = 0;
        Vec3i minimum = null, maximum = null;
        for (Capture capture : captures) {
            Region region = capture.region(); if (capture.blocks().length != region.volume()) throw new IllegalArgumentException("Capture volume mismatch");
            volume = Math.addExact(volume, region.volume()); if (volume > 16_777_216) throw new IllegalArgumentException("Capture exceeds 16 Mi cells");
            int bits = Math.max(2, 32 - Integer.numberOfLeadingZeros(capture.palette().size() - 1));
            List<Map<String,Object>> palette = new ArrayList<>();
            for (BlockStateSpec state : capture.palette()) { Map<String,Object> tag = new LinkedHashMap<>(); tag.put("Name", state.name()); if (!state.properties().isEmpty()) tag.put("Properties", state.properties()); palette.add(tag); }
            for (int id : capture.blocks()) { if (id < 0 || id >= capture.palette().size()) throw new IllegalArgumentException("Capture palette index"); if (!capture.palette().get(id).isAir()) nonAir++; }
            Map<String,Object> tag = new LinkedHashMap<>();
            tag.put("Position", vector(region.min().subtract(origin))); tag.put("Size", vector(region.size())); tag.put("BlockStatePalette", palette);
            tag.put("BlockStates", PackedBits.pack(bits, capture.blocks()).copyWords()); tag.put("TileEntities", capture.blockEntities()); tag.put("Entities", capture.entities());
            tag.put("PendingBlockTicks", capture.blockTicks()); tag.put("PendingFluidTicks", capture.fluidTicks());
            if (regions.put(region.name(), tag) != null) throw new IllegalArgumentException("Duplicate region name");
            Vec3i end = region.min().add(region.size());
            minimum = minimum == null ? region.min() : new Vec3i(Math.min(minimum.x(), region.min().x()), Math.min(minimum.y(), region.min().y()), Math.min(minimum.z(), region.min().z()));
            maximum = maximum == null ? end : new Vec3i(Math.max(maximum.x(), end.x()), Math.max(maximum.y(), end.y()), Math.max(maximum.z(), end.z()));
        }
        long now = System.currentTimeMillis(); Map<String,Object> metadata = new LinkedHashMap<>();
        metadata.put("Name", name); metadata.put("Author", author); metadata.put("Description", "Captured by BetterLitematica"); metadata.put("RegionCount", captures.size());
        metadata.put("TotalVolume", Math.toIntExact(volume)); metadata.put("TotalBlocks", Math.toIntExact(nonAir)); metadata.put("EnclosingSize", vector(maximum.subtract(minimum)));
        metadata.put("TimeCreated", now); metadata.put("TimeModified", now);
        return new LinkedHashMap<>(Map.of("Version", 6, "SubVersion", 1, "MinecraftDataVersion", dataVersion, "Metadata", metadata, "Regions", regions));
    }
    public static Map<String,Object> vector(Vec3i v) { return Map.of("x", v.x(), "y", v.y(), "z", v.z()); }
}
