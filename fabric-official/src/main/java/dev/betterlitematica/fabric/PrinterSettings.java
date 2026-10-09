package dev.betterlitematica.fabric;
import com.google.gson.*;
import dev.betterlitematica.core.PrinterRange;
import java.util.*;
/** Preferences only. The live work switch and target never survive a connection. */
final class PrinterSettings {
    enum Scope { PROJECTION,SELECTION,BELOW,ABOVE }
    enum HighlightStyle { OUTLINE,FILLED,BOTH }
    boolean print=true,fill,fluid,bedrock,airPlace=true,fallingCheck=true,replace=true,skipWaterlogged;
    boolean stripLogs,noteTuning=true,bonemeal,composter,breakWrong,breakExtra,breakState,flowing=true;
    boolean forceSneak;
    PrinterSupply.Source supply=PrinterSupply.Source.NONE;
    boolean iceWater,coralSubstitute,safeObserver=true,containerFill=true,highlights=true,highlightOnTop=true;
    HighlightStyle highlightStyle=HighlightStyle.OUTLINE;
    int highlightMillis=1500,highlightRange=8,highlightLimit=0,placeColor=0xaa57c8d6,adjustColor=0xaae6cf70,breakColor=0xaad780ce,failedColor=0xaaf06464;
    boolean hud=true,missingHud=false,reverseX,reverseY,reverseZ;
    int interval=1,perTick=1,cooldown=3,breakInterval=1,breakPerTick=1,threads=2;
    int workBudgetMillis=16;
    double range;
    PrinterRange.Shape shape=PrinterRange.Shape.SPHERE;
    String order="XZY",fillState="minecraft:stone";
    Scope printScope=Scope.PROJECTION,fillScope=Scope.SELECTION,fluidScope=Scope.SELECTION,bedrockScope=Scope.SELECTION;
    List<String> skip=new ArrayList<>(),replaceable=new ArrayList<>(List.of("minecraft:snow","minecraft:water","minecraft:lava","minecraft:grass","minecraft:bubble_column"));
    List<String> fluids=new ArrayList<>(List.of("minecraft:water","minecraft:lava")),compostItems=new ArrayList<>(List.of("minecraft:wheat_seeds"));
    transient long revision;
    private transient JsonObject preserved=new JsonObject();
    void validate(){
        bounded("间隔",interval,0,20);bounded("位置冷却",cooldown,0,64);bounded("每 tick 上限",perTick,0,256);bounded("破坏间隔",breakInterval,0,20);bounded("每 tick 破坏上限",breakPerTick,1,256);bounded("搜索线程",threads,1,8);
        bounded("工作预算",workBudgetMillis,1,16);
        bounded("高亮时长",highlightMillis,100,10000);bounded("高亮范围",highlightRange,1,32);bounded("高亮上限",highlightLimit,0,1000000);if(highlightStyle==null)throw new IllegalArgumentException("高亮样式无效");
        if(!Double.isFinite(range)||range<0||range>256)throw new IllegalArgumentException("距离：0–256");
        if(supply==null)throw new IllegalArgumentException("补给来源无效");
        if(shape==null||printScope==null||fillScope==null||fluidScope==null||bedrockScope==null||!Set.of("XYZ","XZY","YXZ","YZX","ZXY","ZYX").contains(order))throw new IllegalArgumentException("打印机范围设置无效");
        if(fillState==null||fillState.length()>256)throw new IllegalArgumentException("填充方块无效");
        dev.betterlitematica.core.BlockStateSpec.parse(fillState);
        for(var list:List.of(skip,replaceable,fluids,compostItems)){if(list.size()>128)throw new IllegalArgumentException("过滤列表过长");for(String item:list)if(item==null||item.isBlank()||item.length()>128)throw new IllegalArgumentException("过滤条件无效");}
    }
    private static void bounded(String label,int value,int min,int max){if(value<min||value>max)throw new IllegalArgumentException(label+"："+min+"–"+max);}
    static PrinterSettings read(JsonElement value){if(value==null)return new PrinterSettings();var settings=new Gson().fromJson(value,PrinterSettings.class);var root=value.getAsJsonObject();if(!root.has("highlightOnTop")&&(root.has("highlightThrough")||root.has("errorHighlightOnTop")))settings.highlightOnTop=(root.has("highlightThrough")&&root.get("highlightThrough").getAsBoolean())||(root.has("errorHighlightOnTop")&&root.get("errorHighlightOnTop").getAsBoolean());settings.preserved=root.deepCopy();settings.validate();return settings;}
    JsonObject snapshot(){validate();var root=preserved.deepCopy();root.remove("highlightThrough");root.remove("errorHighlightOnTop");new Gson().toJsonTree(this).getAsJsonObject().entrySet().forEach(e->root.add(e.getKey(),e.getValue()));return root;}
}
