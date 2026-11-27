package dev.betterlitematica.fabric;

import java.util.function.BooleanSupplier;
import net.minecraft.client.Minecraft;

public final class BackgroundPrinting {
    private static BooleanSupplier running=()->false;
    private BackgroundPrinting(){}
    static void bind(BooleanSupplier value){running=value;}
    public static boolean active(){var c=Minecraft.getInstance();return c.level!=null&&c.player!=null&&!c.player.isDeadOrDying()&&running.getAsBoolean();}
}
