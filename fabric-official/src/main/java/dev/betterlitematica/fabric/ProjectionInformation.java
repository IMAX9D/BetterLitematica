package dev.betterlitematica.fabric;

import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.world.item.ItemStack;

/** Compact read-only details; the inventory/source snapshots are unchanged. */
final class ProjectionInformation {
    private record Panel(ProjectionInfoData.Side side,ItemStack icon,String name,List<String> lines,boolean more,int slots,int columns) {}
    private record Metrics(int lines,int columns,double lineHeight,double height,boolean more) {}
    private ProjectionInfoData.Snapshot data;
    private ProjectionController controller;
    private Panel projection,actual;
    private int updates;

    private Panel prepare(ProjectionInfoData.Side source){
        var side=ProjectionInventoryView.compact(source);
        var all=ProjectionInfoPanel.lines(side,controller.options().display.informationStates);
        // Block names and icons already identify ordinary blocks; retain properties and NBT.
        if(side.state()!=null&&!all.isEmpty())all=all.subList(1,all.size());
        int count=Math.min(8,all.size()),slots=Math.min(54,side.items().size()),columns=Math.max(1,Math.min(9,side.columns()));
        ItemStack icon=side.state()==null?ItemStack.EMPTY:new ItemStack(side.state().getBlock().asItem());
        String name=side.state()==null?"未加载":side.state().getBlock().getName().getString();
        return new Panel(side,icon,name,List.copyOf(all.subList(0,count)),count<all.size()||slots<side.items().size(),slots,columns);
    }

    void update(Minecraft client,ProjectionController controller){
        this.controller=controller;
        if(client.player==null||!controller.options().display.information){data=null;return;}
        var target=controller.target(12);if(target==null){data=null;return;}
        if(data==null||!data.position().equals(target.position())||++updates%3==0){
            data=ProjectionInfoData.capture(client,controller,target);projection=prepare(data.projection());actual=prepare(data.actual());
        }
    }

    private String coordinates(){var p=data.position();return p.x()+"   "+p.y()+"   "+p.z();}
    private double preferredWidth(IndependentUi ui,Panel panel,double max){
        double width=Math.max(78,ui.measure(panel.name())+49);
        for(String line:panel.lines())width=Math.max(width,ui.measure(line)+15);
        if(panel.slots()>0)width=Math.max(width,Math.min(panel.columns(),panel.slots())*22+12);
        return Math.min(max,width);
    }
    private double panelWidth(IndependentUi ui,HudLayout layout,boolean showActual){
        double width=Math.max(preferredWidth(ui,projection,layout.informationWidth()),ui.measure(coordinates())+31);
        if(showActual)width=Math.max(width,preferredWidth(ui,actual,layout.informationWidth()));
        return Math.min(layout.informationWidth(),width);
    }
    HudLayout arrange(IndependentUi ui,HudLayout layout){
        if(data==null||controller==null||!controller.options().display.information||!controller.options().display.informationWorld)return layout;
        double line=Math.max(12,ui.lineHeight()),available=layout.bottom()-layout.top()-line-18,width=panelWidth(ui,layout,true);
        var p=metrics(projection,width,line,available);var a=metrics(actual,width,line,available);
        return p.height()+a.height()+6>available?HudLayout.of(layout.viewport(),true):layout;
    }

    void draw(Minecraft client,IndependentUi ui,HudLayout layout){
        if(data==null||HudLayout.menuHidesHud(ClientUi.screen(client))||!controller.options().display.information)return;
        boolean showActual=controller.options().display.informationWorld;
        double panelWidth=panelWidth(ui,layout,showActual),line=Math.max(12,ui.lineHeight()),header=line+9;
        double maxHeight=layout.bottom()-layout.top()-header-8;
        var p=metrics(projection,panelWidth,line,maxHeight);var a=showActual?metrics(actual,panelWidth,line,maxHeight):null;
        boolean columns=showActual&&p.height()+a.height()+6>maxHeight;
        double width=columns?panelWidth*2+HudLayout.GAP:panelWidth,height=header+p.height()+6;
        if(showActual)height=header+(columns?Math.max(p.height(),a.height()):p.height()+a.height()+6)+6;
        double x=layout.right()-width,y=layout.top();HudLayout.card(ui,x,y,width,height);
        HudIcons.target(ui,x+6,y+7,10,UiTheme.MUTED);ui.text(coordinates(),x+22,y+5,width-28,UiTheme.SECONDARY);
        panel(ui,projection,p,true,x,y+header,panelWidth);
        if(showActual){
            double ax=columns?x+panelWidth+HudLayout.GAP:x,ay=columns?y+header:y+header+p.height()+6;
            if(columns)ui.rect(ax-HudLayout.GAP/2,y+header,ax-HudLayout.GAP/2+.5,y+height-6,UiTheme.DIVIDER);
            else ui.rect(x+6,ay-3,x+width-6,ay-2.5,UiTheme.DIVIDER);
            panel(ui,actual,a,false,ax,ay,panelWidth);
        }
    }

    private static Metrics metrics(Panel panel,double width,double line,double available){
        int columns=Math.max(1,Math.min(panel.columns(),(int)((width-12)/22)));
        double inventory=panel.slots()==0?0:4+Math.ceil(panel.slots()/(double)columns)*22;
        int count=panel.lines().size();boolean more=panel.more();
        double height=Math.max(20,line+2)+count*line+inventory+(more?line:0);
        while(height>available&&count>0){count--;more=true;height=Math.max(20,line+2)+count*line+inventory+line;}
        return new Metrics(count,columns,line,height,more);
    }

    private static void panel(IndependentUi ui,Panel panel,Metrics metrics,boolean projection,double x,double y,double width){
        if(projection)HudIcons.projection(ui,x+6,y+5,8,UiTheme.ACCENT);else HudIcons.block(ui,x+6,y+5,8,UiTheme.MUTED);
        if(!panel.icon().isEmpty())ui.item(panel.icon(),x+18,y,18);
        else HudIcons.empty(ui,x+20,y+3,12,UiTheme.MUTED);
        ui.text(panel.name(),x+40,y+3,width-46,UiTheme.TEXT);
        double row=y+Math.max(20,metrics.lineHeight()+2);
        for(int i=0;i<metrics.lines();i++){ui.text(panel.lines().get(i),x+6,row,width-12,UiTheme.SECONDARY);row+=metrics.lineHeight();}
        if(panel.slots()>0)row+=4;
        for(int i=0;i<panel.slots();i++){
            double sx=x+6+(i%metrics.columns())*22,sy=row+(i/metrics.columns())*22;
            ui.roundRect(sx,sy,sx+20,sy+20,3,UiTheme.OVERLAY_SURFACE);
            var item=panel.side().items().get(i);
            if(!item.isEmpty()){
                ui.item(item,sx,sy,20);
                if(item.getCount()>1){
                    String count=Integer.toString(item.getCount());double badge=Math.min(32,ui.measure(count)+4);
                    ui.roundRect(sx+21-badge,sy+11,sx+21,sy+22,3,UiTheme.PANEL);
                    ui.itemCount(item.getCount(),sx+22-badge,sy+10,badge-2);
                }
            }
        }
        if(metrics.more())ui.text("…",x+6,y+metrics.height()-metrics.lineHeight(),width-12,UiTheme.MUTED);
    }
}
