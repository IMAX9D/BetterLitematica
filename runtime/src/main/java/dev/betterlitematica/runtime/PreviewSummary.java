package dev.betterlitematica.runtime;
import dev.betterlitematica.core.Vec3i;
import java.math.BigInteger;
/** Full bounding-box volume, distinct from the cached non-air block count. */
public record PreviewSummary(String volume,String blocks) {
    public static PreviewSummary of(Vec3i dimensions,long blocks,int quarterTurns){
        int x=dimensions.x(),y=dimensions.y(),z=dimensions.z();if((quarterTurns&1)!=0){int swap=x;x=z;z=swap;}
        BigInteger volume=BigInteger.valueOf(x).multiply(BigInteger.valueOf(y)).multiply(BigInteger.valueOf(z));
        return new PreviewSummary("体积："+x+"×"+y+"×"+z+" 共"+volume+"格","共"+blocks+"方块");
    }
}
