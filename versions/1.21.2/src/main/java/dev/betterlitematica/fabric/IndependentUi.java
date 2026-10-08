package dev.betterlitematica.fabric;

import dev.betterlitematica.core.UiViewport;
import dev.betterlitematica.runtime.OutlineFont;
import dev.betterlitematica.runtime.WeightedLru;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.texture.*;
import net.minecraft.util.Identifier;
import com.mojang.blaze3d.systems.RenderSystem;
import java.util.*;
import java.util.concurrent.*;

/** Physical-pixel overlay. Font textures are copied 1:1, never enlarged game glyphs. */
final class IndependentUi implements AutoCloseable {
    // Keep menus ahead of HUD depth (including transparent full-screen HUD quads).
    static final int MENU_DEPTH=2000;
    static final IndependentUi INSTANCE=new IndependentUi();
    private record Key(String text,int pixels){}
    private record Texture(Identifier id,int width,int height){long bytes(){return (long)width*height*4;}}
    private final ThreadPoolExecutor worker=new ThreadPoolExecutor(1,1,0,TimeUnit.MILLISECONDS,new ArrayBlockingQueue<>(32),r->{Thread t=new Thread(r,"betterlitematica-ui-font");t.setDaemon(true);return t;});
    private final CompletableFuture<OutlineFont> loading;
    private final Map<Key,CompletableFuture<OutlineFont.Raster>> pending=new LinkedHashMap<>();
    private final WeightedLru<Key,Texture> textures=new WeightedLru<>(16L<<20,256,Texture::bytes,t->MinecraftClient.getInstance().getTextureManager().destroyTexture(t.id()));
    private final StatusBadgeTextures statusBadges=new StatusBadgeTextures();
    private record GlyphKey(dev.betterlitematica.runtime.UiGlyphArt.Kind kind,int pixels){}
    private final Map<GlyphKey,CompletableFuture<dev.betterlitematica.runtime.UiGlyphArt.Raster>> pendingGlyphs=new LinkedHashMap<>();
    private final WeightedLru<GlyphKey,Texture> glyphs=new WeightedLru<>(4L<<20,192,Texture::bytes,t->MinecraftClient.getInstance().getTextureManager().destroyTexture(t.id()));
    private final LinkedHashMap<String,Float> widths=new LinkedHashMap<>(256,0.75f,true);
    private final LinkedHashMap<String,String> trims=new LinkedHashMap<>(256,0.75f,true);
    private final Deque<UiViewport.Clip> clips=new ArrayDeque<>();
    private OutlineFont font;private UiViewport view;private DrawContext context;private float[] previousColor;private boolean active;
    private int textPixels=20;
    private double offsetX,offsetY,opacity=1;
    private IndependentUi(){loading=CompletableFuture.supplyAsync(()->{
        var selected=OutlineFont.system();BetterLitematicaClient.LOGGER.info("UI system font: {}",selected.family());
        if(!selected.supports("投影材料设置"))BetterLitematicaClient.LOGGER.warn("No Chinese-capable system font found; install a CJK font for menu text.");
        return selected;
    },worker);}
    boolean ready(){if(font==null&&loading.isDone())font=loading.join();return font!=null;}
    int pixels(){return textPixels;}
    boolean begin(DrawContext ctx,UiViewport viewport){
        offsetX=offsetY=0;opacity=1;
        view=viewport;
        if(!ready())return false;
        textPixels=UiTypography.bodyPixels(view.scale(),font);
        drain();drainGlyphs();context=ctx;ctx.draw();previousColor=RenderSystem.getShaderColor().clone();RenderSystem.setShaderColor(1,1,1,1);
        ctx.getMatrices().push();ctx.getMatrices().loadIdentity();ctx.getMatrices().scale((float)(1/view.guiScale()),(float)(1/view.guiScale()),1);
        active=true;return true;
    }
    /** HUD callbacks run after chat, but chat's depth still occludes z=0 textures and items. */
    boolean beginHud(DrawContext ctx,UiViewport viewport){
        if(!begin(ctx,viewport))return false;
        ctx.getMatrices().translate(0,0,1000);
        return true;
    }
    boolean beginMenu(DrawContext ctx,UiViewport viewport){
        if(!begin(ctx,viewport))return false;
        ctx.getMatrices().translate(0,0,MENU_DEPTH);
        return true;
    }
    /** Translation only: glyphs keep their physical resolution throughout a transition. */
    void effect(double x,double y,double alpha){
        if(!clips.isEmpty())throw new IllegalStateException("UI effect changed inside a clip");
        context.draw();offsetX=x;offsetY=y;opacity=UiMotion.clamp(alpha);
    }
    void end(){if(!active)return;try{while(!clips.isEmpty())unclip();context.draw();}finally{context.getMatrices().pop();RenderSystem.setShaderColor(previousColor[0],previousColor[1],previousColor[2],previousColor[3]);active=false;context=null;}}
    private void drain(){
        int count=0;long bytes=0;
        for(var it=pending.entrySet().iterator();it.hasNext()&&count<6&&bytes<(1<<20);){
            var entry=it.next();if(!entry.getValue().isDone())continue;var raster=entry.getValue().join();it.remove();count++;bytes+=raster.bytes();
            NativeImage image=new NativeImage(raster.width(),raster.height(),false);int[] argb=raster.argb();
            for(int y=0;y<raster.height();y++)for(int x=0;x<raster.width();x++){int c=argb[x+y*raster.width()];image.setColorArgb(x,y,c);}
            NativeImageBackedTexture texture=new NativeImageBackedTexture(image);texture.setFilter(false,false);
            Identifier id=MinecraftClient.getInstance().getTextureManager().registerDynamicTexture("betterlitematica-ui",texture);
            textures.put(entry.getKey(),new Texture(id,raster.width(),raster.height()));
        }
    }
    private void drainGlyphs(){
        int count=0;
        for(var it=pendingGlyphs.entrySet().iterator();it.hasNext()&&count<8;){
            var entry=it.next();if(!entry.getValue().isDone())continue;it.remove();count++;
            dev.betterlitematica.runtime.UiGlyphArt.Raster raster;try{raster=entry.getValue().join();}catch(RuntimeException failed){continue;}
            NativeImage image=new NativeImage(raster.size(),raster.size(),false);int[] argb=raster.argb();
            for(int y=0;y<raster.size();y++)for(int x=0;x<raster.size();x++){int c=argb[x+y*raster.size()];image.setColorArgb(x,y,c);}
            NativeImageBackedTexture texture=new NativeImageBackedTexture(image);texture.setFilter(false,false);
            Identifier id=MinecraftClient.getInstance().getTextureManager().registerDynamicTexture("betterlitematica-glyph",texture);
            glyphs.put(entry.getKey(),new Texture(id,raster.size(),raster.size()));
        }
    }
    /** Vector line icon at its exact physical size, tinted with any theme colour. */
    void glyph(dev.betterlitematica.runtime.UiGlyphArt.Kind kind,double x,double y,double size,int color){
        int pixels=Math.max(dev.betterlitematica.runtime.UiGlyphArt.MIN_PIXELS,Math.min(dev.betterlitematica.runtime.UiGlyphArt.MAX_PIXELS,(int)Math.round(size*view.scale())));
        var key=new GlyphKey(kind,pixels);Texture ready=glyphs.get(key);
        if(ready==null){if(pendingGlyphs.size()<48&&!pendingGlyphs.containsKey(key))try{pendingGlyphs.put(key,CompletableFuture.supplyAsync(()->dev.betterlitematica.runtime.UiGlyphArt.raster(kind,pixels),worker));}catch(RejectedExecutionException busy){}return;}
        int left=(int)Math.round(view.pixelX(x+offsetX+size/2)-pixels/2d),top=(int)Math.round(view.pixelY(y+offsetY+size/2)-pixels/2d);
        context.draw();RenderSystem.enableBlend();RenderSystem.defaultBlendFunc();
        try{RenderSystem.setShaderColor((color>>>16&255)/255f,(color>>>8&255)/255f,(color&255)/255f,(float)((color>>>24)/255d*opacity));context.drawTexture(net.minecraft.client.render.RenderLayer::getGuiTextured,ready.id(),left,top,0,0,pixels,pixels,pixels,pixels);context.draw();}
        finally{RenderSystem.setShaderColor(1,1,1,1);RenderSystem.disableBlend();}
    }
    private Texture texture(String text,int size){
        Key key=new Key(text,size);Texture ready=textures.get(key);if(ready!=null)return ready;
        // Glyphs share this bounded worker. Backpressure defers a cold run to a later frame.
        if(pending.size()<24&&!pending.containsKey(key))try{pending.put(key,CompletableFuture.supplyAsync(()->font.raster(text,size,0xffffffff),worker));}catch(RejectedExecutionException busy){}
        return null;
    }
    private static String bounded(String text){if(text.length()<=OutlineFont.MAX_TEXT)return text;int end=OutlineFont.MAX_TEXT-1;if(Character.isHighSurrogate(text.charAt(end-1)))end--;return text.substring(0,end)+"…";}
    double measure(String text){return ready()?measurePixels(bounded(text),textPixels)/view.scale():0;}
    private float measurePixels(String text,int pixels){String key=pixels+":"+text;Float result=widths.get(key);if(result==null){result=font.width(text,pixels);if(widths.size()>=1024)widths.remove(widths.keySet().iterator().next());widths.put(key,result);}return result;}
    String trim(String text,double width){if(!ready())return "";return trimAt(text,textPixels,width);}
    private int rasterWidthLimit(int pixels){return Math.min(OutlineFont.MAX_WIDTH-4,OutlineFont.MAX_PIXELS/((int)Math.ceil(font.lineHeight(pixels))+4)-4);}
    private String trimAt(String text,int pixels,double width){text=bounded(text);int limit=Math.max(0,Math.min(rasterWidthLimit(pixels),(int)Math.floor(width*view.scale())));String key=pixels+":"+limit+":"+text;String result=trims.get(key);if(result==null){result=font.trim(text,pixels,limit,true);if(trims.size()>=1024)trims.remove(trims.keySet().iterator().next());trims.put(key,result);}return result;}
    void item(net.minecraft.item.ItemStack stack,double x,double y,double size){
        context.draw();var matrices=context.getMatrices();matrices.push();
        try{RenderSystem.setShaderColor(1,1,1,(float)opacity);matrices.translate(px(x),py(y),0);float scale=(float)(size*view.scale()/16);matrices.scale(scale,scale,1);context.drawItem(stack,0,0);context.draw();}
        finally{matrices.pop();RenderSystem.setShaderColor(1,1,1,1);RenderSystem.disableBlend();}
    }
    void itemCount(int count,double x,double y,double width){context.getMatrices().push();context.getMatrices().translate(0,0,300);try{text(Integer.toString(count),x,y,width,UiTheme.TEXT);}finally{context.getMatrices().pop();}}
    void rect(double x,double y,double right,double bottom,int color){context.fill(px(x),py(y),px(right),py(bottom),UiMotion.alpha(color,opacity));}
    void roundRect(double x,double y,double right,double bottom,double radius,int color){roundedBatch(x,y,right,bottom,radius,color,false);}
    void roundFrame(double x,double y,double right,double bottom,double radius,int color){roundedBatch(x,y,right,bottom,radius,color,true);}
    private void roundedBatch(double x,double y,double right,double bottom,double radius,int color,boolean outline){
        if((UiMotion.alpha(color,opacity)>>>24)==0)return;
        // DrawContext.fill otherwise flushes every antialiased span. Keep one geometry-only
        // batch per shape; texture tint, scissor and matrix changes still flush at their boundaries.
        context.draw(ignored->rounded(x,y,right,bottom,radius,color,outline));
    }
    /** Small controls: a tight contact shadow. */
    void shadow(double x,double y,double right,double bottom,double radius){
        roundRect(x-2,y+1,right+2,bottom+4,radius+2,UiTheme.SHADOW_AMBIENT);
        roundRect(x-1,y+1,right+1,bottom+2.5,radius+1,UiTheme.SHADOW_AMBIENT);
        roundRect(x-.5,y+.5,right+.5,bottom+1.5,radius+.5,UiMotion.alpha(UiTheme.SHADOW,.55));
    }
    /** Resting controls on light paper: one crisp pixel of contact, no blur. */
    void contact(double x,double y,double right,double bottom,double radius){roundRect(x,y+1,right,bottom+1,radius,UiMotion.alpha(UiTheme.SHADOW,.7));}
    /** Floating surfaces: a wide, soft ambient falloff under a crisp key shadow. */
    void elevation(double x,double y,double right,double bottom,double radius){
        for(int i=6;i>=1;i--){double spread=i*3.2;roundRect(x-spread,y-spread*.45+i*1.4,right+spread,bottom+spread+i*1.6,radius+spread,UiMotion.alpha(UiTheme.SHADOW_AMBIENT,1.15-i*.12));}
        roundRect(x-1,y+1,right+1,bottom+3,radius+1,UiMotion.alpha(UiTheme.SHADOW,.6));
    }
    /** Soft focus halo drawn outside a control. */
    void ring(double x,double y,double right,double bottom,double radius,double strength){
        if(strength<=.01)return;
        roundFrame(x-2,y-2,right+2,bottom+2,radius+2,UiMotion.alpha(UiTheme.RING,strength*.55));
        roundFrame(x-1,y-1,right+1,bottom+1,radius+1,UiMotion.alpha(UiTheme.RING,strength));
    }
    /** One physical pixel of light along the inside top edge; gives dark surfaces their lift. */
    void sheen(double x,double y,double right,double radius){
        if((UiTheme.SHEEN>>>24)==0)return;double inset=Math.max(radius*.7,1);
        rect(x+inset,y,right-inset,y+1/view.scale(),UiTheme.SHEEN);
    }
    /** Rounded coverage is batched in the normal GUI layer; no extra shader/FBO per control. */
    private void rounded(double x,double y,double right,double bottom,double radius,int color,boolean outline){
        int l=px(x),t=py(y),r=px(right),b=py(bottom);if(r<=l||b<=t)return;
        double rad=Math.max(0,Math.min(Math.min(r-l,b-t)/2d,radius*view.scale()));
        int c=UiMotion.alpha(color,opacity),rows=(int)Math.ceil(rad);
        if(rad<1){if(outline){context.fill(l,t,r,t+1,c);context.fill(l,b-1,r,b,c);context.fill(l,t,l+1,b,c);context.fill(r-1,t,r,b,c);}else context.fill(l,t,r,b,c);return;}
        for(int i=0;i<rows;i++){
            double dy=Math.max(0,rad-i-.5),cut=rad-Math.sqrt(Math.max(0,rad*rad-dy*dy));
            double inRad=Math.max(0,rad-1),innerCut=1+inRad-Math.sqrt(Math.max(0,inRad*inRad-dy*dy));
            roundedRow(l,r,t+i,cut,innerCut,c,outline&&i>0);
            if(b-1-i!=t+i)roundedRow(l,r,b-1-i,cut,innerCut,c,outline&&i>0);
        }
        if(t+rows<b-rows){if(outline){context.fill(l,t+rows,l+1,b-rows,c);context.fill(r-1,t+rows,r,b-rows,c);}else context.fill(l,t+rows,r,b-rows,c);}
    }
    private void roundedRow(int l,int r,int y,double cut,double innerCut,int color,boolean hollow){
        if(hollow){pixelSpan(l+cut,l+innerCut,y,color);pixelSpan(r-innerCut,r-cut,y,color);}
        else pixelSpan(l+cut,r-cut,y,color);
    }
    private void pixelSpan(double left,double right,int y,int color){
        if(right<=left)return;int first=(int)Math.floor(left),last=(int)Math.floor(right);
        if(first==last){context.fill(first,y,first+1,y+1,UiMotion.alpha(color,right-left));return;}
        int whole=(int)Math.ceil(left);
        if(whole>first)context.fill(first,y,first+1,y+1,UiMotion.alpha(color,whole-left));
        if(last>whole)context.fill(whole,y,last,y+1,color);
        if(right>last)context.fill(last,y,last+1,y+1,UiMotion.alpha(color,right-last));
    }
    /** Physical-pixel annular sector, also used for discs (inner=0). Angles are radians. */
    void sector(double cx,double cy,double inner,double outer,double start,double end,int color){
        if(outer<=inner||end<=start)return;
        color=UiMotion.alpha(color,opacity);
        context.draw();boolean depth=org.lwjgl.opengl.GL11.glIsEnabled(org.lwjgl.opengl.GL11.GL_DEPTH_TEST),cull=org.lwjgl.opengl.GL11.glIsEnabled(org.lwjgl.opengl.GL11.GL_CULL_FACE);
        RenderSystem.disableDepthTest();RenderSystem.disableCull();RenderSystem.enableBlend();RenderSystem.defaultBlendFunc();
        try{
            RenderSystem.setShader(net.minecraft.client.gl.ShaderProgramKeys.POSITION_COLOR);
            var matrix=context.getMatrices().peek().getPositionMatrix();
            var buffer=net.minecraft.client.render.Tessellator.getInstance().begin(net.minecraft.client.render.VertexFormat.DrawMode.QUADS,net.minecraft.client.render.VertexFormats.POSITION_COLOR);
            int steps=Math.max(1,Math.min(256,(int)Math.ceil((end-start)*outer*view.scale()/5)));
            for(int i=0;i<steps;i++){
                double a=start+(end-start)*i/steps,b=start+(end-start)*(i+1)/steps;
                for(int v=0;v<4;v++){double r=v==1||v==2?outer:inner,t=v<2?a:b;buffer.vertex(matrix,(float)view.pixelX(cx+offsetX+Math.cos(t)*r),(float)view.pixelY(cy+offsetY+Math.sin(t)*r),0).color((color>>>16)&255,(color>>>8)&255,color&255,(color>>>24)&255);}
            }
            net.minecraft.client.render.BufferRenderer.drawWithGlobalProgram(buffer.end());
        }finally{RenderSystem.disableBlend();if(depth)RenderSystem.enableDepthTest();if(cull)RenderSystem.enableCull();}
    }
    void frame(double x,double y,double right,double bottom,int color){double line=1/view.scale();rect(x,y,right,y+line,color);rect(x,bottom-line,right,bottom,color);rect(x,y,x+line,bottom,color);rect(right-line,y,right,bottom,color);}
    void image(Identifier id,int pixels,double x,double y,double width,double height){
        imageRegion(id,pixels,0,0,pixels,pixels,x,y,width,height);
    }
    void imageRegion(Identifier id,int pixels,int sourceX,int sourceY,int sourceWidth,int sourceHeight,double x,double y,double width,double height){
        context.draw();RenderSystem.enableBlend();RenderSystem.defaultBlendFunc();
        try{RenderSystem.setShaderColor(1,1,1,(float)opacity);context.drawTexture(net.minecraft.client.render.RenderLayer::getGuiTextured,id,px(x),py(y),px(x+width)-px(x),py(y+height)-py(y),sourceX,sourceY,sourceWidth,sourceHeight,pixels,pixels);context.draw();}
        finally{RenderSystem.setShaderColor(1,1,1,1);RenderSystem.disableBlend();}
    }
    void prepareStatusBadges(double diameter){statusBadges.prepare(statusBadgePixels(diameter),UiTheme.DARK);}
    private int statusBadgePixels(double diameter){return Math.max(12,Math.min(192,(int)Math.round(diameter*view.scale())));}
    void statusBadge(dev.betterlitematica.runtime.StatusBadgeArt.Kind kind,double x,double y,double diameter){
        var texture=statusBadges.get(kind,statusBadgePixels(diameter),UiTheme.DARK);if(texture==null)return;
        context.draw();RenderSystem.enableBlend();RenderSystem.defaultBlendFunc();
        // Exact physical-pixel blit: supersampled curves must not be rescaled with the game GUI.
        try{RenderSystem.setShaderColor(1,1,1,(float)opacity);context.drawTexture(net.minecraft.client.render.RenderLayer::getGuiTextured,texture.id(),px(x)-texture.padding(),py(y)-texture.padding(),0,0,texture.size(),texture.size(),texture.size(),texture.size());context.draw();}
        finally{RenderSystem.setShaderColor(1,1,1,1);RenderSystem.disableBlend();}
    }
    private int px(double x){return (int)Math.round(view.pixelX(x+offsetX));}private int py(double y){return (int)Math.round(view.pixelY(y+offsetY));}
    void text(String text,double x,double y,double maxWidth,int color){draw(trimAt(text,textPixels,maxWidth),px(x),py(y),textPixels,color);}
    void title(String text,double x,double y,double maxWidth,int color){int size=UiTypography.titlePixels(view.scale(),font);draw(trimAt(text,size,maxWidth),px(x),py(y),size,color);}
    void centered(String text,double x,double y,double width,double height,int color){String shown=trim(text,width-12);int left=px(x)+(int)Math.round((width*view.scale()-measurePixels(shown,textPixels))/2);int top=py(y)+(int)Math.round((height*view.scale()-font.lineHeight(textPixels))/2);draw(shown,left,top,textPixels,color);}
    private void draw(String text,int x,int y,int pixels,int color){
        if(text.isEmpty())return;Texture run=texture(text,pixels);if(run==null)return;
        context.draw();RenderSystem.enableBlend();RenderSystem.defaultBlendFunc();
        // Tint the coverage mask so animated colours and palette changes reuse the same run.
        try{RenderSystem.setShaderColor((color>>>16&255)/255f,(color>>>8&255)/255f,(color&255)/255f,(float)((color>>>24)/255d*opacity));context.drawTexture(net.minecraft.client.render.RenderLayer::getGuiTextured,run.id(),x-2,y-2,0,0,run.width(),run.height(),run.width(),run.height());context.draw();}
        finally{RenderSystem.setShaderColor(1,1,1,1);RenderSystem.disableBlend();}
    }
    void clip(double left,double top,double right,double bottom){context.draw();var clip=view.clip(left+offsetX,top+offsetY,right+offsetX,bottom+offsetY);if(clips.isEmpty())context.enableScissor(0,0,view.inputWidth(),view.inputHeight());else{var parent=clips.peek();int x=Math.max(parent.x(),clip.x()),y=Math.max(parent.y(),clip.y());clip=new UiViewport.Clip(x,y,Math.max(0,Math.min(parent.x()+parent.width(),clip.x()+clip.width())-x),Math.max(0,Math.min(parent.y()+parent.height(),clip.y()+clip.height())-y));}clips.push(clip);RenderSystem.enableScissor(clip.x(),clip.y(),clip.width(),clip.height());}
    void unclip(){context.draw();clips.pop();if(clips.isEmpty())context.disableScissor();else{var clip=clips.peek();RenderSystem.enableScissor(clip.x(),clip.y(),clip.width(),clip.height());}}
    int hit(String text,double localX){return ready()?font.hit(text,textPixels,(float)(localX*view.scale())):0;}
    int startForCursor(String text,int cursor,double width){return font.startForCursor(text,cursor,textPixels,(float)(width*view.scale()));}
    String fittingText(String text,double width){return font.trim(text,textPixels,(float)Math.min(rasterWidthLimit(textPixels),width*view.scale()),false);}
    void rawText(String text,double x,double y,int color){draw(text,px(x),py(y),textPixels,color);}
    /** Queue a stable label before its first animated reveal, using the existing bounded worker. */
    void prepareText(String text){if(!text.isEmpty())texture(text,textPixels);}
    double lineHeight(){return font.lineHeight(textPixels)/view.scale();}
    double titleLineHeight(){return font.lineHeight(UiTypography.titlePixels(view.scale(),font))/view.scale();}
    double measureTitle(String text){if(!ready())return 0;int size=UiTypography.titlePixels(view.scale(),font);return measurePixels(bounded(text),size)/view.scale();}
    double pixel(){return 1/view.scale();}
    void clearTextures(){for(var future:pending.values())future.cancel(false);pending.clear();worker.getQueue().clear();textures.close();widths.clear();trims.clear();statusBadges.clear();for(var future:pendingGlyphs.values())future.cancel(false);pendingGlyphs.clear();glyphs.close();}
    @Override public void close(){worker.shutdownNow();pending.clear();clearTextures();statusBadges.close();}
}
