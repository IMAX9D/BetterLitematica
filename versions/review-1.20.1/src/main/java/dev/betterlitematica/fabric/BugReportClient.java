package dev.betterlitematica.fabric;

import com.google.gson.*;
import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.Pattern;

/** User-initiated feedback only. A persisted, immutable request makes an ambiguous timeout safe to retry. */
final class BugReportClient {
    static final URI API=URI.create("https://forum.imax9d.dpdns.org/api/mods/betterlitematica/v1/");
    static final int DESCRIPTION_CHARS=3000;
    record Draft(String title,String description,String contact,boolean logs,boolean crash,boolean previousSession) {
        static final Draft EMPTY=new Draft("","","",true,true,false);
        Draft(String title,String description,String contact,boolean logs,boolean crash){this(title,description,contact,logs,crash,false);}
        Draft { Objects.requireNonNull(title);Objects.requireNonNull(description);Objects.requireNonNull(contact); }
        boolean hasContent(){return !title.isBlank()||!description.isBlank()||!contact.isBlank();}
    }
    record Receipt(String id,String token,String status) {}
    record View(boolean busy,String message,boolean error,Receipt receipt,long retryAt) {}
    record Attachments(String logs,String crash,String note) {}
    private record Pending(Draft draft,String body) {}
    private static final Gson JSON=new GsonBuilder().setPrettyPrinting().create();
    private final Path game,folder;
    private final URI api;
    private final ExecutorService worker=Executors.newSingleThreadExecutor(r->{var t=new Thread(r,"betterlitematica-feedback");t.setDaemon(true);return t;});
    private final HttpClient http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).followRedirects(HttpClient.Redirect.NEVER).build();
    private volatile View view=new View(true,"正在读取反馈草稿…",false,null,0);
    private volatile Draft draft=Draft.EMPTY;
    private volatile Pending pending;
    private final CompletableFuture<Draft> loading;

    BugReportClient(Path game){this(game,API);}
    /** Non-public endpoint injection is for local contract checks; the game always uses the fixed HTTPS origin. */
    BugReportClient(Path game,URI api){
        this.game=game;this.folder=game.resolve("config/betterlitematica/feedback");this.api=api;
        loading=CompletableFuture.supplyAsync(()->{
            String warning="";Receipt receipt=null;
            try { var data=read("draft.json");if(data!=null)draft=readDraft(data); }
            catch(Exception e){warning="旧反馈草稿无法读取，可填写新问题。";}
            try {var data=read("pending.json");if(data!=null){var candidate=new Pending(readDraft(data.getAsJsonObject("draft")),data.get("body").getAsString());validateBody(candidate.body);pending=candidate;if(!draft.hasContent())draft=pending.draft;}}
            catch(Exception e){warning="旧提交记录无法读取，可填写新问题。";}
            try {var data=read("receipt.json");if(data!=null)receipt=receipt(data);}
            catch(Exception e){warning="上次反馈回执无法读取。";}
            view=new View(false,warning,false,receipt,0);return draft;
        },worker);
    }
    CompletableFuture<Draft> loading(){return loading;}
    Draft draft(){return draft;}
    View view(){return view;}
    boolean retrying(Draft value){return pending!=null&&pending.draft.equals(value);}
    void saveDraft(Draft value){draft=value;worker.execute(()->{try{write("draft.json",JSON.toJsonTree(value).getAsJsonObject());}catch(IOException e){if(!view.busy)view=new View(false,"草稿未能保存到本地，请勿丢失已填写内容。",true,view.receipt,view.retryAt);}});}
    CompletableFuture<String> attachmentNote(Draft value){return CompletableFuture.supplyAsync(()->{try{boolean report=!value.crash||latestCrash()!=null;boolean archive=!value.previousSession||latestArchive()!=null;return !report?"未找到崩溃报告，可照常提交日志和问题描述。":!archive?"未找到上次日志，将附带本次启动日志。":"";}catch(IOException e){return "附带文件暂时无法读取，可关闭对应选项。";}},worker);}
    CompletableFuture<Attachments> preview(Draft value){return CompletableFuture.supplyAsync(()->{try{if(pending!=null&&pending.draft.equals(value)){var body=JsonParser.parseString(pending.body).getAsJsonObject();return new Attachments(body.get("logs").getAsString(),body.get("crash_report").getAsString(),"");}return attachments(value);}catch(IOException e){throw new CompletionException(new IOException("附带内容读取失败，请关闭对应选项后重试。"));}},worker);}
    void submit(Draft value,JsonObject environment){
        if(view.busy||System.currentTimeMillis()<view.retryAt)return;
        String validation=validate(value);if(!validation.isEmpty()){view=new View(false,validation,true,view.receipt,0);return;}
        draft=value;view=new View(true,"正在准备反馈…",false,view.receipt,0);
        JsonObject captured=environment.deepCopy();
        worker.execute(()->{
            try {
                write("draft.json",JSON.toJsonTree(value).getAsJsonObject());
                if(pending==null||!pending.draft.equals(value)){
                    var files=attachments(value);
                    var body=captured;
                    body.addProperty("client_report_id",UUID.randomUUID().toString());
                    body.addProperty("title",value.title.strip());body.addProperty("description",value.description.strip());body.addProperty("contact",value.contact.strip());
                    body.addProperty("logs",files.logs);body.addProperty("crash_report",files.crash);
                    if(!files.note.isEmpty())body.getAsJsonObject("diagnostics").addProperty("attachment_note",files.note);
                    fitBody(body);var candidate=new Pending(value,JSON.toJson(body));validateBody(candidate.body);
                    var saved=new JsonObject();saved.add("draft",JSON.toJsonTree(value));saved.addProperty("body",candidate.body);
                    // Disk persistence happens before POST, even on the first attempt.
                    write("pending.json",saved);pending=candidate;
                }
                view=new View(true,"正在提交…",false,view.receipt,0);
                var request=HttpRequest.newBuilder(api.resolve("reports")).timeout(Duration.ofSeconds(30))
                    .header("Content-Type","application/json; charset=utf-8").header("User-Agent","BetterLitematica-Feedback/1")
                    .POST(HttpRequest.BodyPublishers.ofString(pending.body,StandardCharsets.UTF_8)).build();
                Response response=null;
                for(int attempt=0;attempt<2;attempt++){
                    try {response=send(request);if(response.code!=503||attempt==1)break;}
                    catch(HttpTimeoutException|ConnectException e){if(attempt==1)throw e;}
                    view=new View(true,"连接暂时中断，正在重试…",false,view.receipt,0);Thread.sleep(1000);
                }
                if(response.code!=200&&response.code!=201){failure(response);return;}
                Receipt result=receipt(response.body);String note="提交成功";
                try {write("receipt.json",receiptJson(result));Files.deleteIfExists(folder.resolve("pending.json"));write("draft.json",JSON.toJsonTree(Draft.EMPTY).getAsJsonObject());}
                catch(IOException e){note="提交成功；本地回执保存失败，请复制报告编号。";}
                pending=null;draft=Draft.EMPTY;view=new View(false,note,false,result,0);
            } catch(Exception e){view=new View(false,errorText(e),true,view.receipt,0);}
        });
    }
    void query(Receipt value){
        if(view.busy||value==null||System.currentTimeMillis()<view.retryAt)return;
        view=new View(true,"正在查询处理状态…",false,value,0);
        worker.execute(()->{try{
            var request=HttpRequest.newBuilder(api.resolve("reports/"+value.id)).timeout(Duration.ofSeconds(30))
                .header("X-Report-Token",value.token).GET().build();
            var response=send(request);if(response.code!=200){failure(response);return;}
            if(!response.body.get("success").getAsBoolean()||!value.id.equals(response.body.get("report_id").getAsString()))throw new IOException();
            var result=new Receipt(value.id,value.token,checkedStatus(response.body.get("status").getAsString()));
            String note="处理状态已更新";try{write("receipt.json",receiptJson(result));}catch(IOException e){note="状态已更新，本地回执未能保存。";}
            view=new View(false,note,false,result,0);
        }catch(Exception e){view=new View(false,errorText(e),true,value,0);}});
    }
    void newDraft(){if(view.busy)return;draft=Draft.EMPTY;saveDraft(draft);view=new View(false,"",false,view.receipt,0);}
    private record Response(int code,JsonObject body,long retry) {}
    private Response send(HttpRequest request)throws IOException,InterruptedException {
        var response=http.send(request,HttpResponse.BodyHandlers.ofInputStream());
        JsonObject body=new JsonObject();try(var stream=response.body()){
            byte[] bytes=stream.readNBytes(16*1024+1);if(bytes.length>16*1024)throw new IOException();
            try{body=JsonParser.parseString(new String(bytes,StandardCharsets.UTF_8)).getAsJsonObject();}catch(RuntimeException e){if(response.statusCode()==200||response.statusCode()==201)throw new IOException();}
        }
        long retry=0;try{retry=Math.min(86400,Math.max(1,Long.parseLong(response.headers().firstValue("Retry-After").orElse("60"))));}catch(NumberFormatException ignored){retry=60;}
        return new Response(response.statusCode(),body,retry);
    }
    private void failure(Response response){
        String message=switch(response.code){
            case 403->"反馈回执无效，无法查询这份报告。";case 404->"反馈服务或报告不存在，请稍后重试。";
            case 409->"这份提交标识已有其他内容，请修改描述后重新提交。";
            case 413->"附带内容过大，请关闭日志或崩溃报告后重试。";
            case 400,415,422->"反馈内容未通过校验，请检查输入后重试。";
            case 429->"提交过于频繁，请稍后重试；内容已保留。";
            case 503->"反馈服务暂时不可用；内容已保留，可稍后重试。";
            default->"提交未完成；内容已保留，请稍后重试。";
        };
        view=new View(false,message,true,view.receipt,response.code==429?System.currentTimeMillis()+response.retry*1000:0);
    }
    static String errorText(Exception error){
        if(error instanceof HttpTimeoutException)return "提交超时；内容已保留，重试不会重复提交。";
        if(error instanceof UnknownHostException||error instanceof ConnectException)return "连接不到反馈服务，请检查网络后重试。";
        if(error instanceof javax.net.ssl.SSLException)return "安全连接失败，请检查系统时间或网络后重试。";
        if(error instanceof IllegalArgumentException)return "反馈内容无效，请检查输入后重试。";
        return "操作未完成；内容已保留，请稍后重试。";
    }
    static String validate(Draft draft){
        if(draft.description.strip().length()<3)return "请填写至少 3 个字的问题描述。";
        if(bytes(draft.description)>10000)return "问题描述太长，请精简后重试。";
        if(bytes(draft.title)>120)return "标题太长，请缩短后重试。";
        if(bytes(draft.contact)>256)return "联系方式太长，请缩短后重试。";
        if((draft.title+draft.description+draft.contact).indexOf('\0')>=0)return "内容含有无效字符，请修改后重试。";
        return "";
    }
    private static int bytes(String text){return text.getBytes(StandardCharsets.UTF_8).length;}
    static String bounded(String text,int limit){
        if(bytes(text)<=limit)return text;
        int end=Math.min(text.length(),limit);while(end>0&&bytes(text.substring(0,end))>limit)end--;
        if(end>0&&Character.isHighSurrogate(text.charAt(end-1)))end--;
        return text.substring(0,end);
    }
    private static void fitBody(JsonObject body){
        for(int i=0;i<4&&bytes(JSON.toJson(body))>250*1024;i++)for(String key:List.of("logs","crash_report")){String text=body.get(key).getAsString();body.addProperty(key,key.equals("logs")?tailBounded(text,bytes(text)/2):excerptBounded(text,bytes(text)/2));}
    }
    private static void validateBody(String body){
        if(bytes(body)>256*1024)throw new IllegalArgumentException();
        var object=JsonParser.parseString(body).getAsJsonObject();UUID.fromString(object.get("client_report_id").getAsString());
    }
    private static Receipt receipt(JsonObject value)throws IOException {
        try {String id=value.get("report_id").getAsString(),token=value.get("receipt_token").getAsString();
            if(!id.matches("BL-\\d{8}-[0-9A-F]{12}")||!token.matches("[a-fA-F0-9]{64}")||value.has("success")&&!value.get("success").getAsBoolean())throw new IOException();
            return new Receipt(id,token,checkedStatus(value.get("status").getAsString()));
        }catch(RuntimeException e){throw new IOException();}
    }
    private static String checkedStatus(String status)throws IOException {if(!Set.of("new","triaged","need_info","resolved","closed").contains(status))throw new IOException();return status;}
    static String statusLabel(String status){return switch(status){case "new"->"待处理";case "triaged"->"已确认";case "need_info"->"需补充信息";case "resolved"->"已解决";case "closed"->"已关闭";default->"未知";};}
    private static JsonObject receiptJson(Receipt receipt){var value=new JsonObject();value.addProperty("report_id",receipt.id);value.addProperty("receipt_token",receipt.token);value.addProperty("status",receipt.status);return value;}
    private JsonObject read(String name)throws IOException {
        Path file=folder.resolve(name);if(!Files.exists(file))return null;if(Files.size(file)>512*1024)throw new IOException();
        return JsonParser.parseString(Files.readString(file,StandardCharsets.UTF_8)).getAsJsonObject();
    }
    private static Draft readDraft(JsonObject data){return new Draft(data.get("title").getAsString(),data.get("description").getAsString(),data.get("contact").getAsString(),data.get("logs").getAsBoolean(),data.get("crash").getAsBoolean(),data.has("previousSession")&&data.get("previousSession").getAsBoolean());}
    private void write(String name,JsonObject data)throws IOException {
        Files.createDirectories(folder);Path temp=Files.createTempFile(folder,"feedback-",".part");
        try {Files.writeString(temp,JSON.toJson(data),StandardCharsets.UTF_8);try{Files.move(temp,folder.resolve(name),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}catch(AtomicMoveNotSupportedException e){Files.move(temp,folder.resolve(name),StandardCopyOption.REPLACE_EXISTING);}}
        finally{Files.deleteIfExists(temp);}
    }
    private Attachments attachments(Draft value)throws IOException {
        String log="",report="",note="";
        if(value.logs){Path archive=value.previousSession?latestArchive():null,file=archive==null?game.resolve("logs/latest.log"):archive;
            if(Files.isRegularFile(file))log=tailBounded(sanitize(archive==null?tail(file,128*1024):gzipTail(file,128*1024)),80*1024);else note="未找到日志文件。";
            if(value.previousSession&&archive==null)note="未找到上次日志，将附带本次启动日志。";
        }
        if(value.crash){Path latest=latestCrash();if(latest!=null)report=excerptBounded(sanitize(crashExcerpt(latest,64*1024)),40*1024);else note=note.isEmpty()?"未找到崩溃报告。":note+" 未找到崩溃报告。";}
        return new Attachments(log,report,note);
    }
    private Path latestArchive()throws IOException {return newest(game.resolve("logs"),"*.log.gz");}
    private Path latestCrash()throws IOException {Path managed=newest(game.resolve("crash-reports"),"crash-*.txt"),nativeCrash=newest(game,"hs_err_pid*.log");return managed==null?nativeCrash:nativeCrash!=null&&Files.getLastModifiedTime(nativeCrash).compareTo(Files.getLastModifiedTime(managed))>0?nativeCrash:managed;}
    private static Path newest(Path directory,String glob)throws IOException {
        Path latest=null;long modified=Long.MIN_VALUE;
        if(Files.isDirectory(directory))try(var files=Files.newDirectoryStream(directory,glob)){for(Path file:files)if(Files.isRegularFile(file)&&!Files.isSymbolicLink(file)){long time=Files.getLastModifiedTime(file).toMillis();if(time>modified){modified=time;latest=file;}}}
        return latest;
    }
    private static String gzipTail(Path file,int maximum)throws IOException {
        byte[] ring=new byte[maximum],buffer=new byte[8192];long total=0;int cursor=0;
        try(var stream=new java.util.zip.GZIPInputStream(Files.newInputStream(file))){int read;while((read=stream.read(buffer))>=0){total+=read;if(total>64L*1024*1024)throw new IOException();for(int i=0;i<read;i++){ring[cursor]=buffer[i];cursor=(cursor+1)%maximum;}}}
        int size=(int)Math.min(total,maximum);byte[] data=new byte[size];int start=total>maximum?cursor:0;for(int i=0;i<size;i++)data[i]=ring[(start+i)%maximum];
        String text=new String(data,StandardCharsets.UTF_8);if(total>maximum){int newline=text.indexOf('\n');return newline<0?"":text.substring(newline+1);}return text;
    }
    private static String crashExcerpt(Path file,int maximum)throws IOException {
        if(Files.size(file)<=maximum)return Files.readString(file,StandardCharsets.UTF_8);
        byte[] head;try(var stream=Files.newInputStream(file)){head=stream.readNBytes(maximum/2);}
        String beginning=new String(head,StandardCharsets.UTF_8);int line=beginning.lastIndexOf('\n');if(line>=0)beginning=beginning.substring(0,line);
        return beginning+"\n[中间内容已省略]\n"+tail(file,maximum/2);
    }
    private static String tailBounded(String text,int maximum){
        byte[] bytes=text.getBytes(StandardCharsets.UTF_8);if(bytes.length<=maximum)return text;
        int start=bytes.length-maximum;while(start<bytes.length&&(bytes[start]&0xc0)==0x80)start++;
        String ending=new String(bytes,start,bytes.length-start,StandardCharsets.UTF_8);int newline=ending.indexOf('\n');return newline<0?ending:ending.substring(newline+1);
    }
    private static String excerptBounded(String text,int maximum){if(bytes(text)<=maximum)return text;if(maximum<128)return bounded(text,maximum);return bounded(text,maximum/2-32)+"\n[中间内容已省略]\n"+tailBounded(text,maximum/2-32);}
    private static String tail(Path file,int maximum)throws IOException {
        try(var stream=new RandomAccessFile(file.toFile(),"r")){long start=Math.max(0,stream.length()-maximum);stream.seek(start);byte[] data=new byte[(int)Math.min(maximum,stream.length()-start)];stream.readFully(data);String text=new String(data,StandardCharsets.UTF_8);if(start>0){int line=text.indexOf('\n');text=line<0?"":text.substring(line+1);}return text;}
    }
    private static final Pattern PRIVATE_LINE=Pattern.compile("(?i)(\\[CHAT\\]|chat message|logged in with entity|connecting to|server address|server ip|session id|--accessToken|--uuid|\\bjoined the game\\b|all players:|player count:|entity name:|player name:|access[_-]?token|authorization|api[_-]?key|password|secret)");
    static String sanitize(String input){
        StringBuilder clean=new StringBuilder();
        for(String line:input.replace("\r","").split("\n",-1)){
            if(PRIVATE_LINE.matcher(line).find())continue;
            line=line.replaceAll("(?i)\\bsk-[A-Za-z0-9_-]{16,}","[密钥已隐藏]")
                .replaceAll("(?i)Bearer\\s+[A-Za-z0-9._~+/-]+","Bearer [已隐藏]")
                .replaceAll("(?i)((?:access[_-]?token|api[_-]?key|password|authorization|secret)\\s*[=:]\\s*[\"']?)[^\\s\"'&,;]+","$1[已隐藏]")
                .replaceAll("[A-Za-z]:[\\\\/].*","[本地路径已隐藏]")
                .replaceAll("(?<![\\w.])/(?:home|Users|data|opt|var|tmp)/.*","[本地路径已隐藏]")
                .replaceAll("\\\\\\\\[^\\s]+.*","[本地路径已隐藏]")
                .replaceAll("https?://[^\\s\"<>]+","[网址已隐藏]")
                .replaceAll("(?<![\\w.])(?:\\d{1,3}\\.){3}\\d{1,3}(?::\\d+)?","[网络地址已隐藏]")
                .replaceAll("(?i)[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}","[标识已隐藏]");
            String user=System.getProperty("user.name","");if(!user.isBlank())line=line.replace(user,"[用户名已隐藏]");
            clean.append(line).append('\n');
        }
        return clean.toString();
    }
}
