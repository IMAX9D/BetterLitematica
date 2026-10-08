package dev.betterlitematica.fabric;

import dev.betterlitematica.runtime.ProjectVersions;
import java.util.*;
import java.util.concurrent.*;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;

final class ProjectScreen extends MenuScreen {
    private final UUID target;private String project="",projectName="project",selected="",versionName="",status="",deleting="";
    private List<String> projects=List.of();private List<ProjectVersions.Version> versions=List.of();private OverlayList projectList,versionList;private EditBox projectInput,versionInput;
    private CompletableFuture<List<String>> readingProjects;private CompletableFuture<List<ProjectVersions.Version>> readingVersions;private CompletableFuture<String> writing;private String writingProject,writingAction="保存";private boolean projectEdited,versionEdited;
    ProjectScreen(Screen parent,ProjectionController controller){super("项目版本","",parent,controller,true);target=controller.selectedId();readingProjects=controller.projectNames();}
    private void readVersions(){if(readingVersions!=null)readingVersions.cancel(true);versions=List.of();selected="";versionName="";deleting="";if(!project.isEmpty())readingVersions=controller.versionDetails(project);}
    private void chooseProject(String id){if(writing!=null)return;project=id;projectName=id;projectEdited=false;versionEdited=false;readVersions();refresh();}
    @Override protected void buildMenu(){
        int w=164,right=left+w+18,rw=innerWidth-w-18;var placement=controller.placement(target);boolean busy=writing!=null;
        projectInput=fieldAt("项目",projectName,left,0,w,80);projectInput.setEditable(!busy);projectInput.setResponder(v->{projectName=v;projectEdited=true;});
        buttonAt("保存版本",left,48,w,()->{controller.select(target);writingProject=projectName;writingAction="保存";writing=controller.saveProjectVersion(writingProject);refresh();},!busy&&placement!=null,true);
        if(projectList==null)projectList=new OverlayList(left,w,Math.max(40,bodyBottom-bodyTop-82),this::chooseProject);projectList.active=!busy;projectList.rows(projects.stream().map(n->new OverlayList.Row(n,n,true)).toList(),project);addBody(projectList,82);
        addBody(new OverlayLabel(right,rw,placement==null?"未选择投影":placement.name()),0);
        if(versionList==null)versionList=new OverlayList(right,rw,144,id->{if(writing!=null)return;selected=id;versionEdited=false;versionName=versions.stream().filter(v->v.id().equals(id)).findFirst().map(ProjectVersions.Version::name).orElse(id);deleting="";refresh();});versionList.active=!busy;versionList.rows(versions.stream().map(v->new OverlayList.Row(v.id(),v.name(),true)).toList(),selected);addBody(versionList,30);
        versionInput=fieldAt("版本名称",versionName,right,184,rw-80,120);versionInput.setEditable(!busy);versionInput.setResponder(v->{versionName=v;versionEdited=true;});
        buttonAt("重命名",right+rw-72,198,72,()->{writingProject=project;writingAction="重命名";writing=controller.renameVersion(project,selected,versionName);refresh();},!busy&&!selected.isEmpty(),false);
        int bw=(rw-16)/3;
        buttonAt("切换版本",right,240,bw,()->controller.restoreVersion(project,selected,target),!busy&&!selected.isEmpty()&&placement!=null,true);
        buttonAt("载入副本",right+bw+8,240,bw,()->controller.restoreVersion(project,selected),!busy&&!selected.isEmpty(),false);
        buttonAt(deleting.equals(selected)&&!selected.isEmpty()?"确认删除":"删除",right+2*(bw+8),240,bw,()->{if(deleting.equals(selected)){writingProject=project;writingAction="删除";writing=controller.deleteVersion(project,selected);deleting="";}else deleting=selected;refresh();},!busy&&!selected.isEmpty(),false);
    }
    @Override protected void updateMenu(){
        if(readingProjects!=null&&readingProjects.isDone()){try{projects=readingProjects.join();if(project.isEmpty()&&!projects.isEmpty()&&!projectEdited){project=projects.get(0);projectName=project;readVersions();}}catch(RuntimeException e){status="读取失败："+detail(e);}readingProjects=null;refresh();}
        if(readingVersions!=null&&readingVersions.isDone()){try{versions=readingVersions.join();if(versions.stream().noneMatch(v->v.id().equals(selected)))selected="";if(!versionEdited)versionName=versions.stream().filter(v->v.id().equals(selected)).findFirst().map(ProjectVersions.Version::name).orElse("");status="";}catch(RuntimeException e){status="读取失败："+detail(e);}readingVersions=null;refresh();}
        if(writing!=null&&writing.isDone()){try{String id=writing.join();project=writingProject;projectName=project;readVersions();selected=id;versionEdited=false;readingProjects=controller.projectNames();status="";}catch(RuntimeException e){status=writingAction+"失败："+detail(e);}writing=null;refresh();}
    }
    private static String detail(Throwable e){while(e.getCause()!=null)e=e.getCause();return e.getMessage();}
    @Override protected String statusLine(){return writing!=null?writingAction+"中…":status;}
    @Override public void removed(){if(readingProjects!=null)readingProjects.cancel(true);if(readingVersions!=null)readingVersions.cancel(true);if(writing!=null)writing.cancel(true);super.removed();}
}
