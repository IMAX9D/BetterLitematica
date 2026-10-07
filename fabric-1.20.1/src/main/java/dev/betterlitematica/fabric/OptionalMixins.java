package dev.betterlitematica.fabric;
import java.util.*;
import net.fabricmc.loader.api.FabricLoader;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.*;
public final class OptionalMixins implements IMixinConfigPlugin {
    public void onLoad(String mixinPackage){}
    public String getRefMapperConfig(){return null;}
    public boolean shouldApplyMixin(String target,String mixin){
        var loader=FabricLoader.getInstance();
        if(mixin.endsWith(".ExternalMinerMixin"))return loader.isModLoaded(target.startsWith("me.z7087.")?"blockminer":"bedrockminer");
        if(mixin.contains(".compat.")){
            if(target.startsWith("fi.dy.masa.litematica."))return loader.isModLoaded("litematica");
            if(target.startsWith("fi.dy.masa.tweakeroo."))return loader.isModLoaded("tweakeroo");
            if(target.startsWith("me.aleksilassila.litematica.printer."))return loader.isModLoaded("litematica-printer");
            if(target.startsWith("com.tom.storagemod."))return loader.isModLoaded("toms_storage");
            return false;
        }
        return true;
    }
    public void acceptTargets(Set<String> mine,Set<String> others){}
    public List<String> getMixins(){return null;}
    public void preApply(String target,ClassNode node,String mixin,IMixinInfo info){}
    public void postApply(String target,ClassNode node,String mixin,IMixinInfo info){}
}
