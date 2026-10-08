package dev.betterlitematica.fabric;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.entity.SignText;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.network.protocol.game.ServerboundSignUpdatePacket;
import java.util.List;
/** Small gameplay API boundary; each version keeps vanilla item and interaction semantics. */
final class VersionGameplay {
    static void swingUse(LocalPlayer player,InteractionHand hand,InteractionResult result){if(result instanceof InteractionResult.Success success&&success.swingSource()==InteractionResult.SwingSource.CLIENT)player.swing(hand);}
    static void swingBreak(LocalPlayer player,InteractionHand hand){player.swing(hand);}
    static boolean compostable(Item item){return net.minecraft.world.level.block.ComposterBlock.COMPOSTABLES.containsKey(item);}
    static boolean blocksMotion(BlockState state){return state.blocksMotion();}
    static SignText signText(SignBlockEntity sign,boolean front){return sign.getText(front);}
    static String signLine(SignText text,int line,boolean filtered){return text.getMessage(line,filtered).getString();}
    static net.minecraft.core.HolderLookup.Provider defaultRegistries(){return net.minecraft.data.registries.VanillaRegistries.createLookup();}
    static java.util.stream.Stream<ItemStack> containerItems(net.minecraft.world.item.component.ItemContainerContents contents){return contents.allItemsCopyStream();}
    static ServerboundSignUpdatePacket signUpdate(BlockPos pos,boolean front,String[] lines){return new ServerboundSignUpdatePacket(pos,front,lines[0],lines[1],lines[2],lines[3]);}
}
