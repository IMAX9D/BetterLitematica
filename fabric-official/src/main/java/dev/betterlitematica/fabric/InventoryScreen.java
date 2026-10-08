package dev.betterlitematica.fabric;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;

final class InventoryScreen extends MenuScreen {
    private EditBox tool;
    InventoryScreen(Screen parent,ProjectionController controller){super("取料与工具","",parent,controller,false);}
    @Override protected void buildMenu(){var settings=controller.options();
        tool=fieldAt("工具物品",tool==null?settings.toolItem:tool.getValue(),left,0,innerWidth-80,4096);
        buttonAt("保存",left+innerWidth-72,14,72,()->{ToolItemSpec.validate(tool.getValue());settings.toolItem=tool.getValue().strip();controller.saveOptions();},true,true);
        caption("保护快捷栏",left,62,innerWidth);int width=(innerWidth-8*6)/9;
        for(int i=0;i<9;i++){int slot=i;boolean locked=(settings.protectedHotbar&(1<<slot))!=0;buttonAt(Integer.toString(i+1),left+i*(width+6),86,width,()->{settings.protectedHotbar^=1<<slot;controller.saveOptions();refresh();},true,locked);}
    }
}
