package dev.betterlitematica.selftest;

import dev.betterlitematica.runtime.UiSectorMesh;

/** Geometry, coverage and cache contracts shared by every Minecraft UI adapter. */
public final class UiSectorChecks {
    private static final double TAU = Math.PI * 2;
    private static int checks;
    private static String shapeLabel;
    private UiSectorChecks() {}

    public static int run() {
        checks = 0;
        try (var cache = new UiSectorMesh.Cache()) {
            for (double outer : new double[]{12, 27.5, 133, 320, 1024}) {
                for (double inner : new double[]{0, outer * .4, outer - 1.5, outer - .35}) {
                    for (double sweep : new double[]{TAU, Math.PI / 2 - .04, TAU / 100, TAU * .99}) {
                        shapeLabel = inner + "/" + outer + "/" + sweep;
                        var mesh = cache.get(inner, outer, -.73, -.73 + sweep);
                        check(mesh.vertexCount() > 0 && mesh.vertexCount() % 4 == 0, "Complete quads");
                        boolean finite = true, coverage = true, bounded = true;
                        for (int i = 0; i < mesh.vertexCount(); i++) {
                            finite &= Float.isFinite(mesh.x(i)) && Float.isFinite(mesh.y(i));
                            coverage &= mesh.coverage(i) >= 0 && mesh.coverage(i) <= 1;
                            bounded &= Math.hypot(mesh.x(i), mesh.y(i)) <= mesh.extent() + .001;
                        }
                        check(finite && coverage && bounded, "Finite coverage and conservative GUI bounds");
                        double area = 0;
                        for (int q = 0; q < mesh.vertexCount(); q += 4) {
                            area += triangleArea(mesh, q, q + 1, q + 2);
                            area += triangleArea(mesh, q, q + 2, q + 3);
                        }
                        double expected = .5 * sweep * (outer * outer - inner * inner);
                        // Subpixel caps approximate a box filter; large surfaces must retain their area.
                        check(Math.abs(area - expected) < Math.max(.65, expected * .045),
                                "Coverage preserves area: " + inner + "/" + outer + "/" + sweep + " => " + area + " vs " + expected);
                    }
                }
            }

            for (double[] shape : new double[][]{{0, 27.5, 0, TAU}, {21.5, 25, -Math.PI / 2, TAU},
                    {31, 78, -.71, 1.43}, {26.65, 27, -.71, 1.43}, {9.5, 11, -Math.PI / 2, TAU / 100}}) {
                double inner = shape[0], outer = shape[1], start = shape[2], sweep = shape[3];
                int size = (int) Math.ceil(outer * 2 + 6);
                double cx = outer + 2.31, cy = outer + 2.73;
                float[] raster = raster(cache.get(inner, outer, start, start + sweep), size, size, cx, cy);
                double softError = 0, hardError = 0;
                int partial = 0;
                for (int y = 0; y < size; y++) for (int x = 0; x < size; x++) {
                    float actual = raster[x + y * size];
                    if (actual > 0 && actual < .999) partial++;
                    double reference = 0;
                    for (int sy = 0; sy < 8; sy++) for (int sx = 0; sx < 8; sx++) {
                        if (inside(x + (sx + .5) / 8 - cx, y + (sy + .5) / 8 - cy, inner, outer, start, sweep)) reference += 1d / 64;
                    }
                    softError += Math.abs(actual - reference);
                    hardError += Math.abs((inside(x + .5 - cx, y + .5 - cy, inner, outer, start, sweep) ? 1 : 0) - reference);
                }
                check(partial > 0, "Curved and slanted boundaries have partial coverage");
                check(softError < hardError * .75, "Coverage improves on hard edges: " + softError + " vs " + hardError);
            }

            var ring = cache.get(21.5, 25, -Math.PI / 2, Math.PI * 1.5);
            check(ring == cache.get(21.5, 25, 0, TAU), "A full ring reuses a seamless, rotation-independent mesh");
            float[] pixels = raster(ring, 60, 60, 30.31, 30.73);
            check(pixels[53 + 30 * 60] > .99 && pixels[30 + 7 * 60] > .99, "100 percent has no cap gap");
            check(pixels[30 + 30 * 60] == 0, "The ring does not fill its centre");
            int last = ring.vertexCount() - 1;
            check((ring.color(last, 0x804080c0) & 0xffffff) == 0x4080c0, "Coverage never changes theme RGB");
            for (int i = 0; i < ring.vertexCount(); i++) check((ring.color(i, 0x804080c0) >>> 24) <= 128, "Tint opacity is retained");

            for (double[] invalid : new double[][]{{0,0,0,1},{1,1,0,1},{-1,5,0,1},{0,5,1,1},
                    {0,5,1,0},{0,Double.POSITIVE_INFINITY,0,1},{0,5,Double.NaN,1},{0,20000,0,1}}) {
                check(cache.get(invalid[0],invalid[1],invalid[2],invalid[3]).vertexCount()==0, "Invalid or zero-length shapes draw nothing");
            }
            for (int i = 1; i <= 500; i++) cache.get(9.5, 11, 0, TAU * i / 501);
            check(cache.size() <= 128 && cache.usedBytes() <= (4L << 20), "Animated progress has a bounded cache");
            cache.close();
            check(cache.size() == 0 && cache.usedBytes() == 0, "Resource reload clears cached geometry");
            check(cache.get(2,4,0,TAU).vertexCount()>0, "Geometry can be rebuilt after reload");
        }
        return checks;
    }

