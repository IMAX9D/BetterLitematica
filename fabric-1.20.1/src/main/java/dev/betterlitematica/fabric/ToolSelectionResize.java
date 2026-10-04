package dev.betterlitematica.fabric;

import dev.betterlitematica.core.*;
import java.util.function.Function;

/** Incremental six-face grow/trim. The caller owns world identity and publishes only result(). */
final class ToolSelectionResize {
    static final int MAX_ROUNDS=256;
    private static final int[] SCAN_AXES={0,2,1};
    private final AreaSelection original;private final SelectionBox source;private final boolean grow;
    private final boolean[] forward=new boolean[3];
    private final int[] min=new int[3],max=new int[3],faceMin=new int[3],faceMax=new int[3],at=new int[3];
    private int round,face,empty;private boolean started,faceStarted,moved,done,waiting;private AreaSelection result;
    ToolSelectionResize(AreaSelection original,boolean grow){
        this.original=original;this.source=original.current();this.grow=grow;
        int[] first={source.first().x(),source.first().y(),source.first().z()},second={source.second().x(),source.second().y(),source.second().z()};
        for(int i=0;i<3;i++){forward[i]=first[i]<=second[i];min[i]=Math.min(first[i],second[i]);max[i]=Math.max(first[i],second[i]);}
    }
    boolean tick(Function<Vec3i,Boolean> occupied,int maxCells,long deadline){
        waiting=false;
        while(!done&&maxCells>0&&System.nanoTime()<deadline){
            if(!started)beginRound();if(!faceStarted)beginFace();
            Boolean present=occupied.apply(new Vec3i(at[0],at[1],at[2]));maxCells--;
            if(present==null){waiting=true;return false;}
            if(present){finishFace(false);continue;}
            if(!advanceFace())finishFace(true);
        }
        return done;
    }
    AreaSelection result(){if(!done)throw new IllegalStateException("选区扫描尚未完成");return result;}
    String status(){return done?(round==MAX_ROUNDS?"选区调整达到扫描上限":"选区调整完成"):waiting?"选区调整等待区块":"调整选区 · "+round+" / "+MAX_ROUNDS;}
    private void beginRound(){
        if(grow){int[] low=new int[3],high=new int[3];for(int i=0;i<3;i++){low[i]=Math.subtractExact(min[i],1);high[i]=Math.addExact(max[i],1);}
            new SelectionBox(source.name(),new Vec3i(low[0],low[1],low[2]),new Vec3i(high[0],high[1],high[2])).region();
            System.arraycopy(low,0,min,0,3);System.arraycopy(high,0,max,0,3);
        }
        round++;face=empty=0;moved=false;started=true;
    }
    private void beginFace(){
        System.arraycopy(min,0,faceMin,0,3);System.arraycopy(max,0,faceMax,0,3);int axis=face/2;
        faceMin[axis]=faceMax[axis]=(face&1)==0?min[axis]:max[axis];System.arraycopy(faceMin,0,at,0,3);faceStarted=true;
    }
    private boolean advanceFace(){
        // X first, then Z, then Y. Fixed face axis has a one-cell extent.
        for(int axis:SCAN_AXES){if(at[axis]<faceMax[axis]){at[axis]++;return true;}at[axis]=faceMin[axis];}return false;
    }
    private void finishFace(boolean isEmpty){
        if(isEmpty){empty++;int axis=face/2;if(min[axis]<max[axis]){if((face&1)==0)min[axis]++;else max[axis]--;moved=true;}}
        faceStarted=false;
        if(++face<6)return;
        if(round==MAX_ROUNDS||(grow?empty==6:empty==0||!moved)){
            int[] first=new int[3],second=new int[3];for(int i=0;i<3;i++){first[i]=forward[i]?min[i]:max[i];second[i]=forward[i]?max[i]:min[i];}
            result=original.put(new SelectionBox(source.name(),new Vec3i(first[0],first[1],first[2]),new Vec3i(second[0],second[1],second[2])));done=true;
        }else started=false;
    }
}
