package dev.betterlitematica.fabric;

import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.item.ItemRenderState;
import net.minecraft.client.texture.Sprite;
import net.minecraft.item.ModelTransformationMode;
import net.minecraft.util.math.random.Random;

/** Resolve the light item's component-dependent model, including its selected light level. */
final class LightProjectionSprite {
    private LightProjectionSprite(){}
    static Sprite sprite(MinecraftClient client,BlockState state){
        var rendered=new ItemRenderState();
        client.getItemModelManager().update(rendered,LightProjectionMarker.stack(state),ModelTransformationMode.GUI,client.world,null,0);
        return rendered.getParticleSprite(Random.create(0));
    }
}
