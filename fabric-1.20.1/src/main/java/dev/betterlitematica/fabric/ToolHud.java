package dev.betterlitematica.fabric;

import dev.betterlitematica.core.BlockStateSpec;
import net.minecraft.client.MinecraftClient;
import net.minecraft.item.ItemStack;
import net.minecraft.util.math.Direction;
import java.util.*;

/** Bottom-left tool status, independent of the top-left construction/material cards. */
final class ToolHud {
    private record Block(ItemStack icon,String text,boolean air) {}
    private final Map<String,Block> blocks=new HashMap<>();
    private ToolHudData.Input previous;
    private ToolHudData data;
    private long epoch=Long.MIN_VALUE;

    boolean visible(MinecraftClient client,ProjectionController controller) {
        return ToolHudData.visible(client.player!=null,HudLayout.menuHidesHud(client.currentScreen),client.options.hudHidden,
            controller.toolRenderingEnabled(),controller.tool().held(),controller.tool().mode());
    }
    private void snapshot(ProjectionController controller) {
        var next=controller.tool().hudData();
        if(epoch==controller.sessionEpoch() && next.same(previous))return;
        epoch=controller.sessionEpoch();previous=next;data=ToolHudData.capture(next);
        blocks.keySet().removeIf(key->data.rows().stream().noneMatch(row->row.state().equals(key)));
        for(var row:data.rows())if(row.kind()==ToolHudData.Kind.BLOCK)blocks.computeIfAbsent(row.state(),ToolHud::block);
    }
    private double rowHeight(IndependentUi ui,ToolHudData.Row row) {return row.kind()==ToolHudData.Kind.BLOCK?Math.max(20,ui.lineHeight()+2):Math.max(13,ui.lineHeight()+2);}
    private double height(IndependentUi ui) {double height=30;for(var row:data.rows())height+=rowHeight(ui,row);return height;}
    double top(MinecraftClient client,IndependentUi ui,ProjectionController controller,HudLayout layout) {
        if(!visible(client,controller))return layout.toolBottom();snapshot(controller);
        double bottom=layout.toolBottom();
        if(hotbarSpace(client,layout)<110){
            var view=layout.viewport();
            bottom=Math.min(bottom,view.localPixelY(view.pixelHeight()-22*view.guiScale())-HudLayout.GAP);
        }
        return bottom-height(ui);
    }
    private static double hotbarSpace(MinecraftClient client,HudLayout layout){
        var view=layout.viewport();
        boolean leftOffhand=client.player.getMainArm()==net.minecraft.util.Arm.RIGHT&&!client.player.getOffHandStack().isEmpty();
        double hotbarLeft=view.pixelWidth()/2d-(91+(leftOffhand?29:0))*view.guiScale();
        return view.localPixelX(hotbarLeft)-layout.toolLeft()-HudLayout.GAP;
    }
    void draw(MinecraftClient client,IndependentUi ui,ProjectionController controller,HudLayout layout,double top) {
        if(!visible(client,controller))return;
        if(data==null)snapshot(controller);
        double labelWidth=26,width=142;
        for(var row:data.rows()) {
            var block=blocks.get(row.state());String text=block==null?row.text():block.text();
            width=Math.max(width,14+labelWidth+ui.measure(text)+3+(block==null?0:23));
        }
        width=Math.min(248,Math.min(width,(layout.right()-layout.left())*.46));
        // The card now shares the bottom edge with vanilla's hotbar, including its offhand slot.
        double besideHotbar=hotbarSpace(client,layout);
        if(besideHotbar>=110)width=Math.min(width,besideHotbar);
        double x=layout.toolLeft(),y=top,line=ui.lineHeight(),rowY=y+25;
        HudLayout.card(ui,x,y,width,height(ui));
        if(data.area())HudIcons.selection(ui,x+7,y+7,11,previous.expand(),UiTheme.ACCENT);
        else HudIcons.tool(ui,x+7,y+7,11,UiTheme.ACCENT);
        String title=data.mode().label()+(data.badge().isEmpty()?"":" · "+data.badge());
        ui.text(title,x+25,y+5,width-68,UiTheme.TEXT);
        String index=(data.mode().ordinal()+1)+" / "+ToolMode.values().length;
        ui.text(index,x+width-ui.measure(index)-8,y+5,ui.measure(index)+3,UiTheme.MUTED);
        ui.rect(x+7,y+22,x+width-7,y+22.5,UiTheme.DIVIDER);
        for(var row:data.rows()) {
            double rowHeight=rowHeight(ui,row),ty=rowY+(rowHeight-line)/2,tx=x+7+labelWidth;
            int color=row.selected()?UiTheme.FOCUS:UiTheme.SECONDARY;
            ui.text(row.label(),x+7,ty,labelWidth-3,row.selected()?UiTheme.FOCUS:UiTheme.MUTED);
            var block=blocks.get(row.state());
            if(block!=null) {
                if(!block.icon().isEmpty())ui.item(block.icon(),tx,rowY,19);
                else if(block.air())HudIcons.empty(ui,tx+3,rowY+3,12,UiTheme.MUTED);
                else HudIcons.warning(ui,tx+4,rowY+3,12,UiTheme.WARNING);
                tx+=23;
            }
            ui.text(block==null?row.text():block.text(),tx,ty,x+width-7-tx,row.kind()==ToolHudData.Kind.NAME?UiTheme.TEXT:color);
            rowY+=rowHeight;
        }
    }
    private static Block block(String spec) {
        try {
            var state=StateResolver1201.checked(BlockStateSpec.parse(spec));String text=state.getBlock().getName().getString();
            for(var entry:state.getEntries().entrySet())if(entry.getValue() instanceof Direction direction){
                text+=" · "+switch(direction){case NORTH->"北";case SOUTH->"南";case WEST->"西";case EAST->"东";case UP->"上";case DOWN->"下";};break;
            }
            for(var entry:state.getEntries().entrySet())if(entry.getValue() instanceof Direction.Axis axis){text+=" · "+axis.name();break;}
            return new Block(new ItemStack(state.getBlock().asItem()),text,state.isAir());
        }catch(RuntimeException invalid){return new Block(ItemStack.EMPTY,"无效方块",false);}
    }
}
