package dev.betterlitematica.fabric.mixin;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.betterlitematica.fabric.*;
import net.minecraft.client.renderer.SectionOcclusionGraph;
import net.minecraft.client.renderer.chunk.*;
import net.minecraft.client.renderer.culling.Frustum;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
/** Exact matrix/graph identity and section coordinates, with a four-block conservative margin. */
@Mixin(SectionOcclusionGraph.class)
public abstract class TerrainFrustumCacheMixin {
    @Shadow @Final private AtomicReference<?> currentGraph;
    @Unique private final ArrayList<SectionRenderDispatcher.RenderSection> betterlitematica$sections=new ArrayList<>();
    @Unique private long[] betterlitematica$positions=new long[0];
    @Unique private Object betterlitematica$graph;
    @Unique private long betterlitematica$revision;
    @Unique private final Matrix4f betterlitematica$matrix=new Matrix4f();
    @Unique private double betterlitematica$x,betterlitematica$y,betterlitematica$z;
    @WrapMethod(method="addSectionsInFrustum")
    private void betterlitematica$collect(Frustum view,List<SectionRenderDispatcher.RenderSection> output,List<SectionRenderDispatcher.RenderSection> nearby,Operation<Void> original){
        if(view.getClass()!=Frustum.class){betterlitematica$graph=null;original.call(view,output,nearby);return;}
        var access=(TerrainView)view;long revision=TerrainNativeBudget.revision();Object graph=currentGraph.get();
        double x=access.betterlitematica$x(),y=access.betterlitematica$y(),z=access.betterlitematica$z();
        boolean reuse=graph!=null&&graph==betterlitematica$graph&&revision!=Long.MIN_VALUE&&revision==betterlitematica$revision
            &&betterlitematica$matrix.equals(access.betterlitematica$matrix())
            &&Math.abs(x-betterlitematica$x)<=4&&Math.abs(y-betterlitematica$y)<=4&&Math.abs(z-betterlitematica$z)<=4
            &&Math.floor(x/16)==Math.floor(betterlitematica$x/16)&&Math.floor(y/16)==Math.floor(betterlitematica$y/16)&&Math.floor(z/16)==Math.floor(betterlitematica$z/16);
        if(reuse)for(int i=0;i<betterlitematica$sections.size();i++)if(betterlitematica$sections.get(i).getSectionNode()!=betterlitematica$positions[i]){reuse=false;break;}
        if(reuse){
            output.addAll(betterlitematica$sections);TerrainNativeBudget.cacheReuses++;
            for(var section:betterlitematica$sections){var p=section.getRenderOrigin();
                if(Math.abs(p.getX()+8-x)<=48&&Math.abs(p.getY()+8-y)<=48&&Math.abs(p.getZ()+8-z)<=48)nearby.add(section);
            }
        }else{
            betterlitematica$graph=null;int first=output.size();TerrainFrustumScope.begin();
            try{original.call(view,output,nearby);}finally{TerrainFrustumScope.end();}
            betterlitematica$sections.clear();betterlitematica$sections.addAll(output.subList(first,output.size()));
            if(betterlitematica$positions.length<betterlitematica$sections.size())betterlitematica$positions=new long[betterlitematica$sections.size()];
            for(int i=0;i<betterlitematica$sections.size();i++)betterlitematica$positions[i]=betterlitematica$sections.get(i).getSectionNode();
            if(revision!=Long.MIN_VALUE&&revision==TerrainNativeBudget.revision()&&graph==currentGraph.get()){
                betterlitematica$graph=graph;betterlitematica$revision=revision;betterlitematica$matrix.set(access.betterlitematica$matrix());
                betterlitematica$x=x;betterlitematica$y=y;betterlitematica$z=z;
            }
        }
        boolean holes=false;for(var section:betterlitematica$sections)holes|=section.getSectionMesh()==CompiledSectionMesh.UNCOMPILED;TerrainNativeBudget.visibleHoles=holes;
    }
    @Inject(method="waitAndReset",at=@At("HEAD"))private void betterlitematica$clear(CallbackInfo ci){betterlitematica$graph=null;betterlitematica$sections.clear();}
}
