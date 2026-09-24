package dev.betterlitematica.runtime;

import java.awt.*;
import java.awt.font.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.util.Map;
import java.util.Locale;
import java.util.LinkedHashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

/** No Minecraft font or window APIs. Produces antialiased glyphs at the final integer pixel size. */
public final class OutlineFont {
    public static final int MAX_TEXT=2048,MAX_WIDTH=8192,MAX_PIXELS=524288;
    private static final FontRenderContext CONTEXT=new FontRenderContext(null,true,true);
    private static final String COVERAGE="投影材料设置中文Ag → × …";
    private static final String[] PREFERRED={"Microsoft YaHei","微软雅黑","Microsoft YaHei UI","SimHei","黑体",
        "PingFang SC","苹方-简","PingFang TC","Heiti SC","黑体-简","Noto Sans CJK SC","Noto Sans SC",
        "Source Han Sans SC","思源黑体","WenQuanYi Micro Hei","WenQuanYi Zen Hei","Arial Unicode MS"};
    private final Font base;
    private final Map<Integer,Font> sizes=new ConcurrentHashMap<>();
    public record Raster(int width,int height,int[] argb) {public long bytes(){return (long)width*height*4;}}
    private OutlineFont(Font base){this.base=base;}
    public static OutlineFont system(){
        String[] available=GraphicsEnvironment.getLocalGraphicsEnvironment().getAvailableFontFamilyNames(Locale.ROOT);
        String family=selectSystemFamily(available,name->new Font(name,Font.PLAIN,20).canDisplayUpTo(COVERAGE)<0);
        return new OutlineFont(new Font(family,Font.PLAIN,20));
    }
    /** Match real installed names first: new Font(unknownName, ...) silently substitutes Dialog. */
    public static String selectSystemFamily(String[] available,Predicate<String> supports){
        Map<String,String> installed=new LinkedHashMap<>();
        for(String name:available)installed.putIfAbsent(name.toLowerCase(Locale.ROOT),name);
        for(String preferred:PREFERRED){String name=installed.get(preferred.toLowerCase(Locale.ROOT));if(name!=null&&supports.test(name))return name;}
        // Java's logical font uses platform font fallback where available.
        if(supports.test(Font.DIALOG))return Font.DIALOG;
        for(String name:installed.values())if(supports.test(name))return name;
        return Font.DIALOG;
    }
    public String family(){return base.getFamily(Locale.ROOT);}
    public static OutlineFont load(InputStream input)throws IOException{
        if(input==null)throw new FileNotFoundException("UI font input missing");
        try(input){return new OutlineFont(Font.createFont(Font.TRUETYPE_FONT,input));}
        catch(FontFormatException e){throw new IOException("Invalid UI font",e);}
    }
    private Font font(int pixels){if(pixels<12||pixels>64)throw new IllegalArgumentException("UI font size outside budget");return sizes.computeIfAbsent(pixels,p->base.deriveFont((float)p));}
    private TextLayout layout(String text,int pixels){if(text.length()>MAX_TEXT)throw new IllegalArgumentException("UI text outside budget");return new TextLayout(text,font(pixels),CONTEXT);}
    public boolean supports(String text){return base.canDisplayUpTo(text)<0;}
    public float width(String text,int pixels){return text.isEmpty()?0:layout(text,pixels).getAdvance();}
    public float lineHeight(int pixels){var metrics=font(pixels).getLineMetrics("中文Ag",CONTEXT);return metrics.getHeight();}
    public int hit(String text,int pixels,float x){return text.isEmpty()?0:Math.max(0,Math.min(text.length(),layout(text,pixels).hitTestChar(x,0).getInsertionIndex()));}
    public int startForCursor(String text,int cursor,int pixels,float width){
        cursor=Math.max(0,Math.min(cursor,text.length()));String prefix=text.substring(0,cursor);
        int[] points=prefix.codePoints().map(Character::charCount).toArray(),ends=new int[points.length+1];for(int i=0;i<points.length;i++)ends[i+1]=ends[i]+points[i];
        int low=0,high=points.length;while(low<high){int mid=(low+high)>>>1;if(width(prefix.substring(ends[mid]),pixels)>width)low=mid+1;else high=mid;}return ends[low];
    }
    public String trim(String text,int pixels,float limit,boolean ellipsis){
        if(text.isEmpty()||limit<=0)return "";int end=Math.min(text.length(),MAX_TEXT);if(end<text.length()&&Character.isHighSurrogate(text.charAt(end-1)))end--;String candidate=text.substring(0,end);
        if(width(candidate,pixels)<=limit)return candidate;
        String suffix=ellipsis?"…":"";float reserve=width(suffix,pixels);if(reserve>limit)return "";
        int[] offsets=candidate.codePoints().map(Character::charCount).toArray();int[] ends=new int[offsets.length+1];for(int i=0;i<offsets.length;i++)ends[i+1]=ends[i]+offsets[i];
        int low=0,high=offsets.length;while(low<high){int mid=(low+high+1)>>>1;if(width(candidate.substring(0,ends[mid]),pixels)+reserve<=limit)low=mid;else high=mid-1;}
        return candidate.substring(0,ends[low])+suffix;
    }
    public Raster raster(String text,int pixels,int color){
        if(text.isEmpty())return new Raster(1,1,new int[1]);
        var layout=layout(text,pixels);int width=(int)Math.ceil(layout.getAdvance())+4,height=(int)Math.ceil(lineHeight(pixels))+4;
        if(width>MAX_WIDTH||(long)width*height>MAX_PIXELS)throw new IllegalArgumentException("UI text raster exceeds budget");
        BufferedImage image=new BufferedImage(width,height,BufferedImage.TYPE_INT_ARGB);Graphics2D graphics=image.createGraphics();
        try{graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,RenderingHints.VALUE_TEXT_ANTIALIAS_ON);graphics.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS,RenderingHints.VALUE_FRACTIONALMETRICS_ON);graphics.setColor(new Color(color,true));layout.draw(graphics,2,2+font(pixels).getLineMetrics(text,CONTEXT).getAscent());}
        finally{graphics.dispose();}
        return new Raster(width,height,image.getRGB(0,0,width,height,null,0,width));
    }
}
