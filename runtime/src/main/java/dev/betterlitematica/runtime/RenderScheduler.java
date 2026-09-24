package dev.betterlitematica.runtime;

import java.util.List;

/** Fair section handoff within one global time and upload budget. */
public final class RenderScheduler {
    private RenderScheduler(){}
    public record Work(int uploaded,boolean progressed){public static final Work NONE=new Work(0,false);}
    public interface Worker {
        boolean building();
        Work buildFrame(long deadline,int uploadLimit);
    }
    public static int run(List<? extends Worker> workers,int start,int attempts,long deadline,int uploadBytes){
        if(workers.isEmpty())return 0;
        if(attempts<1||uploadBytes<0)throw new IllegalArgumentException();
        int next=Math.floorMod(start,workers.size()),idle=0;
        for(int turn=0;turn<attempts&&uploadBytes>0&&System.nanoTime()<deadline&&idle<workers.size();turn++){
            int owner=-1;
            for(int i=0;i<workers.size();i++)if(workers.get(i).building()){if(owner>=0)throw new IllegalStateException("Multiple unfinished render jobs");owner=i;}
            int index=owner>=0?owner:next;var worker=workers.get(index);var work=worker.buildFrame(deadline,uploadBytes);
            if(work.uploaded()<0||work.uploaded()>uploadBytes)throw new IllegalStateException("Render upload budget exceeded");
            uploadBytes-=work.uploaded();if(work.progressed())idle=0;else idle++;
            if(!worker.building())next=(index+1)%workers.size();
            else if(!work.progressed())break;
        }return next;
    }
}
