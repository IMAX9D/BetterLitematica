package dev.betterlitematica.runtime;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Arc2D;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Line2D;
import java.awt.geom.Path2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.util.Objects;

/**
 * Original line-icon set drawn on a 24-unit grid and rasterized at its final physical size.
 * Output is white coverage with straight alpha, tinted at draw time, so one raster serves every theme.
 */
public final class UiGlyphArt {
    public enum Kind {
        BRAND, LOAD, PRINTER, SELECTION, PASTE, TASKS, VERSIONS, SETTINGS, RESTORE,
        EYE, EYE_OFF, TUNE, TRASH, BACK, FOLDER, FILE, SEARCH, CHECK, PLUS, REFRESH, UP, NEXT, COPY,
        LAYERS, MODES, PLAY, LOCK
    }
    public static final int MIN_PIXELS=8,MAX_PIXELS=128,SUPERSAMPLING=4;
    public record Raster(int size,int[] argb){
        public Raster{Objects.requireNonNull(argb);if(size<1||(long)size*size!=argb.length)throw new IllegalArgumentException("Invalid glyph raster");}
        public long bytes(){return (long)size*size*4;}
    }
    private UiGlyphArt(){}

    public static Raster raster(Kind kind,int pixels){
        Objects.requireNonNull(kind,"kind");
        if(pixels<MIN_PIXELS||pixels>MAX_PIXELS)throw new IllegalArgumentException("Glyph size outside 8..128 physical pixels");
        int high=pixels*SUPERSAMPLING;
        var image=new BufferedImage(high,high,BufferedImage.TYPE_INT_ARGB_PRE);var g=image.createGraphics();
        try{
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_RENDERING,RenderingHints.VALUE_RENDER_QUALITY);
            g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL,RenderingHints.VALUE_STROKE_PURE);
            g.scale(high/24d,high/24d);g.setColor(Color.WHITE);
            // Thin strokes read as refined at large sizes; small sizes get a touch more weight to stay crisp.
            float stroke=(float)Math.max(1.45,Math.min(2.1,1.45+(20-pixels)*.04));
            g.setStroke(new BasicStroke(stroke,BasicStroke.CAP_ROUND,BasicStroke.JOIN_ROUND));
            draw(g,kind,stroke);
        }finally{g.dispose();}
        int[] source=((DataBufferInt)image.getRaster().getDataBuffer()).getData();
        int[] out=new int[pixels*pixels];
        for(int y=0;y<pixels;y++)for(int x=0;x<pixels;x++){
            long alpha=0;
            for(int sy=0;sy<SUPERSAMPLING;sy++)for(int sx=0;sx<SUPERSAMPLING;sx++)alpha+=source[(y*SUPERSAMPLING+sy)*high+x*SUPERSAMPLING+sx]>>>24;
            int a=(int)Math.round(alpha/(double)(SUPERSAMPLING*SUPERSAMPLING));
            out[y*pixels+x]=a<<24|0xffffff;
        }
        return new Raster(pixels,out);
    }

    private static void draw(Graphics2D g,Kind kind,float stroke){
        switch(kind){
            case BRAND -> {
                // Isometric block: three faces, the top one solid.
                var top=new Path2D.Double();top.moveTo(12,3.2);top.lineTo(20,7.6);top.lineTo(12,12);top.lineTo(4,7.6);top.closePath();g.fill(top);
                var body=new Path2D.Double();body.moveTo(4,7.6);body.lineTo(4,16.4);body.lineTo(12,20.8);body.lineTo(20,16.4);body.lineTo(20,7.6);g.draw(body);
                g.draw(new Line2D.Double(12,12,12,20.8));
            }
            case LOAD -> {
                folder(g);
                g.draw(new Line2D.Double(12,10.5,12,16.5));g.draw(new Line2D.Double(9.2,13.6,12,16.5));g.draw(new Line2D.Double(14.8,13.6,12,16.5));
            }
            case FOLDER -> folder(g);
            case PRINTER -> {
                g.draw(new RoundRectangle2D.Double(3.5,9,17,8,2.4,2.4));
                var paper=new Path2D.Double();paper.moveTo(7,9);paper.lineTo(7,4);paper.lineTo(17,4);paper.lineTo(17,9);g.draw(paper);
                g.draw(new RoundRectangle2D.Double(7,14,10,6.5,1.2,1.2));
                g.fill(new Ellipse2D.Double(16.6,11.1,1.6,1.6));
            }
            case SELECTION -> {
                // Corner brackets around a solid core.
                double a=4,b=20,l=4.2;
                for(double[] c:new double[][]{{a,a,1,1},{b,a,-1,1},{a,b,1,-1},{b,b,-1,-1}}){
                    var p=new Path2D.Double();p.moveTo(c[0],c[1]+c[3]*l);p.lineTo(c[0],c[1]);p.lineTo(c[0]+c[2]*l,c[1]);g.draw(p);
                }
                g.fill(new RoundRectangle2D.Double(9,9,6,6,1.6,1.6));
            }
            case PASTE -> {
                g.draw(new RoundRectangle2D.Double(5,5.5,14,15,2.6,2.6));
                g.fill(new RoundRectangle2D.Double(8.5,3.2,7,4,1.4,1.4));
                g.draw(new Line2D.Double(8.8,12,15.2,12));g.draw(new Line2D.Double(8.8,15.6,13,15.6));
            }
            case TASKS -> {
                for(int i=0;i<3;i++){double y=6+i*6;g.draw(new Line2D.Double(10.5,y,20,y));}
                var check=new Path2D.Double();check.moveTo(3.6,6);check.lineTo(5.2,7.6);check.lineTo(7.8,4.6);g.draw(check);
                g.fill(new Ellipse2D.Double(4.4,11.2,1.8,1.8));g.fill(new Ellipse2D.Double(4.4,17.2,1.8,1.8));
            }
            case VERSIONS -> {
                var top=new Path2D.Double();top.moveTo(12,3.5);top.lineTo(20.5,8);top.lineTo(12,12.5);top.lineTo(3.5,8);top.closePath();g.draw(top);
                var mid=new Path2D.Double();mid.moveTo(3.5,12.2);mid.lineTo(12,16.7);mid.lineTo(20.5,12.2);g.draw(mid);
                var low=new Path2D.Double();low.moveTo(3.5,16.2);low.lineTo(12,20.7);low.lineTo(20.5,16.2);g.draw(low);
            }
            case SETTINGS, TUNE -> {
                double[][] rows=kind==Kind.SETTINGS?new double[][]{{6,15},{12,8},{18,13}}:new double[][]{{7,9},{17,16}};
                for(double[] r:rows){
                    g.draw(new Line2D.Double(4,r[0],20,r[0]));
                    var knob=new Ellipse2D.Double(r[1]-2.4,r[0]-2.4,4.8,4.8);
                    var old=g.getComposite();g.setComposite(java.awt.AlphaComposite.Clear);g.fill(knob);g.setComposite(old);g.draw(knob);
                }
            }
            case RESTORE -> {
                g.draw(new Arc2D.Double(4,4,16,16,140,-290,Arc2D.OPEN));
                var head=new Path2D.Double();head.moveTo(4.2,4.6);head.lineTo(4.9,9.4);head.lineTo(9.6,8.6);g.draw(head);
                var hand=new Path2D.Double();hand.moveTo(12,8);hand.lineTo(12,12);hand.lineTo(14.8,13.6);g.draw(hand);
            }
            case REFRESH -> {
                g.draw(new Arc2D.Double(4.5,4.5,15,15,70,-300,Arc2D.OPEN));
                var head=new Path2D.Double();head.moveTo(14.6,2.6);head.lineTo(15.3,5.4);head.lineTo(12.4,6.6);g.draw(head);
            }
            case EYE, EYE_OFF -> {
                var eye=new Path2D.Double();eye.moveTo(2.8,12);eye.curveTo(5.5,7.2,8.6,5.6,12,5.6);eye.curveTo(15.4,5.6,18.5,7.2,21.2,12);
                eye.curveTo(18.5,16.8,15.4,18.4,12,18.4);eye.curveTo(8.6,18.4,5.5,16.8,2.8,12);eye.closePath();
                if(kind==Kind.EYE){g.draw(eye);g.fill(new Ellipse2D.Double(9.2,9.2,5.6,5.6));}
                else{
                    var old=g.getClip();var keep=new java.awt.geom.Area(new java.awt.geom.Rectangle2D.Double(0,0,24,24));
                    var gap=new Path2D.Double();gap.moveTo(3,3-1.8);gap.lineTo(21+1.8,21);gap.lineTo(21-1.8,21+1.2);gap.lineTo(3-1.8,3);gap.closePath();
                    keep.subtract(new java.awt.geom.Area(gap));g.setClip(keep);
                    g.draw(eye);g.draw(new Ellipse2D.Double(9.4,9.4,5.2,5.2));g.setClip(old);
                    g.draw(new Line2D.Double(4,4,20,20));
                }
            }
            case TRASH -> {
                g.draw(new Line2D.Double(4,6.5,20,6.5));
                var can=new Path2D.Double();can.moveTo(6,6.5);can.lineTo(7,19.6);can.quadTo(7.2,20.6,8.4,20.6);can.lineTo(15.6,20.6);can.quadTo(16.8,20.6,17,19.6);can.lineTo(18,6.5);g.draw(can);
                var lid=new Path2D.Double();lid.moveTo(9,6.5);lid.lineTo(9.6,3.8);lid.lineTo(14.4,3.8);lid.lineTo(15,6.5);g.draw(lid);
                g.draw(new Line2D.Double(10.4,10.4,10.6,16.6));g.draw(new Line2D.Double(13.6,10.4,13.4,16.6));
            }
            case BACK -> {var p=new Path2D.Double();p.moveTo(14.5,5.5);p.lineTo(8,12);p.lineTo(14.5,18.5);g.draw(p);}
            case COPY -> {
                g.draw(new RoundRectangle2D.Double(8.5,8.5,11.5,11.5,2.6,2.6));
                var back=new Path2D.Double();back.moveTo(5.5,15.5);back.quadTo(4,15.5,4,14);back.lineTo(4,6);back.quadTo(4,4,6,4);back.lineTo(14,4);back.quadTo(15.5,4,15.5,5.5);g.draw(back);
            }
            case NEXT -> {var p=new Path2D.Double();p.moveTo(9.5,5.5);p.lineTo(16,12);p.lineTo(9.5,18.5);g.draw(p);}
            case UP -> {var p=new Path2D.Double();p.moveTo(5.5,14.5);p.lineTo(12,8);p.lineTo(18.5,14.5);g.draw(p);}
            case FILE -> {
                var p=new Path2D.Double();p.moveTo(14,3.5);p.lineTo(6.8,3.5);p.quadTo(5,3.5,5,5.3);p.lineTo(5,18.7);p.quadTo(5,20.5,6.8,20.5);p.lineTo(17.2,20.5);p.quadTo(19,20.5,19,18.7);p.lineTo(19,8.5);p.closePath();g.draw(p);
                var fold=new Path2D.Double();fold.moveTo(14,3.5);fold.lineTo(14,8.5);fold.lineTo(19,8.5);g.draw(fold);
            }
            case SEARCH -> {g.draw(new Ellipse2D.Double(4,4,12.5,12.5));g.draw(new Line2D.Double(14.6,14.6,20,20));}
            case CHECK -> {var p=new Path2D.Double();p.moveTo(5,12.5);p.lineTo(10,17.5);p.lineTo(19.5,7);g.draw(p);}
            case PLUS -> {g.draw(new Line2D.Double(12,5,12,19));g.draw(new Line2D.Double(5,12,19,12));}
            case LAYERS -> {
                // Flat slabs with the current one solid: reads as "one layer of many", unlike the isometric VERSIONS stack.
                g.draw(new RoundRectangle2D.Double(4,4.2,16,3.8,1.6,1.6));
                g.fill(new RoundRectangle2D.Double(4,10.1,16,3.8,1.6,1.6));
                g.draw(new RoundRectangle2D.Double(4,16,16,3.8,1.6,1.6));
            }
            case MODES -> {
                g.draw(new RoundRectangle2D.Double(4,4,6.8,6.8,1.8,1.8));g.draw(new RoundRectangle2D.Double(13.2,4,6.8,6.8,1.8,1.8));
                g.draw(new RoundRectangle2D.Double(4,13.2,6.8,6.8,1.8,1.8));g.fill(new RoundRectangle2D.Double(13.2,13.2,6.8,6.8,1.8,1.8));
            }
            case PLAY -> {var p=new Path2D.Double();p.moveTo(7.6,5);p.lineTo(19,12);p.lineTo(7.6,19);p.closePath();g.draw(p);}
            case LOCK -> {
                g.draw(new RoundRectangle2D.Double(5,10.5,14,10,2.6,2.6));
                var shackle=new Path2D.Double();shackle.moveTo(8.2,10.5);shackle.lineTo(8.2,7.6);shackle.curveTo(8.2,2.6,15.8,2.6,15.8,7.6);shackle.lineTo(15.8,10.5);g.draw(shackle);
                g.fill(new Ellipse2D.Double(11,14.2,2,2));
            }
        }
    }
    private static void folder(Graphics2D g){
        var p=new Path2D.Double();
        p.moveTo(3.5,7);p.quadTo(3.5,5,5.5,5);p.lineTo(9.4,5);p.lineTo(11.4,7.2);p.lineTo(18.5,7.2);p.quadTo(20.5,7.2,20.5,9.2);
        p.lineTo(20.5,17.5);p.quadTo(20.5,19.5,18.5,19.5);p.lineTo(5.5,19.5);p.quadTo(3.5,19.5,3.5,17.5);p.closePath();g.draw(p);
    }
}
