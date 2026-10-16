package dev.betterlitematica.core;

/** Source provenance, retained independently of display text and the resolved block palette. */
public record ProjectionCompatibility(int formatVersion,int sourceDataVersion,int gameDataVersion,int skippedBlockTypes) {
    public static final ProjectionCompatibility NONE=new ProjectionCompatibility(0,0,0,0);
    public ProjectionCompatibility {
        if(formatVersion<0||sourceDataVersion<0||gameDataVersion<0||skippedBlockTypes<0||skippedBlockTypes>65536)
            throw new IllegalArgumentException("Invalid source compatibility information");
    }
    public boolean newerFormat(){return formatVersion>6;}
    public boolean newerGame(){return gameDataVersion>0&&sourceDataVersion>gameDataVersion;}
    public boolean incompatible(){return newerFormat()||newerGame()||skippedBlockTypes>0;}
    public String description(){
        if(!incompatible())return "";
        var text=new StringBuilder("不兼容的投影 · 已兼容加载");
        if(newerFormat())text.append("\n文件格式版本：").append(formatVersion);
        if(newerGame())text.append("\n来源游戏版本高于当前游戏");
        if(skippedBlockTypes>0)text.append("\n已跳过 ").append(skippedBlockTypes).append(" 种不支持的方块或状态");
        else text.append("\n已保留当前游戏能够识别的方块");
        return text.append("\n跳过的位置不参与显示、校验或施工；原文件保留").toString();
    }
}
