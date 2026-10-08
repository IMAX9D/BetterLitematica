package dev.betterlitematica.selftest;

import dev.betterlitematica.runtime.StatusBadgeArt;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.HashSet;

/** Public raster contracts; the contact-sheet harness additionally checks premultiplied reduction. */
public final class StatusBadgeChecks {
    private static int checks;
    private StatusBadgeChecks() {}
    public static int run(){
        checks=0;
        for(int diameter:new int[]{12,13,16,24,31,32,48,64,96,127,128,192}){
            int pad=StatusBadgeArt.padding(diameter),edge=diameter+pad*2;
            var states=new EnumMap<StatusBadgeArt.Kind,StatusBadgeArt.Raster>(StatusBadgeArt.Kind.class);
            for(var kind:StatusBadgeArt.Kind.values()){
                var image=StatusBadgeArt.raster(kind,diameter);states.put(kind,image);
                check(image.width()==edge&&image.height()==edge&&image.argb().length==edge*edge,"Nominal diameter includes only documented padding");
                check(image.bytes()==4L*edge*edge&&image.bytes()<=230400,"Bounded final raster byte weight");
                boolean clearBorder=true,canonicalAlpha=true,fringe=true;int partial=0,green=0;var alphas=new HashSet<Integer>();
                for(int y=0;y<edge;y++)for(int x=0;x<edge;x++){
                    int p=image.argb()[x+y*edge],a=p>>>24,r=(p>>>16)&255,g=(p>>>8)&255,b=p&255;
                    if(x==0||y==0||x==edge-1||y==edge-1)clearBorder&=p==0;
                    if(a==0){canonicalAlpha&=p==0;continue;}
                    if(a<255)partial++;
                    if(a>220&&g>r+35&&g>b+20)green++;
                    double radius=Math.hypot(x+.5-(pad+diameter*.5),y+.5-(pad+diameter*.5));
                    if(Math.abs(radius-diameter*.5)<1.5){alphas.add(a);if(a>8)fringe&=r>20&&g>30&&b>40;}
                }
                check(clearBorder,"Shadow never clips at bitmap boundary");
                check(canonicalAlpha,"Zero-alpha RGB is canonical transparent");
                check(fringe,"Circle and shadow have no black fringe");
                check(alphas.size()>=8,"Circle boundary contains fractional coverage");
                check(partial>diameter*diameter*.55,"Porcelain surface remains translucent");
                check(kind==StatusBadgeArt.Kind.PRINTER_ON?green>0:green==0,"Only running printer is bright green");
            }
            check(!Arrays.equals(states.get(StatusBadgeArt.Kind.EYE_OPEN).argb(),states.get(StatusBadgeArt.Kind.EYE_CLOSED).argb()),"Hidden eye has a distinct slash");
            int[] running=states.get(StatusBadgeArt.Kind.PRINTER_ON).argb(),stopped=states.get(StatusBadgeArt.Kind.PRINTER_OFF).argb();
            check(!Arrays.equals(running,stopped),"Printer state changes its color");
            boolean equalCoverage=true;for(int n=0;n<running.length;n++)equalCoverage&=(running[n]>>>24)==(stopped[n]>>>24);
            check(equalCoverage,"Printer changes color without changing geometry or coverage");
        }
        for(int diameter:new int[]{12,24,48,192}){
            int edge=diameter+StatusBadgeArt.padding(diameter)*2;
            var lightEye=StatusBadgeArt.raster(StatusBadgeArt.Kind.EYE_OPEN,diameter,false);var darkEye=StatusBadgeArt.raster(StatusBadgeArt.Kind.EYE_OPEN,diameter,true);
            check(darkEye.width()==edge&&darkEye.height()==edge,"Dark badges keep the same footprint and hit target");
            check(!Arrays.equals(lightEye.argb(),darkEye.argb()),"Dark badges use a graphite surface");
            int[] on=StatusBadgeArt.raster(StatusBadgeArt.Kind.PRINTER_ON,diameter,true).argb(),off=StatusBadgeArt.raster(StatusBadgeArt.Kind.PRINTER_OFF,diameter,true).argb();
            int green=0;for(int p:on){int a=p>>>24,r=(p>>>16)&255,g=(p>>>8)&255,b=p&255;if(a>220&&g>r+35&&g>b+20)green++;}
            int offGreen=0;for(int p:off){int a=p>>>24,r=(p>>>16)&255,g=(p>>>8)&255,b=p&255;if(a>220&&g>r+35&&g>b+20)offGreen++;}
            check(green>0&&offGreen==0,"Only the running dark printer is green");
            boolean clear=true;for(int n=0;n<edge;n++)clear&=on[n]==0&&on[on.length-1-n]==0;check(clear,"Dark shadow stays inside its padding");
        }
        for(int diameter:new int[]{Integer.MIN_VALUE,-1,0,11,193,Integer.MAX_VALUE}){
            try{StatusBadgeArt.raster(StatusBadgeArt.Kind.EYE_OPEN,diameter);throw new AssertionError("Unsupported diameter accepted");}
            catch(IllegalArgumentException expected){checks++;}
        }
        try{StatusBadgeArt.raster(null,24);throw new AssertionError("Null kind accepted");}catch(NullPointerException expected){checks++;}
        check(StatusBadgeArt.padding(12)==2&&StatusBadgeArt.padding(24)==3&&StatusBadgeArt.padding(192)==24,"Padding contract remains stable");
        check(StatusBadgeArt.raster(StatusBadgeArt.Kind.EYE_OPEN,192).bytes()*4<1_048_576,"All four largest states fit the one MiB cache");
        return checks;
    }
    private static void check(boolean value,String message){checks++;if(!value)throw new AssertionError(message);}
    public static void main(String[] args){System.out.println("StatusBadgeChecks: "+run()+" checks passed");}
}
