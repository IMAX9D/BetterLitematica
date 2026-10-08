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
    static void swingUse(LocalPlayer player,InteractionHand hand,InteractionResult result){if(result instanceof InteractionResult.Success success&&success.swingSource()==InteractionResult.SwingSource.PREDICTED)player.swing(hand,player.getItemInHand(hand).getInteractAnimation(),false);}
    static void swingBreak(LocalPlayer player,InteractionHand hand){player.swing(hand,player.getItemInHand(hand).getAttackAnimation(),false);player.connection.send(net.minecraft.network.protocol.game.ServerboundPunchPacket.INSTANCE);}
    static boolean compostable(Item item){return new ItemStack(item).has(net.minecraft.core.component.DataComponents.COMPOSTABLE);}
    static boolean blocksMotion(BlockState state){return state.isSolid();}
    static SignText signText(SignBlockEntity sign,boolean front){return sign.getText(front?net.minecraft.world.level.block.entity.SignTextSlot.FRONT:net.minecraft.world.level.block.entity.SignTextSlot.BACK);}
    static String signLine(SignText text,int line,boolean filtered){return text.getMessages(filtered).get(line).getString();}
    static net.minecraft.core.HolderLookup.Provider defaultRegistries(){return net.minecraft.data.registries.VanillaRegistries.createWorldLookup();}
    static java.util.stream.Stream<ItemStack> containerItems(net.minecraft.world.item.component.ItemContainerContents contents){return contents.itemCopies();}
    static ServerboundSignUpdatePacket signUpdate(BlockPos pos,boolean front,String[] lines){return new ServerboundSignUpdatePacket(pos,List.of(lines),front?net.minecraft.world.level.block.entity.SignTextSlot.FRONT:net.minecraft.world.level.block.entity.SignTextSlot.BACK);}
}
