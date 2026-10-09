package dev.betterlitematica.fabric;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
/** Small boundary for the client GUI/camera ownership change in 26.2. */
public final class ClientUi {
 private ClientUi(){}
 public static void message(Minecraft client,net.minecraft.network.chat.Component text,boolean overlay){if(overlay)client.gui.setOverlayMessage(text,false);else client.player.sendSystemMessage(text);}
 public static void subtitles(Minecraft client){client.gui.extractDeferredSubtitles();}
 public static Screen screen(Minecraft client){return client.screen;}
 public static void setScreen(Minecraft client,Screen screen){client.setScreen(screen);}
 public static void setHidden(Minecraft client,boolean hidden){client.options.hideGui=hidden;}
 public static boolean hidden(Minecraft client){return client.options.hideGui;}
 public static net.minecraft.client.Camera camera(Minecraft client){return client.gameRenderer.getMainCamera();}
 public static com.mojang.blaze3d.pipeline.RenderTarget target(Minecraft client){return client.getMainRenderTarget();}
 public static net.minecraft.client.renderer.state.GameRenderState renderState(Minecraft client){return client.gameRenderer.getGameRenderState();}
 public static net.minecraft.client.gui.components.ChatComponent chat(Minecraft client){return client.gui.getChat();}
}
