package dev.betterlitematica.runtime;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.RadialGradientPaint;
import java.awt.RenderingHints;
import java.awt.geom.Area;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Line2D;
import java.awt.geom.Path2D;
import java.awt.geom.Point2D;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.util.Objects;

/** Original vector badges rasterized for their final physical size; no fonts, files or GPU APIs. */
public final class StatusBadgeArt {
    public enum Kind { EYE_OPEN, EYE_CLOSED, PRINTER_ON, PRINTER_OFF }
    public static final int MIN_PIXELS=12,MAX_PIXELS=192,SUPERSAMPLING=4;
    private record Style(Color eye,Color muted,Color green,Color shadow,Color top,Color bottom,Color border){}
    private static final Style LIGHT=new Style(new Color(79,97,124),new Color(137,147,161),new Color(10,180,99),
        new Color(61,76,101),new Color(251,253,255,229),new Color(241,245,250,221),new Color(129,145,167,58));
    private static final Style DARK=new Style(new Color(236,232,224),new Color(130,134,143),new Color(110,214,156),
        new Color(0,0,0),new Color(38,40,47,238),new Color(24,25,30,238),new Color(255,255,255,30));

    /** Straight-alpha ARGB. The circle occupies [padding, padding + requestedDiameter). */
    public record Raster(int width,int height,int[] argb) {
        public Raster {Objects.requireNonNull(argb);if(width<1||height<1||(long)width*height!=argb.length)throw new IllegalArgumentException("Invalid badge raster");}
        public long bytes(){return (long)width*height*4;}
    }
    private StatusBadgeArt() {}

    /** Padding is outside the nominal circular hit target, solely for its soft shadow. */
    public static int padding(int physicalPixels){
        if(physicalPixels<MIN_PIXELS||physicalPixels>MAX_PIXELS)throw new IllegalArgumentException("Badge diameter outside 12..192 physical pixels");
        return Math.max(2,(physicalPixels+7)/8);
    }

