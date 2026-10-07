package dev.betterlitematica.core;

/** An isolated preview camera. Pan is measured in whole viewport widths, right/down positive. */
public record PreviewCamera(double yaw,double pitch,double zoom,double panX,double panY) {
    public static final double MIN_ZOOM=.5,MAX_ZOOM=16,MAX_PAN=16;
    public static final double MAX_PITCH=Math.PI/2-.03;
    public static final PreviewCamera DEFAULT=new PreviewCamera(Math.PI/4,Math.atan(1/Math.sqrt(2)),1,0,0);
    public PreviewCamera {
        if(!Double.isFinite(yaw)||!Double.isFinite(pitch)||!Double.isFinite(zoom)||!Double.isFinite(panX)||!Double.isFinite(panY)
            ||Math.abs(pitch)>MAX_PITCH||zoom<MIN_ZOOM||zoom>MAX_ZOOM||Math.abs(panX)>MAX_PAN||Math.abs(panY)>MAX_PAN)throw new IllegalArgumentException("Invalid preview camera");
    }
    public PreviewCamera rotate(double dx,double dy,double viewportWidth){
        finite(dx,dy,viewportWidth);double sensitivity=Math.PI/viewportWidth;
        return new PreviewCamera(Math.IEEEremainder(yaw-dx*sensitivity,2*Math.PI),clamp(pitch+dy*sensitivity,-MAX_PITCH,MAX_PITCH),zoom,panX,panY);
    }
    public PreviewCamera pan(double dx,double dy,double viewportWidth){
        finite(dx,dy,viewportWidth);
        return new PreviewCamera(yaw,pitch,zoom,clamp(panX+dx/viewportWidth,-MAX_PAN,MAX_PAN),clamp(panY+dy/viewportWidth,-MAX_PAN,MAX_PAN));
    }
    /** Anchor coordinates are relative to the viewport center, normalized by the full width. */
    public PreviewCamera scroll(double amount,double anchorX,double anchorY){
        if(!Double.isFinite(amount)||!Double.isFinite(anchorX)||!Double.isFinite(anchorY))throw new IllegalArgumentException("Invalid preview zoom");
        double next=clamp(zoom*Math.pow(1.18,clamp(amount,-64,64)),MIN_ZOOM,MAX_ZOOM);
        if(next==zoom)return this;
        double ratio=next/zoom;
        return new PreviewCamera(yaw,pitch,next,clamp(anchorX-(anchorX-panX)*ratio,-MAX_PAN,MAX_PAN),clamp(anchorY-(anchorY-panY)*ratio,-MAX_PAN,MAX_PAN));
    }
    private static void finite(double x,double y,double width){if(!Double.isFinite(x)||!Double.isFinite(y)||!Double.isFinite(width)||width<=0)throw new IllegalArgumentException("Invalid preview delta");}
    private static double clamp(double value,double min,double max){return Math.max(min,Math.min(max,value));}
}
