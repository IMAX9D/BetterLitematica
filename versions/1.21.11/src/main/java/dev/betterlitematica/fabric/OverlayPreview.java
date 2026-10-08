package dev.betterlitematica.fabric;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.widget.ClickableWidget;
import net.minecraft.client.gui.screen.narration.NarrationMessageBuilder;
import net.minecraft.client.texture.*;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
final class OverlayPreview extends ClickableWidget implements AutoCloseable {
    private Identifier texture;private int side;private NativeImageBackedTexture nativeTexture;
    OverlayPreview(int x,int size){super(x,0,size,size,Text.empty());active=false;}
    void pixels(int[] pixels){if(pixels.length==0){close();return;}pixels(pixels,(int)Math.sqrt(pixels.length));}
    /** Reserve the final viewport capacity once; coarse and fine frames reuse that texture. */
    void pixels(int[] pixels,int capacity){
        int size=(int)Math.sqrt(pixels.length);
        if(size<1||size*size!=pixels.length||capacity<size||capacity>1024)throw new IllegalArgumentException("无效预览图");
        if(nativeTexture!=null&&side>=capacity){
            var image=nativeTexture.getImage();if(image==null)return;
            for(int y=0;y<size;y++)for(int x=0;x<size;x++)image.setColorArgb(x,y,pixels[y*size+x]);
            // Linear sampling at a coarse viewport edge must not pick up an older larger frame.
            int upload=Math.min(side,size+1);
            if(size<side)for(int n=0;n<upload;n++){image.setColorArgb(size,n,0);image.setColorArgb(n,size,0);}
            com.mojang.blaze3d.systems.RenderSystem.getDevice().createCommandEncoder().writeToTexture(nativeTexture.getGlTexture(),image,0,0,0,0,upload,upload,0,0);return;
        }
        var image=new NativeImage(capacity,capacity,false);NativeImageBackedTexture next=null;Identifier id;
        try{
            for(int y=0;y<capacity;y++)for(int x=0;x<capacity;x++)image.setColorArgb(x,y,x<size&&y<size?pixels[y*size+x]:0);
            next=new NativeImageBackedTexture(()->"BetterLitematica preview",image);IndependentUi.filter(next,true);id=IndependentUi.registerTexture("betterlitematica-preview",next);
        }catch(RuntimeException|Error failure){if(next!=null)next.close();else image.close();throw failure;}
        // A failed allocation/upload leaves the previous successful frame available.
        close();side=capacity;nativeTexture=next;texture=id;
    }
    boolean ready(){return texture!=null;}
    private static int nativeColor(int argb){return (argb&0xff00ff00)|((argb&255)<<16)|((argb>>>16)&255);}
    void region(int x,int y,int size,int[] pixels){
        if(nativeTexture==null||x<0||y<0||size<1||(long)x+size>side||(long)y+size>side||pixels.length!=(long)size*size)throw new IllegalArgumentException("无效预览区域");
        var image=nativeTexture.getImage();if(image==null)return;
        for(int row=0;row<size;row++)for(int col=0;col<size;col++)image.setColorArgb(x+col,y+row,pixels[row*size+col]);
        com.mojang.blaze3d.systems.RenderSystem.getDevice().createCommandEncoder().writeToTexture(nativeTexture.getGlTexture(),image,0,0,x,y,size,size,x,y);
    }
    void drawRegion(int sourceX,int sourceY,int size,double x,double y,double width,double height){
        if(texture!=null)IndependentUi.INSTANCE.imageRegion(texture,side,sourceX,sourceY,size,size,x,y,width,height);
    }
    @Override public void renderWidget(DrawContext context,int mouseX,int mouseY,float delta){var ui=IndependentUi.INSTANCE;ui.roundRect(getX(),getY(),getX()+width,getY()+height,UiTheme.CARD_RADIUS,UiTheme.SUNKEN);if(texture!=null)ui.image(texture,side,getX()+3,getY()+3,width-6,height-6);ui.roundFrame(getX(),getY(),getX()+width,getY()+height,UiTheme.CARD_RADIUS,UiTheme.BORDER);}
    @Override protected void appendClickableNarrations(NarrationMessageBuilder builder){}
    @Override public void close(){if(texture!=null)MinecraftClient.getInstance().getTextureManager().destroyTexture(texture);else if(nativeTexture!=null)nativeTexture.close();texture=null;nativeTexture=null;side=0;}
}
