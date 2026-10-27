package com.retrievepanel.printer;

import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;
import java.nio.file.*;

public final class PrinterRefillConfig {
    public boolean enabled=true;
    public int maxStacksPerBatch=32;
    public int reserveEmptySlots=2;
    public int timeoutTicks=100;
    public int materialKinds=8;
    public boolean trashSurplus=true;
    public int swapRecoveryTicks=20;
    public boolean coordinateIpn=true;
    public boolean ipnNoticeShown=false;
    // Stock policy replaces the old exact-count window. Legacy reserve/materialKinds remain readable for rollback.
    public int refillEmptyDivisor=4;
    public int rotationDivisor=8;
    public int admissionRounds=6;
    public int residentProtectionRounds=6;
    public int demandGraceRounds=16;
    public static PrinterRefillConfig current=new PrinterRefillConfig();
    private static Path path(){return FabricLoader.getInstance().getConfigDir().resolve("backpack-retrieve-printer.json");}
    public static void load(){
        try {if(Files.exists(path())){var value=new GsonBuilder().create().fromJson(Files.readString(path()),PrinterRefillConfig.class);if(value!=null)current=value;}
            current.maxStacksPerBatch=Math.max(1,Math.min(36,current.maxStacksPerBatch));
            current.reserveEmptySlots=Math.max(0,Math.min(8,current.reserveEmptySlots));
            current.timeoutTicks=Math.max(20,Math.min(400,current.timeoutTicks));
            current.materialKinds=Math.max(1,Math.min(16,current.materialKinds));
            current.swapRecoveryTicks=Math.max(10,Math.min(80,current.swapRecoveryTicks));
            current.refillEmptyDivisor=Math.max(2,Math.min(8,current.refillEmptyDivisor));
            current.rotationDivisor=Math.max(4,Math.min(36,current.rotationDivisor));
            current.admissionRounds=Math.max(2,Math.min(64,current.admissionRounds));
            current.residentProtectionRounds=Math.max(2,Math.min(64,current.residentProtectionRounds));
            current.demandGraceRounds=Math.max(4,Math.min(128,current.demandGraceRounds));
        }catch(Exception e){current.enabled=false;System.err.println("[Backpack Printer] 配置读取失败，自动补货已关闭");}
    }
    public static void save() throws java.io.IOException {
        Files.createDirectories(path().getParent());
        Files.writeString(path(),new GsonBuilder().setPrettyPrinting().create().toJson(current));
    }
}
