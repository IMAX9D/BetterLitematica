package dev.betterlitematica.fabric;

import net.minecraft.client.gui.screens.Screen;
final class DraftRecoveryScreen extends MenuScreen {
    private net.minecraft.client.gui.components.Button separately;
    DraftRecoveryScreen(Screen parent,ProjectionController controller){super("编辑草稿","",parent,controller,false);}
    @Override protected int preferredHeight(){return 220;}
    @Override protected void buildMenu(){
        button("重试恢复",0,1,0,controller::retryDraftRecovery);
        separately=button("作为新摆放恢复",0,1,1,controller::recoverDraftSeparately);
        button("保留备份并结束恢复",0,1,3,controller::archiveDraftRecovery);
    }
    @Override protected void updateMenu(){if(!controller.hasDraftRecovery()){ClientUi.setScreen(minecraft,parent);return;}separately.active=controller.canRecoverDraftSeparately();}
    @Override protected String statusLine(){return controller.hasDraftRecovery()?controller.draftRecoveryStatus():"已处理";}
}
