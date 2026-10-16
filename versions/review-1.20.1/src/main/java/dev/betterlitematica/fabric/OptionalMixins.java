package dev.betterlitematica.fabric;
import java.util.*;
import net.fabricmc.loader.api.FabricLoader;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.*;
public final class OptionalMixins implements IMixinConfigPlugin {
    private boolean terrainOptimizations;
    public void onLoad(String mixinPackage){
        var loader=FabricLoader.getInstance();
        // Freeze the group decision: accessors and their consumers must never be partially applied.
        terrainOptimizations=!Boolean.getBoolean("betterlitematica.disableTerrainOptimizations")
                &&!loader.isModLoaded("sodium")&&!loader.isModLoaded("iris")&&!loader.isModLoaded("canvas")
                &&!loader.isModLoaded("vulkanmod")&&!loader.isModLoaded("optifabric");
    }
    public String getRefMapperConfig(){return null;}
    public boolean shouldApplyMixin(String target,String mixin){
        var loader=FabricLoader.getInstance();
        // Ownership is atomic: graph/list/frustum hooks, lazy handles, workers, uploads and drawing
        // must be enabled together. External renderers retain their complete terrain backend.
        if(mixin.substring(mixin.lastIndexOf('.')+1).startsWith("Terrain"))
            return terrainOptimizations;
        // Optional targets can arrive in JVM internal or binary class-name form.
        target=target.replace('/','.');
        if(mixin.endsWith(".ExternalMinerMixin"))return loader.isModLoaded(target.startsWith("me.z7087.")?"blockminer":"bedrockminer");
        if(mixin.contains(".compat.")){
            if(target.startsWith("fi.dy.masa.litematica."))return loader.isModLoaded("litematica");
            if(target.startsWith("fi.dy.masa.tweakeroo."))return loader.isModLoaded("tweakeroo");
            if(target.startsWith("me.aleksilassila.litematica.printer."))return loader.isModLoaded("litematica-printer");
            return false;
        }
        return true;
    }
    public void acceptTargets(Set<String> mine,Set<String> others){}
    public List<String> getMixins(){return null;}
    public void preApply(String target,ClassNode node,String mixin,IMixinInfo info){}
    public void postApply(String target,ClassNode node,String mixin,IMixinInfo info){}
}
