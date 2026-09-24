package dev.betterlitematica.fabric;

/** Initialize Fabric's transformed classloader, but never call the Minecraft game entrypoint. */
public final class HeadlessAdapterLauncher {
    public static void main(String[] args)throws Exception{
        System.setProperty("fabric.development","true");
        var knot=new net.fabricmc.loader.impl.launch.knot.Knot(net.fabricmc.api.EnvType.CLIENT);
        var directory=java.nio.file.Path.of("build","headless-check").toAbsolutePath();java.nio.file.Files.createDirectories(directory);
        ClassLoader loader=knot.init(new String[]{"--gameDir",directory.toString()});
        Thread.currentThread().setContextClassLoader(loader);
        Class.forName("net.minecraft.client.MinecraftClient",false,loader);
        Class.forName("net.minecraft.client.Mouse",false,loader);
        Class.forName("net.minecraft.server.network.ServerPlayNetworkHandler",false,loader);
        Class.forName("net.minecraft.item.BlockItem",false,loader);
        Class.forName("net.minecraft.client.network.ClientPlayNetworkHandler",false,loader);
        loader.loadClass("dev.betterlitematica.fabric.AdapterChecks").getMethod("main",String[].class).invoke(null,(Object)new String[0]);
    }
}
