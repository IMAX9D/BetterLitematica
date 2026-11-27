package dev.betterlitematica.fabric;

import java.util.function.BooleanSupplier;
import net.minecraft.client.MinecraftClient;

public final class BackgroundPrinting {
    private static BooleanSupplier running=()->false;
    private BackgroundPrinting(){}
    static void bind(BooleanSupplier value){running=value;}
    public static boolean active(){var c=MinecraftClient.getInstance();return c.world!=null&&c.player!=null&&!c.player.isDead()&&running.getAsBoolean();}
}
