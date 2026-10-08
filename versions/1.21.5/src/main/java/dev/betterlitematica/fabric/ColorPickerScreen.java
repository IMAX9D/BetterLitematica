package dev.betterlitematica.fabric;

import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.narration.NarrationMessageBuilder;
import net.minecraft.client.gui.widget.ClickableWidget;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.texture.NativeImageBackedTexture;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import java.util.Objects;
import java.util.function.IntConsumer;
import org.lwjgl.glfw.GLFW;

/** One texture per open picker; only confirmation commits the draft color to its owner. */
final class ColorPickerScreen extends MenuScreen {
    private static final int TEXTURE_SIZE=512;
    private final IntConsumer accepted;private final long epoch;
    private double hue,saturation,value;private int alpha;private boolean finished;private Identifier wheelTexture;
    ColorPickerScreen(Screen parent,ProjectionController controller,String title,int initial,IntConsumer accepted){
        super(title,"",parent,controller,false);this.accepted=Objects.requireNonNull(accepted);epoch=controller.sessionEpoch();
        var hsv=ColorPickerColor.hsv(initial);hue=hsv.hue();saturation=hsv.saturation();value=hsv.value();alpha=hsv.alpha();
    }
    private int color(){return ColorPickerColor.argb(hue,saturation,value,alpha);}
    @Override protected int preferredHeight(){return 320;}
    @Override protected String backLabel(){return "取消";}
    @Override protected String displayedStatus(){return "";}
    @Override protected void buildMenu(){
        addBody(new Wheel(left,200),6);int right=left+220,width=innerWidth-220;
        addBody(new Preview(right,width),6);
        addBody(new ColorPickerSlider(right,width,"明度",100,value,v->value=v,v->ColorPickerColor.argb(hue,saturation,v,255)),90);
        addBody(new ColorPickerSlider(right,width,"不透明度",255,alpha/255d,v->alpha=(int)Math.round(v*255),v->ColorPickerColor.argb(hue,saturation,value,(int)Math.round(v*255))),143);
        fixed("确定",0,76,this::confirm);
    }
    private void confirm(){if(finished)return;if(epoch!=controller.sessionEpoch()){close();return;}accepted.accept(color());finished=true;super.close();}
    @Override public void close(){finished=true;if(epoch!=controller.sessionEpoch())client.setScreen(null);else super.close();}
    @Override public void removed(){try{if(wheelTexture!=null){client.getTextureManager().destroyTexture(wheelTexture);wheelTexture=null;}}finally{super.removed();}}
    private Identifier texture(){
        if(wheelTexture!=null)return wheelTexture;
        NativeImage image=new NativeImage(TEXTURE_SIZE,TEXTURE_SIZE,false);NativeImageBackedTexture texture=null;
        try{
            double radius=(TEXTURE_SIZE-2)/2d;
            for(int y=0;y<TEXTURE_SIZE;y++)for(int x=0;x<TEXTURE_SIZE;x++){
                double dx=(x+.5-TEXTURE_SIZE/2d)/radius,dy=(y+.5-TEXTURE_SIZE/2d)/radius,d=Math.hypot(dx,dy);
                int a=(int)Math.round(ColorPickerColor.clamp((1-d)*radius+.5)*255);
                image.setColorArgb(x,y,ColorPickerColor.argb(ColorPickerColor.hue(dx,dy),d,1,a));
            }
            texture=new NativeImageBackedTexture(()->"BetterLitematica color wheel",image);texture.setFilter(true,false);wheelTexture=IndependentUi.registerTexture("betterlitematica-color-wheel",texture);return wheelTexture;
        }catch(RuntimeException|Error failure){if(texture!=null)texture.close();else image.close();throw failure;}
    }
    private final class Wheel extends ClickableWidget {
        Wheel(int x,int size){super(x,0,size,size,Text.literal("色相与饱和度"));}
        private void pick(double x,double y){double radius=(width-2)/2d,dx=(x-getX()-width/2d)/radius,dy=(y-getY()-height/2d)/radius;saturation=ColorPickerColor.saturation(dx,dy);if(saturation>0)hue=ColorPickerColor.hue(dx,dy);}
        @Override public boolean mouseClicked(double x,double y,int button){if(!visible||!active||button!=0||Math.hypot(x-getX()-width/2d,y-getY()-height/2d)>width/2d)return false;return super.mouseClicked(x,y,button);}
        @Override public void onClick(double x,double y){pick(x,y);}
        @Override protected void onDrag(double x,double y,double dx,double dy){pick(x,y);}
        @Override public boolean keyPressed(int key,int scan,int modifiers){
            if(key==GLFW.GLFW_KEY_LEFT||key==GLFW.GLFW_KEY_RIGHT){hue=(hue+(key==GLFW.GLFW_KEY_RIGHT?1d:-1d)/360+1)%1;return true;}
            if(key==GLFW.GLFW_KEY_UP||key==GLFW.GLFW_KEY_DOWN){saturation=ColorPickerColor.clamp(saturation+(key==GLFW.GLFW_KEY_UP?.01:-.01));return true;}return super.keyPressed(key,scan,modifiers);
        }
        @Override public void renderWidget(DrawContext context,int mouseX,int mouseY,float delta){
            var ui=IndependentUi.INSTANCE;Identifier id=texture();ui.image(id,TEXTURE_SIZE,getX(),getY(),width,height);
            double radius=(width-2)/2d,x=getX()+width/2d+Math.cos(hue*2*Math.PI)*saturation*radius,y=getY()+height/2d-Math.sin(hue*2*Math.PI)*saturation*radius;
            for(int row=-4;row<=4;row++){double extent=Math.sqrt(20-row*row);ui.rect(x-extent,y+row,x+extent,y+row+1,UiTheme.TEXT);if(Math.abs(row)<=2){double inner=Math.sqrt(8-row*row);ui.rect(x-inner,y+row,x+inner,y+row+1,UiTheme.INPUT);}}
            if(isFocused())ui.roundFrame(getX()-2,getY()-2,getX()+width+2,getY()+height+2,(width+4)/2d,UiTheme.ACCENT);
        }
        @Override protected void appendClickableNarrations(NarrationMessageBuilder builder){appendDefaultNarrations(builder);}
    }
    private final class Preview extends ClickableWidget {
        Preview(int x,int width){super(x,0,width,66,Text.empty());active=false;}
        @Override public boolean mouseClicked(double x,double y,int button){return false;}
        @Override public void renderWidget(DrawContext context,int mouseX,int mouseY,float delta){var ui=IndependentUi.INSTANCE;for(int y=0;y<40;y+=8)for(int x=0;x<width;x+=8)ui.rect(getX()+x,getY()+y,getX()+Math.min(width,x+8),getY()+y+8,((x+y)/8&1)==0?UiTheme.INPUT:UiTheme.TRACK);ui.rect(getX(),getY(),getX()+width,getY()+40,color());
            // Mask only the preview's four corners; alpha and checkerboard retain their exact values.
            for(int row=0;row<6;row++){double cut=6-Math.sqrt(36-(5.5-row)*(5.5-row));ui.rect(getX(),getY()+row,getX()+cut,getY()+row+1,UiTheme.PANEL);ui.rect(getX()+width-cut,getY()+row,getX()+width,getY()+row+1,UiTheme.PANEL);ui.rect(getX(),getY()+39-row,getX()+cut,getY()+40-row,UiTheme.PANEL);ui.rect(getX()+width-cut,getY()+39-row,getX()+width,getY()+40-row,UiTheme.PANEL);}ui.roundFrame(getX(),getY(),getX()+width,getY()+40,6,UiTheme.BORDER);ui.text(String.format("#%08X",color()),getX(),getY()+49,width,UiTheme.TEXT);}
        @Override protected void appendClickableNarrations(NarrationMessageBuilder builder){}
    }
}
