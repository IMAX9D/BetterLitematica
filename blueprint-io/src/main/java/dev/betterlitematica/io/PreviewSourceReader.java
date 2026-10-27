package dev.betterlitematica.io;

import dev.betterlitematica.core.Cancellation;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/** Shared source budget for initial and detailed previews; auxiliary payloads are streamed past. */
public final class PreviewSourceReader {
    public static final NbtReader.Limits LIMITS = SchematicImporter.SOURCE_LIMITS;
    public static final long MAX_BYTES = LIMITS.maxUncompressedBytes();

    private PreviewSourceReader() {}

    public static Map<String,Object> read(Path source, Cancellation cancel) throws IOException {
        cancel.check();
        if (Files.size(source) > SchematicImporter.MAX_SOURCE_BYTES)
            throw new IOException("投影超过预览读取上限");
        return NbtReader.readPartial(source, LIMITS, cancel, PreviewSourceReader::omitAuxiliary);
    }

    private static boolean omitAuxiliary(List<String> path) {
        if (path.isEmpty() || path.size() == 2 && path.get(0).equals("Regions")) return false;
        return switch (path.get(path.size() - 1)) {
            case "Entities", "entities", "TileEntities", "BlockEntities",
                 "PendingBlockTicks", "PendingFluidTicks" -> true;
            default -> false;
        };
    }
}
