package dev.betterlitematica.core;

import java.util.*;

public final class PrinterDiscovery {
    public static final int KNOWN=1,WANTED_AIR=2,ACTUAL_AIR=4,REPLACEABLE=8,SAME_BLOCK=16,ADJUSTABLE=32,FLUID=64,FLUID_SOURCE=128,BEDROCK=256,SKIP=512;
    public record Policy(boolean print,boolean fill,boolean fluid,boolean bedrock,boolean breakWrong,boolean breakExtra,boolean breakState,boolean flowing) {}
    public static final class Page {
        private final long generation;private final long[] positions;private final int[] expected,actual,flags,scope,fill;private final int size;
        public Page(long generation,long[] positions,int[] expected,int[] actual,int[] flags,int[] scope,int[] fill){
            this(generation,positions,expected,actual,flags,scope,fill,positions.length,true);
        }
        private Page(long generation,long[] positions,int[] expected,int[] actual,int[] flags,int[] scope,int[] fill,int size,boolean copy){
            int n=positions.length;
            if(size<0||size>n||n>512||expected.length!=n||actual.length!=n||flags.length!=n||scope.length!=n||fill.length!=n)throw new IllegalArgumentException("Snapshot page dimensions");
            this.size=size;
            this.generation=generation;
            this.positions=copy?Arrays.copyOf(positions,size):positions;
            this.expected=copy?Arrays.copyOf(expected,size):expected;
            this.actual=copy?Arrays.copyOf(actual,size):actual;
            this.flags=copy?Arrays.copyOf(flags,size):flags;
            this.scope=copy?Arrays.copyOf(scope,size):scope;
            this.fill=copy?Arrays.copyOf(fill,size):fill;
        }
    }

    public static final class Buffer {
        public final long[] positions=new long[512];
        public final int[] expected=new int[512],actual=new int[512],flags=new int[512],scopes=new int[512],fill=new int[512];
        public Page freeze(long generation,int size){return new Page(generation,positions,expected,actual,flags,scopes,fill,size,true);}
        public Result searchNow(long generation,int size,Policy policy){
            return search(new Page(generation,positions,expected,actual,flags,scopes,fill,size,false),policy);
        }
    }
    public record Result(long generation,List<PrinterQueue.Job> jobs,int compared,int matched,int unknown) {public Result{jobs=List.copyOf(jobs);}}
    private PrinterDiscovery(){}
    public static Result search(Page page,Policy policy){
        var jobs=new ArrayList<PrinterQueue.Job>();int compared=0,matched=0,unknown=0;
        for(int i=0;i<page.size;i++){
            if(Thread.currentThread().isInterrupted())break;
            int f=page.flags[i],scope=page.scope[i];if((f&KNOWN)==0){unknown++;continue;}
            if((policy.print||policy.breakWrong||policy.breakExtra||policy.breakState)&&(scope&1)!=0){
                compared++;if(page.expected[i]==page.actual[i])matched++;
                else if((f&SKIP)==0){
                    PrinterQueue.Kind kind=null;
                    if(policy.print&&(f&ADJUSTABLE)!=0)kind=PrinterQueue.Kind.ADJUST;
                    else if(policy.print&&(f&WANTED_AIR)==0&&(f&REPLACEABLE)!=0)kind=PrinterQueue.Kind.PLACE;
                    else if((f&ACTUAL_AIR)==0&&((f&WANTED_AIR)!=0?policy.breakExtra:((f&SAME_BLOCK)!=0?policy.breakState:policy.breakWrong)))kind=PrinterQueue.Kind.BREAK;

                    if(kind==PrinterQueue.Kind.BREAK&&policy.bedrock&&(scope&8)!=0&&(f&BEDROCK)!=0)kind=null;
                    if(kind!=null)jobs.add(job(page,i,page.expected[i],kind));
                }
            }
            if(policy.fill&&(scope&2)!=0&&(f&REPLACEABLE)!=0&&page.fill[i]>=0&&page.actual[i]!=page.fill[i])jobs.add(job(page,i,page.fill[i],PrinterQueue.Kind.FILL));
            if(policy.fluid&&(scope&4)!=0&&(f&FLUID)!=0&&(policy.flowing||(f&FLUID_SOURCE)!=0)&&page.fill[i]>=0)jobs.add(job(page,i,page.fill[i],PrinterQueue.Kind.FLUID));
            if(policy.bedrock&&(scope&8)!=0&&(f&BEDROCK)!=0)jobs.add(job(page,i,page.actual[i],PrinterQueue.Kind.BEDROCK));
        }
        return new Result(page.generation,jobs,compared,matched,unknown);
    }
    private static PrinterQueue.Job job(Page p,int i,int expected,PrinterQueue.Kind kind){return new PrinterQueue.Job(p.generation,p.positions[i],expected,p.actual[i],kind);}
}
