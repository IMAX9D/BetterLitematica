package dev.betterlitematica.fabric;

import dev.betterlitematica.fabric.mixin.MultipartModelAccessor;
import dev.betterlitematica.fabric.mixin.WeightedModelAccessor;
import net.minecraft.block.BlockState;
import net.minecraft.client.render.model.*;
import net.minecraft.util.math.Direction;
import java.util.Random;
import java.nio.ByteOrder;
import java.util.*;
import java.util.function.IntUnaryOperator;

/** Render-thread snapshot of vanilla geometry. Workers never call a game's model, predicates or tint callbacks. */
final class FrozenModel {
    static final int MAX_QUADS=512;
    static final class Quad {
        // Four vertices, each XYZ/UV as raw floats followed by a pre-tinted RGBA word.
        final int[] data=new int[24];
        Quad(BakedQuad quad,IntUnaryOperator colors){
            int[] raw=quad.getVertexData();int stride=raw.length/4;
            if(stride<6||raw.length%4!=0)throw new IllegalArgumentException("Invalid vertex format");
            int tint=quad.hasColor()?colors.applyAsInt(quad.getColorIndex()):0xffffff;
            float shade=quad.getFace()==Direction.UP?1f:quad.getFace()==Direction.DOWN?.6f:quad.getFace().getAxis()==Direction.Axis.X?.8f:.9f;
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
    interface Node {void append(int side,Random random,List<Quad> out);boolean randomized();}
    record Simple(Quad[][] sides) implements Node {
        public void append(int side,Random random,List<Quad> out){appendAll(out,sides[side]);}
        public boolean randomized(){return false;}
    }
    record Choice(Node[] models,int[] weights,int total) implements Node {
        public void append(int side,Random random,List<Quad> out){
            // Match vanilla, including the signed MIN_VALUE corner case and the child's RNG state.
            int pick=Math.abs((int)random.nextLong())%total;
            for(int i=0;i<models.length;i++){pick-=weights[i];if(pick<0){models[i].append(side,random,out);return;}}
        }
        public boolean randomized(){return true;}
    }
    record Parts(Node[] models) implements Node {
        public void append(int side,Random random,List<Quad> out){long seed=random.nextLong();for(var child:models){random.setSeed(seed);child.append(side,random,out);}}
        public boolean randomized(){return true;}
    }
    private final Node root;
    private FrozenModel(Node root){this.root=root;}
    boolean randomized(){return root.randomized();}
    void append(int side,Random random,List<Quad> out){root.append(side,random,out);}
    static void appendAll(List<Quad> out,Quad[] quads){if(quads.length>MAX_QUADS-out.size())throw new IllegalArgumentException("Model exceeds quad budget");Collections.addAll(out,quads);}
    /** Unknown/custom model implementations retain the render-thread path rather than guessing their thread safety. */
    static FrozenModel capture(BakedModel model,BlockState state,IntUnaryOperator colors){
        try{return new FrozenModel(new Capture(state,colors).node(model,0));}catch(UnsupportedOperationException unsupported){return null;}
    }
    private static final class Capture {
        final BlockState state;final IntUnaryOperator colors;final Map<BakedModel,Node> known=new IdentityHashMap<>();
        final Map<BakedQuad,Quad> quads=new IdentityHashMap<>();int count;
        Capture(BlockState state,IntUnaryOperator colors){this.state=state;this.colors=colors;}
        Node node(BakedModel model,int depth){
            if(depth>16)throw new UnsupportedOperationException();var cached=known.get(model);if(cached!=null)return cached;
            Node result;
            if(model.getClass()==BasicBakedModel.class){
                Quad[][] faces=new Quad[7][];var random=new Random(0);
                for(int side=0;side<7;side++){
                    var list=model.getQuads(state,side==6?null:Direction.values()[side],random);if((count+=list.size())>8192)throw new UnsupportedOperationException();
                    faces[side]=new Quad[list.size()];for(int i=0;i<list.size();i++)faces[side][i]=quads.computeIfAbsent(list.get(i),q->new Quad(q,colors));
                }
                result=new Simple(faces);
            }else if(model.getClass()==WeightedBakedModel.class&&model instanceof WeightedModelAccessor access){
                var list=access.betterlitematica$models();Node[] nodes=new Node[list.size()];int[] weights=new int[list.size()];int total=0;
                for(int i=0;i<nodes.length;i++){nodes[i]=node(list.get(i).getData(),depth+1);weights[i]=list.get(i).getWeight().getValue();total=Math.addExact(total,weights[i]);}
                if(total<=0)throw new UnsupportedOperationException();result=new Choice(nodes,weights,total);
            }else if(model.getClass()==MultipartBakedModel.class&&model instanceof MultipartModelAccessor access){
                var parts=new ArrayList<Node>();for(var part:access.betterlitematica$components())if(part.getLeft().test(state))parts.add(node(part.getRight(),depth+1));
                if(parts.stream().noneMatch(Node::randomized)){
                    Quad[][] faces=new Quad[7][];for(int side=0;side<7;side++){var list=new ArrayList<Quad>();for(var child:parts)child.append(side,null,list);faces[side]=list.toArray(Quad[]::new);}result=new Simple(faces);
                }else result=new Parts(parts.toArray(Node[]::new));
            }else throw new UnsupportedOperationException();
            known.put(model,result);return result;
        }
    }
}
