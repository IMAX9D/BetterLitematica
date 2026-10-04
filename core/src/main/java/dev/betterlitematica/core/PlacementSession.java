package dev.betterlitematica.core;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Immutable per-world/per-dimension settings. Persistence enforces a byte budget. Render layers apply to every placement. */
public record PlacementSession(List<Placement> placements, UUID selected, LayerRange layer,
                               float opacity, boolean rendering) {
    public static final PlacementSession EMPTY = new PlacementSession(List.of(), null, LayerRange.ALL, 0.45f, true);
    public PlacementSession {
        placements = List.copyOf(placements); Objects.requireNonNull(layer);
        if (!Float.isFinite(opacity) || opacity < 0.05f || opacity > 1f) throw new IllegalArgumentException("Opacity must be between 0.05 and 1");
        var ids = new HashSet<UUID>();
        for (Placement entry : placements) if (!ids.add(entry.id())) throw new IllegalArgumentException("Duplicate placement identity");
        if (selected != null && !ids.contains(selected)) throw new IllegalArgumentException("Selected placement is missing");
    }
}
