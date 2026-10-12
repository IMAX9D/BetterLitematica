package dev.betterlitematica.fabric;
import net.minecraft.util.math.Box;
/** Per-section conservative bounds; immutable boxes are reused until the section moves. */
public interface TerrainExpandedBounds { Box betterlitematica$expandedBounds(Box original); }
