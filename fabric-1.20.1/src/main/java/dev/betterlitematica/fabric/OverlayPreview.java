package dev.betterlitematica.fabric;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.widget.ClickableWidget;
import net.minecraft.client.gui.screen.narration.NarrationMessageBuilder;
import net.minecraft.client.texture.*;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
final class OverlayPreview extends ClickableWidget implements AutoCloseable {
    private Identifier texture;private int side;
    OverlayPreview(int x,int size){super(x,0,size,size,Text.empty());active=false;}
    void pixels(int[] pixels){close();if(pixels.length==0)return;side=(int)Math.sqrt(pixels.length);if(side*side!=pixels.length||side>1024)throw new IllegalArgumentException("无效预览图");var image=new NativeImage(side,side,false);for(int y=0;y<side;y++)for(int x=0;x<side;x++){int c=pixels[y*side+x];image.setColor(x,y,(c&0xff00ff00)|((c&255)<<16)|((c>>>16)&255));}var value=new NativeImageBackedTexture(image);value.setFilter(true,false);texture=MinecraftClient.getInstance().getTextureManager().registerDynamicTexture("betterlitematica-preview",value);}
    @Override public void renderButton(DrawContext context,int mouseX,int mouseY,float delta){var ui=IndependentUi.INSTANCE;ui.rect(getX(),getY(),getX()+width,getY()+height,UiTheme.INPUT);if(texture!=null)ui.image(texture,side,getX(),getY(),width,height);}
    @Override protected void appendClickableNarrations(NarrationMessageBuilder builder){}
    @Override public void close(){if(texture!=null)MinecraftClient.getInstance().getTextureManager().destroyTexture(texture);texture=null;}
}
