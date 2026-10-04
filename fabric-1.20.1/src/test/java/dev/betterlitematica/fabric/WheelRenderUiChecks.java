package dev.betterlitematica.fabric;

/** Actual hit geometry: three navigation entries and seven render choices plus two layer steps. */
public final class WheelRenderUiChecks {
    private static int checks;
    private static void check(boolean value,String text){checks++;if(!value)throw new AssertionError(text);}
    public static int run(){checks=0;
        for(int count:new int[]{3,4,9}){
            double cx=ModeWheelScreen.CX;
            for(int i=0;i<count;i++){
                double angle=-Math.PI/2+i*Math.PI*2/count;
                for(double radius:new double[]{60,94,128})check(ModeWheelScreen.sectorAt(cx+Math.cos(angle)*radius,200+Math.sin(angle)*radius,count,cx)==i,"Rendered sector center and inner/outer content are clickable");
            }
            check(ModeWheelScreen.sectorAt(cx,200,count,cx)==-1,"Center return is never interpreted as a sector");
            check(ModeWheelScreen.sectorAt(cx,60,count,cx)==-1,"Outside wheel does not activate a mode");
        }
        for(int x:new int[]{452,468,500,574,590})for(int y:new int[]{130,154,168,211,242})check(ModeWheelScreen.sectorAt(x,y,9,300)==-1,"Editor fields cannot activate a ring sector");
        check(ModeWheelScreen.sectorAt(300,106,3)==0,"Existing top navigation still opens work modes");
        check(ModeWheelScreen.sectorAt(300,106,4)==0,"Existing work-mode print target is unchanged");
        return checks;
    }
}
