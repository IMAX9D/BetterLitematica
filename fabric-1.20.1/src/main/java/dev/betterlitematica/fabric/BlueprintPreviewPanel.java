package dev.betterlitematica.fabric;

import dev.betterlitematica.core.PlacementTransform;
import dev.betterlitematica.core.Vec3i;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.widget.ClickableWidget;
import net.minecraft.client.gui.screen.narration.NarrationMessageBuilder;
import net.minecraft.text.Text;

/** One owned atlas, displayed as a large orbit with three thumbnails or as an orbit alone. */
final class BlueprintPreviewPanel extends ClickableWidget implements AutoCloseable {
    private static final PlacementTransform IDENTITY=new PlacementTransform(Vec3i.ZERO,0,false,false);
    private static final int GAP=8;
    private static final double DEFAULT_YAW=Math.PI/4,DEFAULT_PITCH=Math.atan(1/Math.sqrt(2));
    private OverlayPreview image;
    private FilePreviews.Orbit orbit;
    private final ProjectionController controller;
    private final OverlayLabel name;
    private final boolean gallery;
    private PlacementTransform transform=IDENTITY,displayedTransform;
    private String status="";
    private boolean dragging,moved;private double yaw=DEFAULT_YAW,pitch=DEFAULT_PITCH;
    private long requestedFrame,appliedFrame;
    BlueprintPreviewPanel(ProjectionController controller,int x,int width,int height){this(controller,x,width,height,true);}
    BlueprintPreviewPanel(ProjectionController controller,int x,int width,int height,boolean gallery){super(x,0,width,height,Text.empty());this.controller=controller;this.gallery=gallery;active=false;name=new OverlayLabel(x,width,"");}
    void selected(String value){close();name.setMessage(Text.literal(value));status=value.isEmpty()?"":"生成中…";}
    void failed(){close();status="预览失败";}
    void images(FilePreviews.Preview preview){
        var value=preview.images();
        int side=value.side();var views=value.views();
        if(side!=256||views.size()!=4||views.stream().anyMatch(p->p==null||p.length!=side*side))throw new IllegalArgumentException("无效预览图");
        // Keep the existing cached views; assemble a single image once per selection.
        int combinedSide=side*2;int[] pixels=new int[combinedSide*combinedSide];
        for(int i=0;i<4;i++)for(int y=0;y<side;y++)System.arraycopy(views.get(i),y*side,pixels,((i/2)*side+y)*combinedSide+(i%2)*side,side);
        close();
        try{image=new OverlayPreview(getX(),imageSize());image.pixels(pixels);orbit=controller.previewOrbit(preview.model());if(gallery){yaw=DEFAULT_YAW;pitch=DEFAULT_PITCH;}requestedFrame=appliedFrame=0;displayedTransform=transform.equals(IDENTITY)&&yaw==DEFAULT_YAW&&pitch==DEFAULT_PITCH?IDENTITY:null;active=true;status="";if(displayedTransform==null)request(false);}
        catch(RuntimeException e){close();throw e;}
    }
    void orientation(PlacementTransform value){
        var next=new PlacementTransform(Vec3i.ZERO,value.quarterTurns(),value.mirrorX(),value.mirrorZ());
        if(transform.equals(next))return;transform=next;if(orbit!=null)request(dragging);
    }
    private void request(boolean coarse){requestedFrame=orbit.request(yaw,pitch,coarse,transform);}
    private int imageSize(){return Math.max(24,Math.min(height-(gallery?24:0),gallery?(3*width-GAP)/4:width));}
    private int thumbnailSize(){return Math.max(1,(imageSize()-2*GAP)/3);}
    private int imageLeft(){return getX()+(width-imageSize()-(gallery?GAP+thumbnailSize():0))/2;}
    private int imageTop(){return getY()+(gallery?24:Math.max(0,(height-imageSize())/2));}
    private boolean orbitHit(double x,double y){int left=imageLeft(),top=imageTop(),size=imageSize();return visible&&active&&orbit!=null&&x>=left&&x<left+size&&y>=top&&y<top+size;}
    @Override public boolean mouseClicked(double x,double y,int button){if(button!=0||!orbitHit(x,y))return false;dragging=true;moved=false;setFocused(true);return true;}
    @Override public boolean mouseDragged(double x,double y,int button,double dx,double dy){
        if(!dragging||button!=0||orbit==null)return false;
        if(dx!=0||dy!=0){double sensitivity=Math.PI/Math.max(24,imageSize()-6);yaw=Math.IEEEremainder(yaw-dx*sensitivity,2*Math.PI);pitch=Math.max(-Math.PI/2+.03,Math.min(Math.PI/2-.03,pitch+dy*sensitivity));moved=true;request(true);}
        return true;
    }
    @Override public boolean mouseReleased(double x,double y,int button){return releaseDrag(button);}
    boolean releaseDrag(int button){if(!dragging||button!=0)return false;finishDrag();return true;}
    private void finishDrag(){dragging=false;if(moved&&orbit!=null)request(false);moved=false;}
    @Override public void renderButton(DrawContext context,int mouseX,int mouseY,float delta){
        if(name.getMessage().getString().isEmpty())return;
        var ui=IndependentUi.INSTANCE;if(gallery){name.setY(getY());name.render(context,mouseX,mouseY,delta);}
        if(dragging&&!MinecraftClient.getInstance().isWindowFocused())finishDrag();
        if(orbit!=null){var frame=orbit.poll();if(frame!=null&&frame.transform().equals(transform)){context.draw();image.region(256,256,256,frame.pixels());displayedTransform=transform;appliedFrame=frame.sequence();}}
        int size=imageSize(),x=imageLeft(),y=imageTop();
        drawView(3,x,y,size,image!=null&&displayedTransform!=null);
        if(gallery){int thumb=thumbnailSize();for(int i=0;i<3;i++)drawView(i,x+size+GAP,y+i*(thumb+GAP),thumb,image!=null);}
        if(!status.isEmpty()){
            ui.centered(status,x,y+(size-19)/2.0,size,19,status.equals("预览失败")?UiTheme.ERROR:UiTheme.MUTED);
        }
    }
    private void drawView(int view,int x,int y,int size,boolean ready){
        var ui=IndependentUi.INSTANCE;ui.roundRect(x,y,x+size,y+size,UiTheme.CARD_RADIUS,UiTheme.SUNKEN);
        if(ready)image.drawRegion((view%2)*256,(view/2)*256,256,x+3,y+3,size-6,size-6);
        ui.roundFrame(x,y,x+size,y+size,UiTheme.CARD_RADIUS,UiTheme.BORDER);
    }
    @Override protected void appendClickableNarrations(NarrationMessageBuilder builder){}
    @Override public void close(){dragging=false;moved=false;active=false;displayedTransform=null;if(orbit!=null){orbit.close();orbit=null;}if(image!=null){image.close();image=null;}}
}
