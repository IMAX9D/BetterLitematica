package dev.betterlitematica.core;

import java.util.Arrays;

/** Immutable expected raw states for source cells represented or omitted as already complete. */
public final class RenderedCells {
    private final short[] cells;
    private final int[] states;
    private RenderedCells(short[] cells,int[] states){this.cells=cells;this.states=states;}
    private static void cell(int cell){if(cell<0||cell>=4096)throw new IllegalArgumentException("Source cell out of range");}
    public int state(int cell){cell(cell);int index=Arrays.binarySearch(cells,(short)cell);return index<0?-1:states[index];}
    public int size(){return cells.length;}
    public long estimatedBytes(){return 80L+cells.length*2L+states.length*4L;}
    public static final class Builder {
        private final int[] states=new int[4096];
        private int count;
        public Builder(){Arrays.fill(states,-1);}
        public void record(int cell,int rawStateId){
            cell(cell);if(rawStateId<0)throw new IllegalArgumentException("Negative raw state ID");
            if(states[cell]<0)count++;states[cell]=rawStateId;
        }
        public RenderedCells build(){
            short[] cells=new short[count];int[] values=new int[count];int at=0;
            for(int cell=0;cell<states.length;cell++)if(states[cell]>=0){cells[at]=(short)cell;values[at++]=states[cell];}
            return new RenderedCells(cells,values);
        }
        public void clear(){Arrays.fill(states,-1);count=0;}
    }
}
