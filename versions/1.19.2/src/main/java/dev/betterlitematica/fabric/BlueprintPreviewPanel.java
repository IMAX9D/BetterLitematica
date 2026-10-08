package dev.betterlitematica.fabric;

import dev.betterlitematica.core.PlacementTransform;
import dev.betterlitematica.core.PreviewCamera;
import dev.betterlitematica.core.UiViewport;
import dev.betterlitematica.core.Vec3i;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.client.gui.widget.ClickableWidget;
import net.minecraft.client.gui.screen.narration.NarrationMessageBuilder;
import net.minecraft.text.Text;
import org.lwjgl.glfw.GLFW;

/** Shared camera and pointer capture for the browser and placement preview. */
final class BlueprintPreviewPanel extends ClickableWidget implements AutoCloseable {
    public int getX(){return x;} public int getY(){return y;} public void setX(int value){x=value;} public void setY(int value){y=value;}

    private static final PlacementTransform IDENTITY=new PlacementTransform(Vec3i.ZERO,0,false,false);
    private static final int GAP=8,INSET=3;
    private static final long SETTLE_NANOS=160_000_000L,DOUBLE_CLICK_NANOS=300_000_000L;
    private OverlayPreview image,orbitImage;
    private FilePreviews.Orbit orbit;
    private final ProjectionController controller;
    private final OverlayLabel name;
    private final boolean gallery;
    private PlacementTransform transform=IDENTITY;
    private PreviewCamera camera=PreviewCamera.DEFAULT;
    private PreviewCamera displayedCamera;
    private record Request(PreviewCamera camera,int side,boolean coarse,PlacementTransform transform){}
    private Request requested;
    private String status="";
    private boolean moved,appliedDetailed;private int dragButton=-1,orbitSide;
    private long lastInput,lastClick,requestedFrame,appliedFrame;
    private double clickX,clickY,pointerX=-1,pointerY=-1;
    BlueprintPreviewPanel(ProjectionController controller,int x,int width,int height){this(controller,x,width,height,true);}
    BlueprintPreviewPanel(ProjectionController controller,int x,int width,int height,boolean gallery){super(x,0,width,height,Text.empty());this.controller=controller;this.gallery=gallery;active=false;name=new OverlayLabel(x,width,"");}
    void selected(String value){close();camera=PreviewCamera.DEFAULT;lastInput=lastClick=0;name.setMessage(Text.literal(value));status=value.isEmpty()?"":"生成中…";}
    void failed(){close();status="预览失败";}
    void layout(int x,int width,int height){setX(x);setWidth(width);this.height=height;name.setX(x);name.setWidth(width);}
    void images(FilePreviews.Preview preview){
        var value=preview.images();int side=value.side();var views=value.views();
        if(side!=256||views.size()!=4||views.stream().anyMatch(p->p==null||p.length!=side*side))throw new IllegalArgumentException("无效预览图");
        // The static thumbnail atlas never changes while interacting with the separate orbit image.
        int combinedSide=side*2;int[] pixels=new int[combinedSide*combinedSide];
        for(int i=0;i<4;i++)for(int y=0;y<side;y++)System.arraycopy(views.get(i),y*side,pixels,((i/2)*side+y)*combinedSide+(i%2)*side,side);
        close();
        try{image=new OverlayPreview(getX(),imageSize());image.pixels(pixels);orbitImage=new OverlayPreview(getX(),imageSize());orbit=controller.previewOrbit(preview);active=true;status="";request(false);}
        catch(RuntimeException e){close();throw e;}
    }
    void orientation(PlacementTransform value){
        var next=new PlacementTransform(Vec3i.ZERO,value.quarterTurns(),value.mirrorX(),value.mirrorZ());
        if(transform.equals(next))return;transform=next;request(interacting(System.nanoTime()));
    }
    private int physicalSide(){
        var window=MinecraftClient.getInstance().getWindow();
        var viewport=UiViewport.fit(Math.max(1,window.getFramebufferWidth()),Math.max(1,window.getFramebufferHeight()),Math.max(1,window.getScaledWidth()),Math.max(1,window.getScaledHeight()),window.getScaleFactor());
        return Math.max(128,Math.min(1024,(int)Math.ceil(contentSize()*viewport.scale())));
    }
    private void request(boolean coarse){
        if(orbit==null)return;
        var next=new Request(camera,coarse?Math.min(384,physicalSide()):physicalSide(),coarse,transform);
        if(next.equals(requested))return;
        long sequence=orbit.request(camera.yaw(),camera.pitch(),camera.zoom(),camera.panX(),camera.panY(),next.side(),coarse,transform);
        if(sequence>0){requested=next;requestedFrame=sequence;}
    }
    private boolean interacting(long now){return lastInput!=0&&now-lastInput<SETTLE_NANOS;}
    private void changed(){lastInput=System.nanoTime();request(true);}
    private int imageSize(){return Math.max(24,Math.min(height-(gallery?24:0),gallery?(3*width-GAP)/4:width));}
    private int contentSize(){return imageSize()-INSET*2;}
    private int thumbnailSize(){return Math.max(1,(imageSize()-2*GAP)/3);}
    private int imageLeft(){return getX()+(width-imageSize()-(gallery?GAP+thumbnailSize():0))/2;}
    private int imageTop(){return getY()+(gallery?24:Math.max(0,(height-imageSize())/2));}
    private boolean orbitHit(double x,double y){int left=imageLeft(),top=imageTop(),size=imageSize();return visible&&active&&orbit!=null&&x>=left&&x<left+size&&y>=top&&y<top+size;}
    /** A reset chip appears in the corner once the view leaves its default framing; double-click remains a shortcut. */
    private static final int RESET=18;
    private boolean resetShown(){return orbit!=null&&!camera.equals(PreviewCamera.DEFAULT);}
    private boolean resetHit(double x,double y){int rx=imageLeft()+imageSize()-INSET-RESET-4,ry=imageTop()+INSET+4;return resetShown()&&orbitHit(x,y)&&x>=rx&&x<rx+RESET&&y>=ry&&y<ry+RESET;}
    boolean recentlyUsed(){return lastInput!=0&&System.nanoTime()-lastInput<1_500_000_000L;}
    @Override public boolean mouseClicked(double x,double y,int button){
        if((button!=0&&button!=2)||!orbitHit(x,y))return false;
        if(button==0&&resetHit(x,y)){camera=PreviewCamera.DEFAULT;lastClick=0;changed();return true;}
        long now=System.nanoTime();
        if(button==0&&lastClick!=0&&now-lastClick<DOUBLE_CLICK_NANOS&&Math.hypot(x-clickX,y-clickY)<=5){camera=PreviewCamera.DEFAULT;changed();lastClick=0;}
        else lastClick=0;
        clickX=x;clickY=y;dragButton=button;moved=false;setFocused(true);return true;
    }
    @Override public boolean mouseDragged(double x,double y,int button,double dx,double dy){
        if(dragButton<0||button!=dragButton||orbit==null)return false;
        if(dx!=0||dy!=0){var next=button==2?camera.pan(dx,dy,contentSize()):camera.rotate(dx,dy,contentSize());if(!next.equals(camera)){camera=next;moved=true;changed();}}
        return true;
    }
    @Override public boolean mouseScrolled(double x,double y,double amount){
        if(!orbitHit(x,y))return false;
        double anchorX=Math.max(-.5,Math.min(.5,(x-imageLeft()-INSET)/contentSize()-.5));
        double anchorY=Math.max(-.5,Math.min(.5,(y-imageTop()-INSET)/contentSize()-.5));
        var next=camera.scroll(amount,anchorX,anchorY);
        if(!next.equals(camera)){camera=next;moved=true;lastClick=0;changed();}return true;
    }
    @Override public boolean mouseReleased(double x,double y,int button){return releaseDrag(button);}
    boolean releaseDrag(int button){
        if(dragButton<0||button!=dragButton)return false;
        if(button==0&&!moved)lastClick=System.nanoTime();else lastClick=0;
        dragButton=-1;moved=false;return true;
    }
    boolean capturing(){return dragButton>=0;}
    void cancelCapture(){dragButton=-1;moved=false;lastClick=0;}
    void pollCapture(){
        if(dragButton<0)return;
        var client=MinecraftClient.getInstance();
        if(!visible||!active||!client.isWindowFocused()||GLFW.glfwGetMouseButton(client.getWindow().getHandle(),dragButton)!=GLFW.GLFW_PRESS)cancelCapture();
    }
    @Override public void renderButton(MatrixStack legacyMatrices,int mouseX,int mouseY,float delta){LegacyGuiContext context=new LegacyGuiContext(legacyMatrices);
        if(name.getMessage().getString().isEmpty())return;
        pointerX=mouseX;pointerY=mouseY;
        var ui=IndependentUi.INSTANCE;if(gallery){name.setY(getY());name.render(context.getMatrices(),mouseX,mouseY,delta);}
        pollCapture();request(interacting(System.nanoTime()));
        if(orbit!=null){
            var frame=orbit.poll();
            if(frame!=null&&frame.transform().equals(transform)&&(frame.sequence()>appliedFrame||frame.sequence()==appliedFrame&&!appliedDetailed&&frame.detailed())){
                context.draw();orbitImage.pixels(frame.pixels(),Math.max(frame.side(),physicalSide()));orbitSide=frame.side();appliedFrame=frame.sequence();appliedDetailed=frame.detailed();
                displayedCamera=new PreviewCamera(frame.yaw(),frame.pitch(),frame.zoom(),frame.panX(),frame.panY());
            }
        }
        int size=imageSize(),x=imageLeft(),y=imageTop();drawOrbit(x,y,size);
        if(gallery){int thumb=thumbnailSize();for(int i=0;i<3;i++)drawView(i,x+size+GAP,y+i*(thumb+GAP),thumb);}
        if(!status.isEmpty())ui.centered(status,x,y+(size-19)/2.0,size,19,status.equals("预览失败")?UiTheme.ERROR:UiTheme.MUTED);
    }
    private void drawOrbit(int x,int y,int size){
        var ui=IndependentUi.INSTANCE;ui.roundRect(x,y,x+size,y+size,UiTheme.CARD_RADIUS,UiTheme.SUNKEN);
        if(orbitImage!=null&&orbitImage.ready()){
            double content=size-INSET*2,ratio=1,offsetX=0,offsetY=0;
            // Pan/zoom are affine in this orthographic view. Reproject the last good image on the
            // GPU for immediate pointer response while the worker fills newly exposed areas.
            if(displayedCamera!=null&&camera.yaw()==displayedCamera.yaw()&&camera.pitch()==displayedCamera.pitch()){
                ratio=camera.zoom()/displayedCamera.zoom();
                offsetX=.5+camera.panX()-(.5+displayedCamera.panX())*ratio;
                offsetY=.5+camera.panY()-(.5+displayedCamera.panY())*ratio;
            }
            ui.clip(x+INSET,y+INSET,x+size-INSET,y+size-INSET);
            orbitImage.drawRegion(0,0,orbitSide,x+INSET+offsetX*content,y+INSET+offsetY*content,content*ratio,content*ratio);
            ui.unclip();
        }
        else if(image!=null)image.drawRegion(256,256,256,x+INSET,y+INSET,size-INSET*2,size-INSET*2);
        ui.roundFrame(x,y,x+size,y+size,UiTheme.CARD_RADIUS,UiTheme.BORDER);
        if(resetShown()){
            double rx=x+size-INSET-RESET-4,ry=y+INSET+4;boolean over=resetHit(pointerX,pointerY);
            ui.roundRect(rx,ry,rx+RESET,ry+RESET,RESET/2d,UiMotion.alpha(over?UiTheme.HOVER:UiTheme.OVERLAY_PANEL,.92));
            ui.roundFrame(rx,ry,rx+RESET,ry+RESET,RESET/2d,over?UiTheme.ACCENT:UiTheme.BORDER);
            ui.glyph(dev.betterlitematica.runtime.UiGlyphArt.Kind.REFRESH,rx+3,ry+3,RESET-6,over?UiTheme.ACCENT:UiTheme.SECONDARY);
        }
    }
    private void drawView(int view,int x,int y,int size){
        var ui=IndependentUi.INSTANCE;ui.roundRect(x,y,x+size,y+size,UiTheme.CARD_RADIUS,UiTheme.SUNKEN);
        if(image!=null)image.drawRegion((view%2)*256,(view/2)*256,256,x+INSET,y+INSET,size-INSET*2,size-INSET*2);
        ui.roundFrame(x,y,x+size,y+size,UiTheme.CARD_RADIUS,UiTheme.BORDER);
    }
    @Override public void appendNarrations(NarrationMessageBuilder builder){}
    @Override public void close(){cancelCapture();active=false;requested=null;displayedCamera=null;requestedFrame=appliedFrame=0;appliedDetailed=false;orbitSide=0;if(orbit!=null){orbit.close();orbit=null;}if(image!=null){image.close();image=null;}if(orbitImage!=null){orbitImage.close();orbitImage=null;}}
}
