package dev.betterlitematica.fabric;

/** Independent preferences. Choosing a mode never starts the worker. */
enum WheelModes {
    PRINT("打印"),MINE("挖掘"),DRAIN("排流体"),FILL("填充");
    private final String label;
    WheelModes(String label){this.label=label;}
    String label(){return label;}
    boolean enabled(PrinterSettings value){return switch(this){case PRINT->value.print;case MINE->value.breakWrong||value.breakExtra||value.breakState;case DRAIN->value.fluid;case FILL->value.fill;};}
    boolean mixed(PrinterSettings value){return this==MINE&&enabled(value)&&!(value.breakWrong&&value.breakExtra&&value.breakState);}
    void toggle(ProjectionController controller){controller.printer().configure(toggled(controller.options().printer,this));}
    static PrinterSettings toggled(PrinterSettings source,WheelModes mode){
        var next=PrinterSettings.read(source.snapshot());boolean enabled=!mode.enabled(source);
        switch(mode){case PRINT->next.print=enabled;case MINE->{next.breakWrong=enabled;next.breakExtra=enabled;next.breakState=enabled;}case DRAIN->next.fluid=enabled;case FILL->next.fill=enabled;}
        next.validate();return next;
    }
}
