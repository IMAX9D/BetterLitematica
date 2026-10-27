package dev.betterlitematica.fabric;

import java.nio.ByteOrder;
import java.util.*;
import java.util.function.IntUnaryOperator;
import net.minecraft.client.render.model.*;
import dev.betterlitematica.fabric.mixin.MultipartModelAccessor;
import dev.betterlitematica.fabric.mixin.WeightedModelAccessor;
import net.minecraft.client.render.model.BakedQuad;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.random.Random;
import net.minecraft.block.BlockState;

/** Immutable model data captured during extraction; workers never invoke a model or tint callback. */
final class FrozenModel {
    static final int MAX_QUADS=512;
    static final class Quad {
        final int[] data=new int[24];
        Quad(BakedQuad quad,IntUnaryOperator colors){
            int[] raw=quad.vertexData();int stride=raw.length/4;
            if(stride<6||raw.length%4!=0)throw new IllegalArgumentException("Invalid vertex format");
            int tint=quad.hasTint()?colors.applyAsInt(quad.tintIndex()):0xffffff;
            float shade=quad.face()==Direction.UP?1f:quad.face()==Direction.DOWN?.6f:quad.face().getAxis()==Direction.Axis.X?.8f:.9f;
            for(int v=0;v<4;v++){
                int a=v*stride,b=v*6,c=raw[a+3];data[b]=raw[a];data[b+1]=raw[a+1];data[b+2]=raw[a+2];data[b+3]=raw[a+4];data[b+4]=raw[a+5];
                int r=(int)((c&255)/255f*((tint>>>16)&255)/255f*shade*255f);
                int g=(int)(((c>>>8)&255)/255f*((tint>>>8)&255)/255f*shade*255f);
                int blue=(int)(((c>>>16)&255)/255f*(tint&255)/255f*shade*255f);
                int rgba=(r&255)|((g&255)<<8)|((blue&255)<<16)|0xff000000;
                data[b+5]=ByteOrder.nativeOrder()==ByteOrder.LITTLE_ENDIAN?rgba:Integer.reverseBytes(rgba);
            }
        }
    }
    interface Node {void append(int face,Random random,List<Quad> output);boolean randomized();}
    record Simple(Quad[][] faces) implements Node {
        public void append(int face,Random random,List<Quad> output){appendAll(output,faces[face]);}
        public boolean randomized(){return false;}
    }
    record Choice(Node[] choices,int[] weights,int total) implements Node {
        public void append(int face,Random random,List<Quad> output){
            int selected=random.nextInt(total);
            for(int i=0;i<choices.length;i++){selected-=weights[i];if(selected<0){choices[i].append(face,random,output);return;}}
        }
        public boolean randomized(){return true;}
    }
    record Parts(Node[] children) implements Node {
        public void append(int face,Random random,List<Quad> output){long seed=random.nextLong();for(var child:children){random.setSeed(seed);child.append(face,random,output);}}
        public boolean randomized(){return true;}
    }
    private final Node root;
    private FrozenModel(Node root){this.root=root;}
    boolean randomized(){return root.randomized();}
    void append(int face,Random random,List<Quad> output){root.append(face,random,output);}
    static void appendAll(List<Quad> output,Quad[] values){if(values.length>MAX_QUADS-output.size())throw new IllegalArgumentException("模型面数超过预算");Collections.addAll(output,values);}
    static FrozenModel capture(BlockStateModel model,BlockState state,IntUnaryOperator colors){
        try{return new FrozenModel(new Capture(colors).node(model,0));}catch(UnsupportedOperationException unsupported){return null;}
    }
    private static final class Capture {
        final IntUnaryOperator colors;final Map<BlockStateModel,Node> nodes=new IdentityHashMap<>();
        final Map<BakedQuad,Quad> quads=new IdentityHashMap<>();int count;
        Capture(IntUnaryOperator colors){this.colors=colors;}
        Node node(BlockStateModel model,int depth){
            if(depth>16)throw new UnsupportedOperationException();var cached=nodes.get(model);if(cached!=null)return cached;
            Node result;
            if(model.getClass()==SimpleBlockStateModel.class){
                var parts=new ArrayList<BlockModelPart>();model.addParts(Random.create(0),parts);
                Quad[][] faces=new Quad[7][];
                for(int face=0;face<7;face++){
                    var values=new ArrayList<Quad>();
                    for(var part:parts)for(var quad:part.getQuads(face==6?null:Direction.values()[face])){
                        if(++count>8192)throw new UnsupportedOperationException();
                        values.add(quads.computeIfAbsent(quad,q->new Quad(q,colors)));
                    }
                    faces[face]=values.toArray(Quad[]::new);
                }
                result=new Simple(faces);
            }else if(model.getClass()==WeightedBlockStateModel.class){
                var list=((WeightedModelAccessor)model).betterlitematica$models().getEntries();Node[] choices=new Node[list.size()];int[] weights=new int[list.size()];int total=0;
                for(int i=0;i<list.size();i++){choices[i]=node(list.get(i).value(),depth+1);weights[i]=list.get(i).weight();total=Math.addExact(total,weights[i]);}
                if(total<=0)throw new UnsupportedOperationException();result=new Choice(choices,weights,total);
            }else if(model.getClass()==MultipartBlockStateModel.class){
                // Resolve its state predicates and lazy model list on this thread before publishing.
                model.addParts(Random.create(0),new ArrayList<>());
                var list=((MultipartModelAccessor)model).betterlitematica$components();var children=new ArrayList<Node>();for(var child:list)children.add(node(child,depth+1));
                if(children.stream().noneMatch(Node::randomized)){
                    Quad[][] faces=new Quad[7][];
                    for(int face=0;face<7;face++){var values=new ArrayList<Quad>();for(var child:children)child.append(face,null,values);faces[face]=values.toArray(Quad[]::new);}
                    result=new Simple(faces);
                }else result=new Parts(children.toArray(Node[]::new));
            }else throw new UnsupportedOperationException();
            nodes.put(model,result);return result;
        }
    }
}
