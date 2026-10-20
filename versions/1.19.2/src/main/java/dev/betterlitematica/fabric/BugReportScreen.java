package dev.betterlitematica.fabric;

import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.*;
import net.minecraft.text.Text;
import java.util.concurrent.CompletableFuture;

/** One short form, explicit attachment choices, and a durable receipt in the same responsive frame. */
final class BugReportScreen extends MenuScreen {
    private final BugReportClient service;
    private BugReportClient.Draft draft=BugReportClient.Draft.EMPTY;
    private TextFieldWidget titleInput,contactInput;
    private BugTextArea description;
    private ButtonWidget submit,previous,logToggle,crashToggle,preview;
    private boolean loaded,edited,submitted,showReceipt,wasBusy;
    private long editedAt;
    private String localMessage="";
    private String lastReceipt="";
    private CompletableFuture<BugReportClient.Attachments> previewing;
    private CompletableFuture<String> attachmentNote;

    BugReportScreen(Screen parent,ProjectionController controller){super("问题反馈","描述遇到的问题，点击提交即可。",parent,controller,true);service=BugReports.service();if(service.view().receipt()!=null)lastReceipt=service.view().receipt().id();}
    @Override protected int preferredHeight(){return 380;}
    @Override protected void buildMenu(){
        var state=service.view();
        if(showReceipt&&state.receipt()!=null){buildReceipt(state.receipt());return;}
        titleInput=fieldAt("标题（可选）",draft.title(),left,0,innerWidth,120);
        titleInput.setChangedListener(value->edit(new BugReportClient.Draft(value,draft.description(),draft.contact(),draft.logs(),draft.crash())));
        caption("问题描述",left,44,innerWidth);
        int descriptionHeight=Math.max(60,Math.min(92,bodyBottom-bodyTop-174));
        if(description==null)description=new BugTextArea(left,innerWidth,descriptionHeight,draft.description(),true,BugReportClient.DESCRIPTION_CHARS,value->edit(new BugReportClient.Draft(draft.title(),value,draft.contact(),draft.logs(),draft.crash())));
        description.x=left;description.setWidth(innerWidth);description.resizeHeight(descriptionHeight);addBody(description,58);
        int contactY=58+descriptionHeight+10;
        contactInput=fieldAt("联系方式（可选）",draft.contact(),left,contactY,innerWidth,80);
        contactInput.setChangedListener(value->edit(new BugReportClient.Draft(draft.title(),draft.description(),value,draft.logs(),draft.crash())));
        int toggleY=contactY+44,third=cellWidth(3);
        logToggle=buttonAt("附带日志："+(draft.logs()?"开":"关"),left,toggleY,third,()->{edit(new BugReportClient.Draft(draft.title(),draft.description(),draft.contact(),!draft.logs(),draft.crash()));refresh();},true,false);
        crashToggle=buttonAt("附带崩溃报告："+(draft.crash()?"开":"关"),cellX(1,3),toggleY,third,()->{edit(new BugReportClient.Draft(draft.title(),draft.description(),draft.contact(),draft.logs(),!draft.crash()));refresh();},true,false);
        preview=buttonAt("查看附带内容",cellX(2,3),toggleY,third,this::preview,true,false);
        hint(logToggle,"附带清理后的日志；若附带上次运行的崩溃报告，也会选择上次日志。聊天、密钥、地址和个人路径会被移除。");
        hint(crashToggle,"附带本次或上次运行的崩溃报告，更早的报告不附带；可查看来源时间和清理后的内容。");hint(preview,"查看将附带的日志或崩溃报告。");
        caption(BugReports.versionSummary(),left,toggleY+30,innerWidth);
        hint(titleInput,"可留空；报告标题将取问题描述的第一行。");hint(contactInput,"仅在希望维护者联系你时填写。");
        submit=fixedAction("提交",0,94,()->{localMessage="";submitted=true;edited=false;service.submit(draft,BugReports.environment(controller));});
        previous=fixed("上次反馈",106,88,()->{showReceipt=true;refresh();});
        updateControls();
    }
    private void buildReceipt(BugReportClient.Receipt receipt){
        caption("报告编号",left,12,innerWidth);
        addBody(new OverlayLabel(left,innerWidth,receipt.id()),34);
        caption("处理状态："+BugReportClient.statusLabel(receipt.status()),left,74,innerWidth);
        if(receipt.status().equals("need_info"))caption("请提交新报告补充信息，并注明原报告编号。",left,102,innerWidth);
        buttonAt("复制报告编号",left,132,cellWidth(2),()->{client.keyboard.setClipboard(receipt.id());localMessage="报告编号已复制";},true,false);
        buttonAt("刷新处理状态",cellX(1,2),132,cellWidth(2),()->{localMessage="";service.query(receipt);},!service.view().busy(),false);
        var next=fixedAction("提交新问题",0,112,()->{if(draft.hasContent()){showReceipt=false;}else{service.newDraft();draft=defaults();service.saveDraft(draft);description=null;showReceipt=false;}localMessage="";attachmentNote=service.attachmentNote(draft);refresh();});next.active=!service.view().busy();
    }
    private boolean previousSession(){return BugReports.previousSession(parent instanceof net.minecraft.client.gui.screen.TitleScreen);}
    private BugReportClient.Draft defaults(){return new BugReportClient.Draft("","","",true,true,previousSession());}
    private void edit(BugReportClient.Draft value){boolean attachmentChanged=value.logs()!=draft.logs()||value.crash()!=draft.crash();draft=new BugReportClient.Draft(value.title(),value.description(),value.contact(),value.logs(),value.crash(),draft.previousSession());edited=true;editedAt=System.nanoTime();localMessage="";if(attachmentChanged)attachmentNote=service.attachmentNote(draft);}
    private void preview(){localMessage="正在读取附带内容…";previewing=service.preview(draft);}
    private void updateControls(){
        if(submit==null||showReceipt)return;var state=service.view();boolean active=loaded&&!state.busy();
        titleInput.active=contactInput.active=description.active=logToggle.active=crashToggle.active=active;
        titleInput.setEditable(active);contactInput.setEditable(active);
        preview.active=active&&(draft.logs()||draft.crash())&&previewing==null;
        submit.active=active&&BugReportClient.validate(draft).isEmpty()&&System.currentTimeMillis()>=state.retryAt();
        submit.setMessage(Text.literal(state.busy()?"提交中…":service.retrying(draft)?"重试提交":"提交"));
        previous.active=loaded&&!state.busy()&&state.receipt()!=null;
    }
    @Override protected void updateMenu(){
        if(!loaded&&service.loading().isDone()){
            var saved=service.draft();draft=saved.hasContent()?saved:defaults();
            if(saved.hasContent()&&!service.retrying(saved))draft=new BugReportClient.Draft(saved.title(),saved.description(),saved.contact(),saved.logs(),saved.crash(),previousSession());
            // A blank legacy draft may contain the old attachment defaults; it is a new report.
            if(!draft.equals(saved))service.saveDraft(draft);
            loaded=true;description=null;attachmentNote=service.attachmentNote(draft);refresh();
        }
        var state=service.view();
        if(!state.busy()&&!state.error()&&state.receipt()!=null&&state.message().startsWith("提交成功")&&(submitted||!state.receipt().id().equals(lastReceipt))){submitted=false;lastReceipt=state.receipt().id();draft=BugReportClient.Draft.EMPTY;description=null;showReceipt=true;refresh();}
        if(submitted&&!state.busy())submitted=false;
        if(state.busy()!=wasBusy){wasBusy=state.busy();if(showReceipt)refresh();}
        if(edited&&!state.busy()&&System.nanoTime()-editedAt>700_000_000L){edited=false;service.saveDraft(draft);}
        if(previewing!=null&&previewing.isDone()){
            var result=previewing;previewing=null;
            try{var files=result.join();String text=(files.note().isEmpty()?"":files.note()+"\n\n")+(files.logs().isEmpty()?"":"【日志】\n"+files.logs())+(files.crash().isEmpty()?"":"\n【崩溃报告】\n"+files.crash());localMessage="";client.setScreen(new BugAttachmentScreen(this,controller,text));}
            catch(RuntimeException e){localMessage="附带内容读取失败，请关闭对应选项后重试。";}
        }
        updateControls();
    }
    @Override protected String statusLine(){String validation=showReceipt||draft.description().isBlank()?"":BugReportClient.validate(draft);String note=showReceipt||attachmentNote==null?"":attachmentNote.getNow("");return !localMessage.isEmpty()?localMessage:!validation.isEmpty()?validation:!service.view().message().isEmpty()?service.view().message():note;}
    @Override protected int statusColor(){return !localMessage.isEmpty()&&localMessage.contains("失败")||service.view().error()?UiTheme.ERROR:showReceipt?UiTheme.SUCCESS:UiTheme.MUTED;}
    @Override public boolean keyPressed(int key,int scan,int modifiers){if(getFocused() instanceof BugTextArea area&&area.keyPressed(key,scan,modifiers))return true;return super.keyPressed(key,scan,modifiers);}
    @Override public void removed(){if(loaded&&edited&&!service.view().busy()){edited=false;service.saveDraft(draft);}super.removed();}
}
