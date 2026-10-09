package dev.betterlitematica.fabric;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
/** Small boundary for the client GUI/camera ownership change in 26.2. */
public final class ClientUi {
 private ClientUi(){}
 public static void message(Minecraft client,net.minecraft.network.chat.Component text,boolean overlay){if(overlay)client.gui.hud.setOverlayMessage(text,false);else client.player.sendSystemMessage(text);}
 public static void subtitles(Minecraft client){client.gui.hud.extractDeferredSubtitles();}
 public static Screen screen(Minecraft client){return client.gui.screen();}
 public static void setScreen(Minecraft client,Screen screen){client.gui.setScreen(screen);}
 public static void setHidden(Minecraft client,boolean hidden){if(client.gui.hud.isHidden()!=hidden)client.gui.hud.toggle();}
 public static boolean hidden(Minecraft client){return client.gui.hud.isHidden();}
 public static net.minecraft.client.Camera camera(Minecraft client){return client.gameRenderer.mainCamera();}
 public static com.mojang.blaze3d.pipeline.RenderTarget target(Minecraft client){return client.gameRenderer.mainRenderTarget();}
 public static net.minecraft.client.renderer.state.GameRenderState renderState(Minecraft client){return client.gameRenderer.gameRenderState();}
 public static net.minecraft.client.gui.components.ChatComponent chat(Minecraft client){return client.gui.hud.getChat();}
}
