package com.retrievepanel.printer;

import java.util.*;
import net.fabricmc.loader.api.FabricLoader;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.*;

public final class PrinterMixinPlugin implements IMixinConfigPlugin {
    private boolean enabled;
    public void onLoad(String pkg){
        var loader=FabricLoader.getInstance();
        enabled=loader.getModContainer("betterlitematica").map(m->m.getMetadata().getVersion().getFriendlyString().equals("0.4.17")).orElse(false)
            &&loader.getModContainer("travelersbackpack").map(m->m.getMetadata().getVersion().getFriendlyString().equals("9.1.50")).orElse(false);
        // Do not load any Minecraft or BetterLitematica classes during mixin discovery.
    }
    public boolean shouldApplyMixin(String target,String mixin){
        if(!enabled)return false;
        if(mixin.endsWith("IpnAutoRefillMixin"))return FabricLoader.getInstance().getModContainer("inventoryprofilesnext")
            .map(m->m.getMetadata().getVersion().getFriendlyString().equals("1.10.20")).orElse(false);
        return true;
    }
    public String getRefMapperConfig(){return null;}
    public void acceptTargets(Set<String> mine,Set<String> others){}
    public List<String> getMixins(){return null;}
    public void preApply(String target,ClassNode node,String mixin,IMixinInfo info){}
    public void postApply(String target,ClassNode node,String mixin,IMixinInfo info){}
}
