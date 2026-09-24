package dev.betterlitematica.fabric;
final class ModeWheelGeometryChecks {
    static int run(){int count=0;double[][] centers={{300,106},{394,200},{300,294},{206,200}};
        for(int i=0;i<centers.length;i++){if(ModeWheelScreen.sectorAt(centers[i][0],centers[i][1],4)!=i)throw new AssertionError("Wheel cardinal target");count++;}
        for(double[] p:new double[][]{{300,200},{300,150},{450,200},{-50,200},{380,120}}){if(ModeWheelScreen.sectorAt(p[0],p[1],4)!=-1)throw new AssertionError("Center, outside and separator must not toggle");count++;}
        if(ModeWheelScreen.sectorAt(300,100,2)!=0||ModeWheelScreen.sectorAt(300,300,2)!=1)throw new AssertionError("Root ring separates modes from rendering");return count+1;
    }
}
