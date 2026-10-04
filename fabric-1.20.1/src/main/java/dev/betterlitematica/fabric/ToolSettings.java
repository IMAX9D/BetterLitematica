package dev.betterlitematica.fabric;
import dev.betterlitematica.core.*;
final class ToolSettings {
    String primary="minecraft:stone",secondary="minecraft:air";
    boolean executeRequiresTool=true;
    boolean expandSelection=false,deleteEntities=true,deletePlacement=false,pasteEntities=true,pasteNbt=true;
    ReplaceRule pasteRule=ReplaceRule.ALL;int distance=200;
    void validate(){BlockStateSpec.parse(primary);BlockStateSpec.parse(secondary);if(pasteRule==null||distance<1||distance>200)throw new IllegalArgumentException("工具范围必须为 1–200");}
}
