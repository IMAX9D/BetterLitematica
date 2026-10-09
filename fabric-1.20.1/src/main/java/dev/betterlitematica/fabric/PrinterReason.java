package dev.betterlitematica.fabric;

import java.util.Objects;

/** Machine-readable state; wording is presentation only and never determines the cause. */
record PrinterReason(Id id,String description) {
    enum Id {
        NONE(""), RUNNING(""), STOPPED(""), PAUSED(""), ERROR("施工异常"),
        NO_PROJECTION("无投影"), USING_ITEM("正在使用"), RETURN_TO_GAME("等待返回"),
        BUILD_HEIGHT("超出高度"), LOADING("待加载"), NO_WORK("没有目标"), MISSING("缺料"),
        OBSERVER("等侦测器"), OUT_OF_REACH("够不到"), OBSTRUCTED("被挡住"),
        NO_FACE("无放置面"), NO_SUPPORT("等支撑"), UNSUPPORTED("无法施工"),
        EQUIPPING("等待换手"), INVENTORY_FULL("背包已满"), TOOL_REQUIRED("需要工具"),
        CONFIRMING("等待确认"), CONFIRM_TIMEOUT("确认超时"), BLOCK_UPDATE("等方块更新"),
        BREAKING("挖掘中"), ICE_BREAK("等破冰"), REJECTED("未获接受"),
        SUPPLY("补给中"), SUPPLY_UNAVAILABLE("补给不可用"), SUPPLY_INTERRUPTED("补给中断"),
        RECOVERING("回收材料"), BEDROCK_BUSY("等待破基岩"), BEDROCK("破基岩中"), BEDROCK_FAILED("破基岩失败"),
        CONTAINER("填充容器"), CONTAINER_BLOCKED("容器受阻"), SIGN_BLOCKED("告示牌受阻");
        private final String shortLabel;
        Id(String shortLabel){this.shortLabel=shortLabel;}
        String shortLabel(){return shortLabel;}
    }
    PrinterReason {Objects.requireNonNull(id);Objects.requireNonNull(description);}
    static PrinterReason of(Id id,String description){return new PrinterReason(id,description);}
    static final PrinterReason NONE=of(Id.NONE,""),RUNNING=of(Id.RUNNING,"打印中"),STOPPED=of(Id.STOPPED,"已停止"),
        PAUSED=of(Id.PAUSED,"已暂停"),ERROR=of(Id.ERROR,"打印异常，已暂停");
    String shortLabel(){return id.shortLabel();}
    boolean isEmpty(){return id==Id.NONE;}
    boolean missing(){return id==Id.MISSING;}
    boolean blocked(){return !shortLabel().isEmpty();}
    /** Only typed operational failures may supply player-facing details. */
    static PrinterReason failure(RuntimeException failure){return failure instanceof Failure typed?typed.reason:ERROR;}
    static final class Failure extends IllegalStateException {
        final PrinterReason reason;
        Failure(PrinterReason reason){super(reason.description());this.reason=reason;}
    }
}
