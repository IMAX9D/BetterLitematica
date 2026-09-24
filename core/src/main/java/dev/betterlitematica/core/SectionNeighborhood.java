package dev.betterlitematica.core;

/** A pinned center plus six decoded face neighbors. Null means known absent, never pending IO. */
public record SectionNeighborhood(PackedSection center,PackedSection west,PackedSection east,
                                  PackedSection down,PackedSection up,PackedSection north,PackedSection south) {
    public SectionNeighborhood {java.util.Objects.requireNonNull(center);}
    public int globalId(int x,int y,int z){
        int outside=(x<0||x>15?1:0)+(y<0||y>15?1:0)+(z<0||z>15?1:0);
        if(outside>1||x< -1||x>16||y< -1||y>16||z< -1||z>16)throw new IndexOutOfBoundsException("Only face neighbors are pinned");
        PackedSection source=x<0?west:x>15?east:y<0?down:y>15?up:z<0?north:z>15?south:center;
        return source==null?0:source.globalId(x&15,y&15,z&15);
    }
}