    public static Raster raster(Kind kind,int physicalPixels){return raster(kind,physicalPixels,false);}
    /** Dark badges sit on graphite glass; the vector geometry is identical in both themes. */
    public static Raster raster(Kind kind,int physicalPixels,boolean dark){
        Style style=dark?DARK:LIGHT;
        Objects.requireNonNull(kind,"kind");int pad=padding(physicalPixels),edge=physicalPixels+pad*2,high=edge*SUPERSAMPLING;
        var image=new BufferedImage(high,high,BufferedImage.TYPE_INT_ARGB_PRE);var g=image.createGraphics();
        try{
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_RENDERING,RenderingHints.VALUE_RENDER_QUALITY);
            g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL,RenderingHints.VALUE_STROKE_PURE);
            g.setRenderingHint(RenderingHints.KEY_ALPHA_INTERPOLATION,RenderingHints.VALUE_ALPHA_INTERPOLATION_QUALITY);
            g.scale(SUPERSAMPLING,SUPERSAMPLING);surface(g,physicalPixels,pad,style);
            g.translate(pad,pad);g.scale(physicalPixels,physicalPixels);
            double stroke=Math.max(.78,physicalPixels*.038)/physicalPixels;
            g.setStroke(new BasicStroke((float)stroke,BasicStroke.CAP_ROUND,BasicStroke.JOIN_ROUND));
            switch(kind){
                case EYE_OPEN -> eye(g,stroke,false,style);
                case EYE_CLOSED -> eye(g,stroke,true,style);
                case PRINTER_ON -> printer(g,true,style);
                case PRINTER_OFF -> printer(g,false,style);
            }
        }finally{g.dispose();}
        int[] premultiplied=((DataBufferInt)image.getRaster().getDataBuffer()).getData();
        return new Raster(edge,edge,reduce(premultiplied,edge));
    }

    private static void surface(Graphics2D g,int diameter,int padding,Style style){
        Color sh=style.shadow();
        double center=padding+diameter*.5,drop=diameter*.025;
        float shadowRadius=(float)Math.min(diameter*.56,diameter*.5+padding-1.25-drop);
        g.setPaint(new RadialGradientPaint(new Point2D.Double(center,center+drop),shadowRadius,
            new float[]{0,.78f,.9f,1},new Color[]{new Color(sh.getRed(),sh.getGreen(),sh.getBlue(),dark(style)?44:24),new Color(sh.getRed(),sh.getGreen(),sh.getBlue(),dark(style)?34:21),new Color(sh.getRed(),sh.getGreen(),sh.getBlue(),dark(style)?14:9),new Color(sh.getRed(),sh.getGreen(),sh.getBlue(),0)}));
        g.fill(new Ellipse2D.Double(center-shadowRadius,center+drop-shadowRadius,shadowRadius*2,shadowRadius*2));
        double border=Math.max(.45,diameter*.0075),inset=border*.5;
        var circle=new Ellipse2D.Double(padding+inset,padding+inset,diameter-border,diameter-border);
        g.setPaint(new GradientPaint(0,padding,style.top(),0,padding+diameter,style.bottom()));
        g.fill(circle);
        g.setColor(style.border());g.setStroke(new BasicStroke((float)border));g.draw(circle);
    }

    private static boolean dark(Style style){return style==DARK;}
    private static Path2D eyePath(){
        var path=new Path2D.Double();path.moveTo(.235,.5);
        path.curveTo(.315,.369,.402,.323,.5,.323);path.curveTo(.598,.323,.685,.369,.765,.5);
        path.curveTo(.685,.631,.598,.677,.5,.677);path.curveTo(.402,.677,.315,.631,.235,.5);path.closePath();return path;
    }
    private static void eye(Graphics2D g,double stroke,boolean hidden,Style style){
        g.setColor(hidden?style.muted():style.eye());var path=eyePath();
        if(!hidden){g.draw(path);g.fill(new Ellipse2D.Double(.426,.426,.148,.148));return;}
        var slash=new Line2D.Double(.269,.261,.731,.739);
        // Do not paint over crossing strokes: leave an intentional, background-colored gutter.
        var visible=new Area(new Rectangle2D.Double(0,0,1,1));
        visible.subtract(new Area(new BasicStroke((float)(stroke*2.5),BasicStroke.CAP_ROUND,BasicStroke.JOIN_ROUND).createStrokedShape(slash)));
        var clipped=(Graphics2D)g.create();try{clipped.clip(visible);clipped.draw(path);}finally{clipped.dispose();}
        g.draw(slash);
    }
    private static void printer(Graphics2D g,boolean running,Style style){
        g.setColor(running?style.green():style.muted());
        var paper=new Path2D.Double();paper.moveTo(.35,.398);paper.lineTo(.35,.281);paper.quadTo(.35,.255,.377,.255);
        paper.lineTo(.623,.255);paper.quadTo(.65,.255,.65,.281);paper.lineTo(.65,.398);g.draw(paper);
        var body=new Path2D.Double();body.moveTo(.345,.655);body.lineTo(.315,.655);body.quadTo(.265,.655,.265,.6);
        body.lineTo(.265,.455);body.quadTo(.265,.405,.32,.405);body.lineTo(.68,.405);body.quadTo(.735,.405,.735,.455);
        body.lineTo(.735,.6);body.quadTo(.735,.655,.685,.655);body.lineTo(.655,.655);g.draw(body);
        var output=new Path2D.Double();output.moveTo(.345,.574);output.lineTo(.655,.574);output.lineTo(.655,.747);
        output.quadTo(.655,.773,.63,.773);output.lineTo(.37,.773);output.quadTo(.345,.773,.345,.747);output.closePath();g.draw(output);
        g.fill(new Ellipse2D.Double(.647,.474,.031,.031));
        var previous=g.getStroke();g.setStroke(new BasicStroke(((BasicStroke)previous).getLineWidth()*.57f,BasicStroke.CAP_ROUND,BasicStroke.JOIN_ROUND));
        g.draw(new Line2D.Double(.409,.653,.591,.653));g.draw(new Line2D.Double(.409,.709,.552,.709));g.setStroke(previous);
    }

    /** Box-filter premultiplied coverage before converting to straight ARGB; avoids dark fringes. */
    static int[] reduce(int[] source,int edge){
        int factor=SUPERSAMPLING,high=edge*factor;int[] output=new int[edge*edge];
        if(source.length!=(long)high*high)throw new IllegalArgumentException("Invalid supersampled badge");
        int samples=factor*factor;
        for(int y=0;y<edge;y++)for(int x=0;x<edge;x++){
            int a=0,r=0,g=0,b=0;
            for(int dy=0;dy<factor;dy++)for(int dx=0;dx<factor;dx++){
                int pixel=source[(y*factor+dy)*high+x*factor+dx];a+=pixel>>>24;r+=(pixel>>>16)&255;g+=(pixel>>>8)&255;b+=pixel&255;
            }
            int alpha=(a+samples/2)/samples;if(alpha==0)continue;
            output[x+y*edge]=(alpha<<24)|(Math.min(255,(r*255+a/2)/a)<<16)|(Math.min(255,(g*255+a/2)/a)<<8)|Math.min(255,(b*255+a/2)/a);
        }
        return output;
    }
}
