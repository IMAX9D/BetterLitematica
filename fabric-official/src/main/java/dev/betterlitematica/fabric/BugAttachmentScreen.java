package dev.betterlitematica.fabric;

import net.minecraft.client.gui.screens.Screen;

/** Read-only attachment preview. It never sends data or changes the report's consent choices. */
final class BugAttachmentScreen extends MenuScreen {
    private final String text;
    BugAttachmentScreen(Screen parent,ProjectionController controller,String text){super("附带内容","聊天、密钥、地址和个人路径已清理。",parent,controller,true);this.text=text;}
    @Override protected int preferredHeight(){return 380;}
    @Override protected void buildMenu(){addBody(new BugTextArea(left,innerWidth,Math.max(80,bodyBottom-bodyTop-12),text,false,160*1024,value->{}),6);}
    @Override public boolean keyPressed(int key,int scan,int modifiers){if(getFocused() instanceof BugTextArea area&&area.keyPressed(key,scan,modifiers))return true;return super.keyPressed(key,scan,modifiers);}
}
