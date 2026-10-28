package dev.betterlitematica.checks.mixin;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.server.integrated.IntegratedServerLoader;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;
@Mixin(IntegratedServerLoader.class)
public interface LoaderInvoker {
    @Invoker("start") void startFixture(Screen parent,String name,boolean safeMode,boolean backup);
}