    private static double triangleArea(UiSectorMesh mesh, int a, int b, int c) {
        double cross = (mesh.x(b)-mesh.x(a))*(double)(mesh.y(c)-mesh.y(a))
                - (mesh.y(b)-mesh.y(a))*(double)(mesh.x(c)-mesh.x(a));
        check(cross >= -.002, "Consistent winding: " + shapeLabel + " triangle " + a + "/" + b + "/" + c + " cross=" + cross);
        return Math.abs(cross) / 6 * (mesh.coverage(a)+mesh.coverage(b)+mesh.coverage(c));
    }

    private static boolean inside(double x,double y,double inner,double outer,double start,double sweep) {
        double radius=Math.hypot(x,y);
        if(radius<inner||radius>outer)return false;
        if(sweep>=TAU-1e-9)return true;
        double angle=((Math.atan2(y,x)-start)%TAU+TAU)%TAU;
        return angle<=sweep;
    }

    /** CPU reference rasterizer for the exact quads submitted by the game adapters. */
    public static float[] raster(UiSectorMesh mesh,int width,int height,double cx,double cy) {
        float[] pixels=new float[width*height];
        for(int q=0;q<mesh.vertexCount();q+=4){
            triangle(mesh,q,q+1,q+2,pixels,width,height,cx,cy);
            triangle(mesh,q,q+2,q+3,pixels,width,height,cx,cy);
        }
        return pixels;
    }

    private static void triangle(UiSectorMesh mesh,int a,int b,int c,float[] pixels,int width,int height,double cx,double cy) {
        double ax=mesh.x(a)+cx,ay=mesh.y(a)+cy,bx=mesh.x(b)+cx,by=mesh.y(b)+cy,cx1=mesh.x(c)+cx,cy1=mesh.y(c)+cy;
        double denominator=(by-cy1)*(ax-cx1)+(cx1-bx)*(ay-cy1);
        if(Math.abs(denominator)<1e-10)return;
        int left=Math.max(0,(int)Math.floor(Math.min(ax,Math.min(bx,cx1))));
        int right=Math.min(width,(int)Math.ceil(Math.max(ax,Math.max(bx,cx1))));
        int top=Math.max(0,(int)Math.floor(Math.min(ay,Math.min(by,cy1))));
        int bottom=Math.min(height,(int)Math.ceil(Math.max(ay,Math.max(by,cy1))));
        for(int y=top;y<bottom;y++)for(int x=left;x<right;x++){
            double u=((by-cy1)*(x+.5-cx1)+(cx1-bx)*(y+.5-cy1))/denominator;
            double v=((cy1-ay)*(x+.5-cx1)+(ax-cx1)*(y+.5-cy1))/denominator;
            double w=1-u-v;
            if(u>=-1e-7&&v>=-1e-7&&w>=-1e-7){
                float value=(float)(u*mesh.coverage(a)+v*mesh.coverage(b)+w*mesh.coverage(c));
                pixels[x+y*width]=Math.max(pixels[x+y*width],Math.min(1,value));
            }
        }
    }

    private static void check(boolean value,String message){checks++;if(!value)throw new AssertionError(message);}
    public static void main(String[] args){System.out.println("UiSectorChecks: "+run()+" checks passed");}
}
