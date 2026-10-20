package dev.betterlitematica.fabric;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.TextFieldWidget;

final class InventoryScreen extends MenuScreen {
    @Override protected boolean isSettingsPage(){return true;}
    private TextFieldWidget tool;
    InventoryScreen(Screen parent,ProjectionController controller){super("取料与工具","",parent,controller,false);}
    private void save(){ToolItemSpec.validate(tool.getText());controller.options().toolItem=tool.getText().strip();controller.saveOptions();draftEdited();}
    @Override protected boolean draftDirty(){return tool!=null&&!tool.getText().strip().equals(controller.options().toolItem);}
    @Override public boolean keyPressed(int key,int scan,int modifiers){if(submitField(key,tool)){controller.action(this::save);return true;}return super.keyPressed(key,scan,modifiers);}
    @Override protected void buildMenu(){var settings=controller.options();
        tool=setting(SettingId.INVENTORY_TOOL,fieldAt("工具物品",tool==null?settings.toolItem:tool.getText(),left,0,innerWidth-80,4096));tool.setChangedListener(v->draftEdited());
        settingAction(buttonAt("保存",left+innerWidth-72,14,72,this::save,true,true));
        caption("保护快捷栏",left,62,innerWidth);int width=(innerWidth-8*6)/9;
        for(int i=0;i<9;i++){int slot=i;boolean locked=(settings.protectedHotbar&(1<<slot))!=0;setting(SettingId.INVENTORY_HOTBAR,buttonAt(Integer.toString(i+1),left+i*(width+6),86,width,()->{settings.protectedHotbar^=1<<slot;controller.saveOptions();refresh();},true,locked));}
    }
}
