package dev.betterlitematica.fabric;
import net.minecraft.client.gui.screen.Screen;
/** A persistent explanation remains available after the one-time chat notice. */
final class CompatibilityScreen extends MenuScreen {
    CompatibilityScreen(Screen parent,ProjectionController controller){super("模组共存","",parent,controller,true);}
    @Override protected void buildMenu(){
        caption("接管不会改写其他模组的配置文件；移除本模组后恢复其原功能。",left,0,innerWidth);
        var mods=CompatibilityNotice.detected();int row=34;
        if(mods.isEmpty())caption("未检测到被接管的可选模组。",left,row,innerWidth);
        for(var entry:mods.entrySet()){caption(entry.getKey(),left,row,innerWidth);caption(entry.getValue(),left,row+20,innerWidth);row+=58;}
        caption("Litematica 和独立打印机 的打印接管持续生效。",left,row+16,innerWidth);
        caption("Tweakeroo：停止或暂停施工后恢复；独立破基岩启用期间同样临时拦截。",left,row+38,innerWidth);
    }
}
