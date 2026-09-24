package dev.betterlitematica.core;

import java.util.*;

public record AreaSelection(List<SelectionBox> boxes, String selected, Vec3i origin, boolean simple) {
    public static final AreaSelection EMPTY = new AreaSelection(List.of(), "", Vec3i.ZERO, true);
    public AreaSelection {
        boxes=List.copyOf(boxes);Objects.requireNonNull(selected);Objects.requireNonNull(origin);
        if(boxes.size()>128)throw new IllegalArgumentException("最多 128 个选区盒");
        Set<String> names=new HashSet<>();for(var box:boxes){box.region();if(!names.add(box.name()))throw new IllegalArgumentException("选区名称重复");}
        if(!selected.isEmpty()&&!names.contains(selected))throw new IllegalArgumentException("选中的区域不存在");
    }
    public SelectionBox current(){return boxes.stream().filter(b->b.name().equals(selected)).findFirst().orElseThrow(()->new IllegalStateException("请先创建并选择一个区域"));}
    public AreaSelection put(SelectionBox box){List<SelectionBox> result=new ArrayList<>(simple?List.of():boxes);result.removeIf(b->b.name().equals(box.name()));result.add(box);return new AreaSelection(result,box.name(),origin,simple);}
    public AreaSelection add(SelectionBox box){if(boxes.stream().anyMatch(b->b.name().equals(box.name())))throw new IllegalArgumentException("区域名称已存在");var result=new ArrayList<>(boxes);result.add(box);return new AreaSelection(result,box.name(),boxes.isEmpty()?box.first():origin,simple&&boxes.isEmpty());}
    public AreaSelection rename(String name){var current=current();if(boxes.stream().anyMatch(b->b.name().equals(name)&&!b.name().equals(selected)))throw new IllegalArgumentException("区域名称已存在");var result=boxes.stream().map(b->b.name().equals(selected)?new SelectionBox(name,current.first(),current.second()):b).toList();return new AreaSelection(result,name,origin,simple);}
    public AreaSelection translated(Vec3i offset){return new AreaSelection(boxes.stream().map(b->b.translate(offset)).toList(),selected,origin.add(offset),simple);}
    public AreaSelection select(String name){return new AreaSelection(boxes,name,origin,simple);}
    public AreaSelection remove(){List<SelectionBox> result=boxes.stream().filter(b->!b.name().equals(selected)).toList();return new AreaSelection(result,result.isEmpty()?"":result.get(0).name(),origin,simple);}
    public AreaSelection origin(Vec3i value){return new AreaSelection(boxes,selected,value,simple);}
    public AreaSelection toggleMode(){if(!simple&&boxes.size()>1)throw new IllegalStateException("切换简单模式前请只保留一个区域");return new AreaSelection(boxes,selected,origin,!simple);}
}
