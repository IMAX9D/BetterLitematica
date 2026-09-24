package dev.betterlitematica.fabric;
import net.minecraft.client.gui.screen.Screen;
final class TaskScreen extends MenuScreen {
    private int page,ticks;
    TaskScreen(Screen p,ProjectionController c){super("任务管理","取消不撤销已写入内容",p,c,true);}
    @Override protected void buildMenu(){
        var tasks=controller.tasks();button("取消全部运行任务",0,1,0,()->{controller.cancelTasks();refresh();},!tasks.isEmpty(),false);
        int count=listRows(2),pages=Math.max(1,(tasks.size()+count-1)/count);page=Math.min(page,pages-1);
        if(tasks.isEmpty())label("暂无任务",2);
        for(int i=page*count;i<Math.min(tasks.size(),(page+1)*count);i++)label((i+1)+".  "+tasks.get(i),2+i-page*count);
        pager(page,pages,()->{page--;refresh();},()->{page++;refresh();});
    }
    @Override protected void updateMenu(){if(++ticks%20==0)refresh();}
}
