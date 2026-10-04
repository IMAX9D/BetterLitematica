package dev.betterlitematica.fabric;

import dev.betterlitematica.core.UiViewport;
import dev.betterlitematica.runtime.OutlineFont;
import dev.betterlitematica.runtime.UiGlyphArt;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.*;

/** Actual production submissions and raster results, without a GL context or texture upload. */
public final class UiTextureQueueChecks {
    private static int checks;
    private static void check(boolean value,String message){checks++;if(!value)throw new AssertionError(message);}
    private static Field field(String name)throws Exception{var f=IndependentUi.class.getDeclaredField(name);f.setAccessible(true);return f;}
    private static void await(CountDownLatch latch)throws Exception{check(latch.await(10,TimeUnit.SECONDS),"Bounded worker rendezvous completes");}
    private static CountDownLatch block(ThreadPoolExecutor worker)throws Exception{
        var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
        worker.execute(()->{entered.countDown();try{release.await();}catch(InterruptedException e){Thread.currentThread().interrupt();}});
        await(entered);return release;
    }
    private static void draw(Method draw,IndependentUi ui,String text,int size,int color)throws Exception{
        try{draw.invoke(ui,text,0,0,size,color);}catch(InvocationTargetException failure){throw new AssertionError("Cold production text draw must defer safely",failure.getCause());}
    }
    public static int run()throws Exception{
        checks=0;var constructor=IndependentUi.class.getDeclaredConstructor();constructor.setAccessible(true);
        var ui=constructor.newInstance();var worker=(ThreadPoolExecutor)field("worker").get(ui);
        CountDownLatch release=null;
        try{
            ((CompletableFuture<?>)field("loading").get(ui)).get(10,TimeUnit.SECONDS);check(ui.ready(),"Independent test UI has its actual selected font");
            field("view").set(ui,UiViewport.fit(960,540,960,540,1));
            var draw=IndependentUi.class.getDeclaredMethod("draw",String.class,int.class,int.class,int.class,int.class);draw.setAccessible(true);
            var pending=(Map<?,?>)field("pending").get(ui);var glyphs=(Map<?,?>)field("pendingGlyphs").get(ui);
            release=block(worker);
            var kinds=UiGlyphArt.Kind.values();
            for(int i=0;i<32;i++)ui.glyph(kinds[i%kinds.length],0,0,12+(i/kinds.length)*8,0xffffffff);
            check(worker.getQueue().size()==32&&glyphs.size()==32,"Real glyph requests saturate the shared production worker queue");
            draw(draw,ui,"加载投影",20,0xffd6b98a);
            check(pending.isEmpty(),"Rejected text submission leaves no stuck pending entry");
            check(worker.getQueue().size()==32,"Rejected text does not displace accepted glyphs");
            release.countDown();release=null;
            for(var value:List.copyOf(glyphs.values()))((CompletableFuture<?>)value).get(10,TimeUnit.SECONDS);
            worker.submit(()->{}).get(10,TimeUnit.SECONDS);
            check(worker.getQueue().isEmpty(),"Accepted glyph work drains after backpressure is released");

            release=block(worker);
            draw(draw,ui,"加载投影",20,0xffd6b98a);
            check(pending.size()==1,"Previously rejected text is admitted on the next cold draw");
            var same=pending.values().iterator().next();
            for(int i=0;i<256;i++)draw(draw,ui,"加载投影",20,(i<<24)|((255-i)<<16)|(i<<8)|91);
            check(pending.size()==1&&pending.values().iterator().next()==same,"Animated RGB and alpha reuse the identical production text request");
            check(worker.getQueue().size()==1,"Colour animation cannot enqueue extra raster jobs");
            draw(draw,ui,"加载投影",21,0xffffffff);draw(draw,ui,"材料清单",20,0xffffffff);
            check(pending.size()==3,"Different physical sizes and text retain separate raster identities");
            release.countDown();release=null;
            var raster=(OutlineFont.Raster)((CompletableFuture<?>)same).get(10,TimeUnit.SECONDS);
            check(Arrays.stream(raster.argb()).anyMatch(c->(c>>>24)>0),"Recovered request produces visible glyphs");
            check(Arrays.stream(raster.argb()).filter(c->(c>>>24)>0).allMatch(c->(c&0xffffff)==0xffffff),"Production text raster is white coverage, independent of requested tint");
            check(Arrays.stream(raster.argb()).anyMatch(c->(c>>>24)>0&&(c>>>24)<255),"White coverage retains antialias transparency");
            for(var value:List.copyOf(pending.values()))((CompletableFuture<?>)value).get(10,TimeUnit.SECONDS);
            check(((dev.betterlitematica.runtime.WeightedLru<?,?>)field("textures").get(ui)).size()==0,"Headless checks never upload a GPU texture");
        }finally{if(release!=null)release.countDown();ui.close();check(worker.awaitTermination(10,TimeUnit.SECONDS),"Independent test worker is released");}
        return checks;
    }
}
