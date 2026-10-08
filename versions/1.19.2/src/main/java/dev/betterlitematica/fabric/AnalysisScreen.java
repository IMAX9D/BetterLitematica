package dev.betterlitematica.fabric;
import dev.betterlitematica.core.VerificationReport;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.*;
import java.util.*;
final class AnalysisScreen extends MenuScreen {
    private List<net.minecraft.item.Item> stableOrder=List.of();
    private final Map<String,String> verifierNames=new HashMap<>();private Object verifierLanguage;
    private final UUID target;private MaterialGrid grid;private VerificationList results;private FileMaterials.Sort sort=FileMaterials.Sort.MISSING;private boolean descending=true;
    private final boolean materials;private boolean missingOnly,ignored;private int ticks,multiplier=1;private String materialQuery="",errorQuery="";private TextFieldWidget multiplierInput;private ButtonWidget export,pause,cancel,highlight;
    AnalysisScreen(Screen parent,ProjectionController controller,boolean materials){super(materials?"材料清单":"投影校验","",parent,controller,true);this.materials=materials;target=controller.selectedId();}
    private PlacementAnalysis analysis(){var value=controller.analysis();return value!=null&&Objects.equals(target,value.target())?value:null;}
    @Override protected void buildMenu(){
        var placement=controller.placement(target);addBody(new OverlayLabel(left,innerWidth,target==null?"未选择投影":placement==null?"投影已移除":placement.name()),0);
        var search=fieldAt("搜索",materials?materialQuery:errorQuery,left,22,innerWidth-100,120);
        if(materials){
            buttonAt(sort.label(),left+innerWidth-92,36,62,()->{sort=FileMaterials.Sort.values()[(sort.ordinal()+1)%FileMaterials.Sort.values().length];updateGrid(true);refresh();},true,false);buttonAt(descending?"↓":"↑",left+innerWidth-24,36,24,()->{descending=!descending;updateGrid(true);refresh();},true,false);
            buttonAt("刷新",cellX(0,4),64,cellWidth(4),()->{controller.startMaterials(target);updateGrid(true);},placement!=null,true);
            export=buttonAt("导出",cellX(1,4),64,cellWidth(4),()->controller.exportMaterials(target,multiplier,missingOnly,materialQuery,sort,descending),false,false);
            buttonAt(missingOnly?"仅缺料":"全部材料",cellX(2,4),64,cellWidth(4),()->{missingOnly=!missingOnly;updateGrid(true);refresh();},true,false);
            caption("倍数",cellX(3,4),67,28);multiplierInput=new OverlayTextField(cellX(3,4)+36,0,cellWidth(4)-42,"倍数");multiplierInput.setMaxLength(10);multiplierInput.setTextPredicate(AnalysisScreen::validMultiplierInput);multiplierInput.setText(Integer.toString(multiplier));addBody(multiplierInput,64);multiplierInput.setChangedListener(v->{if(!v.isEmpty()){multiplier=Integer.parseInt(v);updateGrid(false);}});
            if(placement!=null){if(grid==null)grid=new MaterialGrid(left,innerWidth,bodyBottom-bodyTop-94);addBody(grid,94);updateGrid(false);}search.setChangedListener(v->{materialQuery=v;updateGrid(true);});
        }else{
            multiplierInput=null;buttonAt(ignored?"已忽略":"错误",left+innerWidth-92,36,92,()->{ignored=!ignored;rows(true);refresh();},true,false);
            buttonAt("开始",cellX(0,5),64,cellWidth(5),()->{controller.startAnalysis(target);rows(true);updateMenu();},placement!=null,true);
            pause=buttonAt("暂停",cellX(1,5),64,cellWidth(5),()->{if(analysis()!=null)analysis().pause();updateMenu();},false,false);
            cancel=buttonAt("取消",cellX(2,5),64,cellWidth(5),()->{if(analysis()!=null)controller.cancelAnalysis();updateMenu();},false,false);
            export=buttonAt("导出",cellX(3,5),64,cellWidth(5),()->controller.exportVerifier(target),false,false);
            highlight=buttonAt(controller.errorOverlayEnabled()?"高亮：开":"高亮：关",cellX(4,5),64,cellWidth(5),()->{controller.toggleErrorOverlay();refresh();},analysis()!=null,false);
            if(results==null)results=new VerificationList(left,innerWidth,bodyBottom-bodyTop-94,key->controller.action(()->{var a=analysis();if(a!=null){if(a.report().ignored(key))a.report().restore(key);else a.report().ignore(key);rows(false);}}));addBody(results,94);rows(false);search.setChangedListener(v->{errorQuery=v;rows(true);});
        }

        updateMenu();
    }
    static boolean validMultiplierInput(String value){if(value.isEmpty())return true;if(value.length()>10||!value.chars().allMatch(c->c>='0'&&c<='9'))return false;try{return Integer.parseInt(value)>0;}catch(NumberFormatException e){return false;}}
    private void updateGrid(boolean reset){if(grid==null)return;var totals=controller.materialTotals(target);var rows=new ArrayList<>(totals==null?List.<PlacementAnalysis.Material>of():totals.materials(client,multiplier,missingOnly,materialQuery,sort,descending));if(!reset&&grid.scrollOffset()>0){var order=new HashMap<net.minecraft.item.Item,Integer>();for(int i=0;i<stableOrder.size();i++)order.put(stableOrder.get(i),i);rows.sort(Comparator.comparingInt(r->order.getOrDefault(r.item(),Integer.MAX_VALUE)));}stableOrder=rows.stream().map(PlacementAnalysis.Material::item).toList();grid.rows(rows,reset);}
    private void rows(boolean reset){if(results==null)return;var a=analysis();if(a==null){results.emptyMessage(controller.placement(target)==null?"":"尚未校验");results.rows(List.of(),reset);return;}var report=a.report();var groups=new LinkedHashMap<>(report.groups());if(ignored)for(var key:report.ignoredKeys())groups.putIfAbsent(key,0L);String query=errorQuery.toLowerCase(Locale.ROOT);
        results.emptyMessage(!query.isBlank()?"无匹配结果":ignored?"无已忽略项":a.cancelled()?"已取消":a.paused()?"已暂停":!a.finished()?"校验中":report.count(dev.betterlitematica.core.Comparison.UNKNOWN)>0?"等待区块":"未发现错误");
        var language=net.minecraft.util.Language.getInstance();if(verifierLanguage!=language){verifierNames.clear();verifierLanguage=language;}
        results.rows(groups.entrySet().stream().filter(e->report.ignored(e.getKey())==ignored&&matchesVerification(e.getKey(),query,this::verificationName)).sorted(Map.Entry.<VerificationReport.Key,Long>comparingByValue().reversed()).map(e->new VerificationList.Row(e.getKey(),e.getValue(),ignored)).toList(),reset);
    }
    static boolean matchesVerification(VerificationReport.Key key,String query,java.util.function.Function<String,String> name){if(query.isBlank())return true;String raw=key.expected()+key.actual()+VerificationList.type(key.type())+key.type();String lower=query.toLowerCase(Locale.ROOT);return raw.toLowerCase(Locale.ROOT).contains(lower)||(name.apply(key.expected())+name.apply(key.actual())).toLowerCase(Locale.ROOT).contains(lower);}
    private String verificationName(String state){String known=verifierNames.get(state);if(known!=null)return known;if(verifierNames.size()>=VerificationReport.MAX_GROUPS*2)verifierNames.clear();return verifierNames.computeIfAbsent(state,AnalysisScreen::localizedStateName);}
    static String localizedStateName(String state){int start=state.indexOf('{'),end=state.indexOf('}');String id=start>=0&&end>start?state.substring(start+1,end):state.split("\\[",2)[0];var key=net.minecraft.util.Identifier.tryParse(id);return key!=null&&net.minecraft.util.registry.Registry.BLOCK.containsId(key)?net.minecraft.util.registry.Registry.BLOCK.get(key).getName().getString():state.isEmpty()?"未知":state;}
    @Override protected void updateMenu(){if(export==null)return;if(materials){if(multiplierInput!=null&&!multiplierInput.isFocused()&&multiplierInput.getText().isEmpty())multiplierInput.setText(Integer.toString(multiplier));var totals=controller.materialTotals(target);export.active=totals!=null&&totals.finished();if(++ticks%10==0)updateGrid(false);}else{var a=analysis();boolean live=a!=null&&!a.cancelled();pause.active=cancel.active=live;highlight.active=a!=null;pause.setMessage(net.minecraft.text.Text.literal(a!=null&&a.paused()?"继续":"暂停"));export.active=a!=null;if(++ticks%10==0)rows(false);}}
    @Override protected String statusLine(){if(materials){var value=controller.materialTotals(target);return value==null?"":value.status();}var a=analysis();return a==null?"":a.status();}
}
