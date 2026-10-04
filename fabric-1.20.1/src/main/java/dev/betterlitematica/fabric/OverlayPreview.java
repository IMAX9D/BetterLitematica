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
    void pixels(int[] pixels){close();if(pixels.length==0)return;side=(int)Math.sqrt(pixels.length);if(side*side!=pixels.length||side>1024)throw new IllegalArgumentException("无效预览图");var image=new NativeImage(side,side,false);for(int y=0;y<side;y++)for(int x=0;x<side;x++)image.setColor(x,y,nativeColor(pixels[y*side+x]));nativeTexture=new NativeImageBackedTexture(image);nativeTexture.setFilter(true,false);texture=MinecraftClient.getInstance().getTextureManager().registerDynamicTexture("betterlitematica-preview",nativeTexture);}
    private static int nativeColor(int argb){return (argb&0xff00ff00)|((argb&255)<<16)|((argb>>>16)&255);}
    void region(int x,int y,int size,int[] pixels){
        if(nativeTexture==null||x<0||y<0||size<1||x+size>side||y+size>side||pixels.length!=size*size)throw new IllegalArgumentException("无效预览区域");
        var image=nativeTexture.getImage();if(image==null)return;
        for(int row=0;row<size;row++)for(int col=0;col<size;col++)image.setColor(x+col,y+row,nativeColor(pixels[row*size+col]));
        nativeTexture.bindTexture();image.upload(0,x,y,x,y,size,size,true,false,false,false);
    }
    void drawRegion(int sourceX,int sourceY,int size,double x,double y,double width,double height){
        if(texture!=null)IndependentUi.INSTANCE.imageRegion(texture,side,sourceX,sourceY,size,size,x,y,width,height);
    }
    @Override public void renderButton(DrawContext context,int mouseX,int mouseY,float delta){var ui=IndependentUi.INSTANCE;ui.roundRect(getX(),getY(),getX()+width,getY()+height,UiTheme.CARD_RADIUS,UiTheme.INPUT);if(texture!=null)ui.image(texture,side,getX()+3,getY()+3,width-6,height-6);ui.roundFrame(getX(),getY(),getX()+width,getY()+height,UiTheme.CARD_RADIUS,UiTheme.BORDER);}
    @Override protected void appendClickableNarrations(NarrationMessageBuilder builder){}
    @Override public void close(){if(texture!=null)MinecraftClient.getInstance().getTextureManager().destroyTexture(texture);else if(nativeTexture!=null)nativeTexture.close();texture=null;nativeTexture=null;}
}
