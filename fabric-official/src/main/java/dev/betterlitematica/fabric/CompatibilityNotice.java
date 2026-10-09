package dev.betterlitematica.fabric;

import java.nio.file.*;
import java.util.*;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

/** Announce actual optional takeovers once per installed mod and policy revision. Never edits other mods' settings. */
final class CompatibilityNotice {
    private static boolean checked;
    static Map<String,String> detected(){
        var result=new LinkedHashMap<String,String>();var loader=FabricLoader.getInstance();
        if(loader.isModLoaded("litematica"))result.put("Litematica","投影渲染、输入、快捷键和简易放置由本模组接管");
        if(loader.isModLoaded("litematica-printer"))result.put("Litematica Printer","打印逻辑和快捷键由本模组接管");
        if(loader.isModLoaded("tweakeroo"))result.put("Tweakeroo","仅本模组施工运行或执行放置动作期间，暂时拦截冲突的放置、点击和换物品功能");
        return result;
    }
    static void tick(Minecraft client){
        if(checked||client.player==null)return;checked=true;var mods=detected();if(mods.isEmpty())return;
        Path file=FabricLoader.getInstance().getConfigDir().resolve("betterlitematica-compat-notices.properties");var seen=new Properties();
        try{if(Files.isRegularFile(file))try(var reader=Files.newBufferedReader(file)){seen.load(reader);}}catch(Exception e){BetterLitematicaClient.LOGGER.warn("Could not read compatibility notices",e);}
        boolean changed=false;
        for(var entry:mods.entrySet())if(!"review-policy-1".equals(seen.getProperty(entry.getKey()))){
            ClientUi.message(client,Component.literal("[BetterLitematica 共存提示] "+entry.getKey()+"："+entry.getValue()+"。详情见设置中的“模组共存”。"),false);
            seen.setProperty(entry.getKey(),"review-policy-1");changed=true;
        }
        if(changed)try{Files.createDirectories(file.getParent());try(var writer=Files.newBufferedWriter(file)){seen.store(writer,"Acknowledged compatibility notices");}}catch(Exception e){BetterLitematicaClient.LOGGER.warn("Could not persist compatibility notices",e);}
    }
}
