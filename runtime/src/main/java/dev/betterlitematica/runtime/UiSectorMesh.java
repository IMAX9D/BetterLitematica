package dev.betterlitematica.runtime;

/** Immutable, tintable annular-sector quads with coverage at their physical-pixel edges. */
public final class UiSectorMesh {
    private static final double TAU = Math.PI * 2;
    private static final double HALF_PIXEL = .5;
    private static final double MAX_RADIUS = 16384;
    private static final double CURVE_ERROR = .03;
    private static final UiSectorMesh EMPTY = new UiSectorMesh(new float[0], 0);
    private final float[] vertices;
    private final double extent;

    private UiSectorMesh(float[] vertices, double extent) {
        this.vertices = vertices;
        this.extent = extent;
    }

    public int vertexCount() { return vertices.length / 3; }
    public float x(int vertex) { return vertices[vertex * 3]; }
    public float y(int vertex) { return vertices[vertex * 3 + 1]; }
    public float coverage(int vertex) { return vertices[vertex * 3 + 2]; }
    public double extent() { return extent; }
    public long bytes() { return (long) vertices.length * Float.BYTES; }
    public int color(int vertex, int tint) {
        return (tint & 0xffffff) | (Math.round((tint >>> 24) * coverage(vertex)) << 24);
    }

    /** Render-thread-owned cache: position, theme and hover alpha do not rebuild geometry. */
    public static final class Cache implements AutoCloseable {
        private record Key(double inner, double outer, double start, double sweep) {}
        private final WeightedLru<Key, UiSectorMesh> meshes =
                new WeightedLru<>(4L << 20, 128, UiSectorMesh::bytes, ignored -> {});

        public UiSectorMesh get(double inner, double outer, double start, double end) {
            double sweep = end - start;
            if (!Double.isFinite(inner) || !Double.isFinite(outer) || !Double.isFinite(start)
                    || !Double.isFinite(sweep) || inner < 0 || outer <= inner
                    || outer > MAX_RADIUS || sweep <= 0) return EMPTY;
            sweep = Math.min(TAU, sweep);
            start = sweep >= TAU - 1e-9 ? 0 : ((start % TAU) + TAU) % TAU;
            if (sweep >= TAU - 1e-9) sweep = TAU;
            var key = new Key(inner, outer, start, sweep);
            var mesh = meshes.get(key);
            if (mesh == null) {
                mesh = create(inner, outer, start, sweep);
                meshes.put(key, mesh);
            }
            return mesh;
        }

        public long usedBytes() { return meshes.usedBytes(); }
        public int size() { return meshes.size(); }
        @Override public void close() { meshes.close(); }
    }

    private static UiSectorMesh create(double inner, double outer, double start, double sweep) {
        boolean full = sweep == TAU;
        double[] radii;
        double[] radialCoverage;
        if (inner == 0) {
            radii = new double[]{0, Math.max(0, outer - HALF_PIXEL), outer + HALF_PIXEL};
            radialCoverage = new double[]{1, 1, 0};
        } else {
            double peak = Math.min(1, outer - inner);
            // A subpixel stroke has a lower, wider plateau, not two overlapping fringes.
            radii = new double[]{Math.max(0, inner - HALF_PIXEL),
                    Math.min(inner + HALF_PIXEL, outer - HALF_PIXEL),
                    Math.max(inner + HALF_PIXEL, outer - HALF_PIXEL), outer + HALF_PIXEL};
            radialCoverage = new double[]{0, peak, peak, 0};
            radii[1] = Math.max(radii[0], radii[1]);
        }
        double maxAngle = Math.min(Math.PI / 8,
                2 * Math.acos(1 - CURVE_ERROR / (outer + HALF_PIXEL)));
        int steps = Math.max(1, Math.min(4096, (int) Math.ceil(sweep / maxAngle)));
        int columns = steps + (full ? 1 : 3);
        float[][] rows = new float[radii.length][columns * 3];
        for (int row = 0; row < radii.length; row++) {
            double radius = radii[row];
            if (full) {
                for (int col = 0; col <= steps; col++) {
                    // Reuse the exact first direction at 100%; no faint seam at the join.
                    double angle = col == steps ? 0 : TAU * col / steps;
                    point(rows[row], col, radius, angle, radialCoverage[row]);
                }
            } else {
                double fringe = Math.asin(Math.min(1, HALF_PIXEL / Math.max(HALF_PIXEL, radius)));
                // Nearly complete rings must not double-blend opposite cap fringes in the gap.
                if (sweep > Math.PI) fringe = Math.min(fringe, (TAU - sweep) / 2);
                double first = start + Math.min(fringe, sweep - fringe);
                double last = start + Math.max(fringe, sweep - fringe);
                double capCoverage = radius == 0 ? 1 : Math.min(1, radius * sweep);
                point(rows[row], 0, radius, start - fringe, 0);
                for (int col = 0; col <= steps; col++) {
                    double angle = first + (last - first) * col / steps;
                    point(rows[row], col + 1, radius, angle, radialCoverage[row] * capCoverage);
                }
                point(rows[row], columns - 1, radius, start + sweep + fringe, 0);
            }
        }
        float[] vertices = new float[(radii.length - 1) * (columns - 1) * 12];
        int cursor = 0;
        for (int row = 0; row + 1 < radii.length; row++) {
            for (int col = 0; col + 1 < columns; col++) {
                cursor = copy(rows[row], col, vertices, cursor);
                cursor = copy(rows[row + 1], col, vertices, cursor);
                cursor = copy(rows[row + 1], col + 1, vertices, cursor);
                cursor = copy(rows[row], col + 1, vertices, cursor);
            }
        }
        return new UiSectorMesh(vertices, outer + HALF_PIXEL);
    }

    private static void point(float[] row, int column, double radius, double angle, double alpha) {
        int offset = column * 3;
        row[offset] = (float) (radius * Math.cos(angle));
        row[offset + 1] = (float) (radius * Math.sin(angle));
        row[offset + 2] = (float) alpha;
    }

    private static int copy(float[] row, int column, float[] target, int offset) {
        System.arraycopy(row, column * 3, target, offset, 3);
        return offset + 3;
    }
}
