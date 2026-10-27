package dev.betterlitematica.selftest;

import dev.betterlitematica.core.*;
import dev.betterlitematica.io.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.awt.image.BufferedImage;
import javax.imageio.ImageIO;

/** Headless regression against an external large source; never modifies the source. */
public final class LargePreviewCheck {
    public static void main(String[] args) throws Exception {
        Path source=Path.of(args[0]), output=Path.of(args[1]);
        Files.createDirectories(output);
        String before=SchematicImporter.sha256(source,Cancellation.NEVER);
        try {
            NbtReader.read(source,new NbtReader.Limits(512L<<20,512L<<20,64,4_000_000),Cancellation.NEVER);
            throw new AssertionError("Fixture must reproduce the old reader budget failure");
        } catch(IOException expected) {
            if(!expected.getMessage().contains("budget exceeded"))throw expected;
            System.out.println("Original reader reproduces: "+expected.getMessage());
        }
        try {
            PreviewSourceReader.read(source,()->true);
            throw new AssertionError("Cancelled read succeeded");
        } catch(InterruptedIOException expected) { System.out.println("Cancellation OK"); }
        // A hostile declared length must be rejected before allocating the array.
        Path oversized=output.resolve("oversized-array.nbt");
        try {
            try(var out=new DataOutputStream(Files.newOutputStream(oversized))) {
                out.writeByte(10);out.writeUTF("");out.writeByte(12);out.writeUTF("BlockStates");
                out.writeInt((int)(PreviewSourceReader.MAX_BYTES/8+1));
            }
            try {
                PreviewSourceReader.read(oversized,Cancellation.NEVER);
                throw new AssertionError("Oversized array accepted");
            } catch(IOException expected) {
                if(!expected.getMessage().contains("allocation budget exceeded"))throw expected;
                System.out.println("Allocation bound OK");
            }
        } finally { Files.deleteIfExists(oversized); }
        SchematicPreview.Geometry base=geometry(source,false);
        int[] colors=colors(base);
        var images=SchematicPreview.render(base,colors,Cancellation.NEVER);
        if(images.blocks()<=0)throw new AssertionError("Empty preview");
        for(int view=0;view<4;view++)save(images.views().get(view),256,output.resolve("view-"+view+".png"));
        var model=SchematicPreview.createOrbitModel(base,colors,Cancellation.NEVER);
        save(SchematicPreview.renderOrbit(model,.7,.4,false,Cancellation.NEVER),256,output.resolve("orbit.png"));
        System.out.println("Base views and orbit OK; size="+base.size()+", visible blocks="+base.blocks());
        var fine=geometry(source,true);
        if(!base.size().equals(fine.size())||base.blocks()!=fine.blocks()||!base.digest().equals(fine.digest()))
            throw new AssertionError("Fine preview changed source geometry");
        var fineModel=SchematicPreview.createOrbitModel(fine,colors(fine),Cancellation.NEVER);
        save(SchematicPreview.renderOrbit(fineModel,.7,.4,false,Cancellation.NEVER),256,output.resolve("fine-orbit.png"));
        if(!before.equals(SchematicImporter.sha256(source,Cancellation.NEVER)))throw new AssertionError("Source changed");
        System.out.println("Fine model OK; original SHA-256 unchanged: "+before);
    }
    private static SchematicPreview.Geometry geometry(Path source,boolean fine)throws Exception {
        long start=System.nanoTime();
        var root=PreviewSourceReader.read(source,Cancellation.NEVER);
        var result=fine?SchematicPreview.readGeometry(root,Map.of(),SchematicPreview.DETAIL_LOD,Cancellation.NEVER)
            :SchematicPreview.readGeometry(root,Cancellation.NEVER);
        System.out.printf(Locale.ROOT,"%s read + geometry: %.2f s%n",fine?"Fine":"Base",(System.nanoTime()-start)/1e9);
        return result;
    }
    private static int[] colors(SchematicPreview.Geometry g) {
        // Diagnostic colors only: the game resolves real block map colors on its main thread.
        return g.palette().stream().mapToInt(s->0xff000000|(s.name().hashCode()&0xffffff)).toArray();
    }
    private static void save(int[] pixels,int side,Path path)throws Exception {
        if(Arrays.stream(pixels).noneMatch(p->(p>>>24)!=0))throw new AssertionError("Blank image");
        var image=new BufferedImage(side,side,BufferedImage.TYPE_INT_ARGB);
        image.setRGB(0,0,side,side,pixels,0,side);ImageIO.write(image,"png",path.toFile());
    }
}
