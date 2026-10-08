package dev.betterlitematica.selftest;

import dev.betterlitematica.runtime.UiGlyphArt;
import java.util.Arrays;
import java.util.HashSet;

/** Glyph rasters are white coverage only, bounded, distinct and centred inside their square. */
public final class UiGlyphChecks {
    private static int checks;
    private UiGlyphChecks() {}
    public static int run(){
        checks=0;
        for(int size:new int[]{8,12,16,20,24,32,48,128}){
            var seen=new HashSet<String>();
            for(var kind:UiGlyphArt.Kind.values()){
                var raster=UiGlyphArt.raster(kind,size);
                check(raster.size()==size&&raster.argb().length==size*size&&raster.bytes()==4L*size*size,"Glyph raster is exactly its requested physical size");
                int ink=0,solid=0;boolean white=true;double cx=0,cy=0,total=0;
                for(int y=0;y<size;y++)for(int x=0;x<size;x++){int p=raster.argb()[x+y*size],a=p>>>24;white&=(p&0xffffff)==0xffffff;if(a>160)solid++;if(a>0){ink++;cx+=x*a;cy+=y*a;total+=a;}}
                check(white,"Glyph colour comes only from the draw-time tint");
                check(ink>size,"Glyph is visible at "+size+"px: "+kind);
                check(solid<size*size*.5,"Glyph is a line icon, not a filled square: "+kind);
                if(size>=16){cx/=total;cy/=total;check(Math.abs(cx-(size-1)/2d)<size*.2&&Math.abs(cy-(size-1)/2d)<size*.2,"Glyph is optically centred: "+kind);}
                seen.add(Arrays.toString(raster.argb()));
            }
            check(seen.size()>=UiGlyphArt.Kind.values().length-1,"Glyphs are distinguishable from each other");
        }
        for(int size:new int[]{-1,0,7,129}){
            try{UiGlyphArt.raster(UiGlyphArt.Kind.BRAND,size);throw new AssertionError("Unsupported glyph size accepted");}catch(IllegalArgumentException expected){checks++;}
        }
        try{UiGlyphArt.raster(null,16);throw new AssertionError("Null glyph accepted");}catch(NullPointerException expected){checks++;}
        return checks;
    }
    private static void check(boolean value,String message){checks++;if(!value)throw new AssertionError(message);}
}
