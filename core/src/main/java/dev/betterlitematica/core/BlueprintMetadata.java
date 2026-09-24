package dev.betterlitematica.core;
import java.util.*;
public record BlueprintMetadata(String name, int dataVersion, String sourceSha256,
        List<BlockStateSpec> palette, List<Region> regions, List<String> warnings) {
    public BlueprintMetadata {
        Objects.requireNonNull(name); Objects.requireNonNull(sourceSha256);
        palette=List.copyOf(palette); regions=List.copyOf(regions); warnings=List.copyOf(warnings);
        if(palette.isEmpty() || !palette.get(0).equals(BlockStateSpec.AIR))throw new IllegalArgumentException("Palette zero must be canonical air");
        if(palette.size()>65_536||regions.isEmpty()||regions.size()>1024||warnings.size()>1024)throw new IllegalArgumentException("Metadata limits exceeded");
        if(!sourceSha256.matches("[a-f0-9]{64}"))throw new IllegalArgumentException("Invalid source SHA-256");
    }
}
