package dev.betterlitematica.core;

/** Physical-pixel safe area, independent of a host application's logical GUI scale. */
public record UiViewport(int pixelWidth,int pixelHeight,int inputWidth,int inputHeight,double guiScale,
                         double scale,double offsetX,double offsetY) {
    public static final int WIDTH=600,HEIGHT=400;
    public static UiViewport fit(int pixelsX,int pixelsY,int inputsX,int inputsY,double guiScale){
        if(pixelsX<1||pixelsY<1||inputsX<1||inputsY<1||!Double.isFinite(guiScale)||guiScale<=0)throw new IllegalArgumentException("Invalid viewport");
        double scale=Math.min(pixelsX*0.90/WIDTH,pixelsY*0.90/HEIGHT);
        return new UiViewport(pixelsX,pixelsY,inputsX,inputsY,guiScale,scale,(pixelsX-WIDTH*scale)/2,(pixelsY-HEIGHT*scale)/2);
    }
    public double pixelX(double x){return offsetX+x*scale;}
    public double pixelY(double y){return offsetY+y*scale;}
    public double localPixelX(double x){return (x-offsetX)/scale;}
    public double localPixelY(double y){return (y-offsetY)/scale;}
    public double inputX(double x){return localPixelX(x*pixelWidth/inputWidth);}
    public double inputY(double y){return localPixelY(y*pixelHeight/inputHeight);}
    public double deltaX(double x){return x*pixelWidth/inputWidth/scale;}
    public double deltaY(double y){return y*pixelHeight/inputHeight/scale;}
    public double drawScale(){return scale/guiScale;}
    public double drawX(){return offsetX/guiScale;}
    public double drawY(){return offsetY/guiScale;}
    public record Clip(int x,int y,int width,int height){}
    /** OpenGL bottom-left origin; rounded outwards, clamped to the framebuffer. */
    public Clip clip(double left,double top,double right,double bottom){
        int x1=Math.max(0,Math.min(pixelWidth,(int)Math.floor(pixelX(left))));
        int y1=Math.max(0,Math.min(pixelHeight,(int)Math.floor(pixelY(top))));
        int x2=Math.max(x1,Math.min(pixelWidth,(int)Math.ceil(pixelX(right))));
        int y2=Math.max(y1,Math.min(pixelHeight,(int)Math.ceil(pixelY(bottom))));
        return new Clip(x1,pixelHeight-y2,x2-x1,y2-y1);
    }
}
