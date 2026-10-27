package com.retrievepanel.printer;

/** Receipt accounting is fed exclusively by S2C packets, never predicted inventory. */
public final class ReceiptBatch {
    private final boolean[] confirmed;
    private int pending,age;
    public ReceiptBatch(int size) {if(size<1||size>36)throw new IllegalArgumentException();confirmed=new boolean[size];pending=size;}
    public void receive(int index,boolean matches) {if(index>=0&&index<confirmed.length&&matches&&!confirmed[index]){confirmed[index]=true;pending--;}}
    public boolean complete(){return pending==0;}
    public int pending(){return pending;}
    public boolean timedOut(int limit){return ++age>limit;}
}
