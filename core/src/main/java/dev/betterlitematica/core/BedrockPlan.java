package dev.betterlitematica.core;

import java.util.*;

public final class BedrockPlan {
    public static final int MAX_LAYOUTS=600;
    private static final int[][] STEPS={{0,-1,0},{0,1,0},{0,0,-1},{0,0,1},{-1,0,0},{1,0,0}};
    private BedrockPlan() {}
    public record Layout(Vec3i target,Vec3i piston,Vec3i head,Vec3i torch,Vec3i support,
                         int initialFace,int breakFace,int torchFace,boolean throughTarget) {}

    public static Vec3i offset(Vec3i position,int face){
        Objects.requireNonNull(position,"position");if(face<0||face>=STEPS.length)throw new IllegalArgumentException("Invalid face");
        var d=STEPS[face];return new Vec3i(Math.addExact(position.x(),d[0]),Math.addExact(position.y(),d[1]),Math.addExact(position.z(),d[2]));
    }

    public static List<Layout> layouts(Vec3i target){
        Objects.requireNonNull(target,"target");var result=new LinkedHashSet<Layout>();
        for(int side:new int[]{1,0,2,3,4,5}){
            var piston=offset(target,side);
            for(int initial:new int[]{1,0}){
                var head=offset(piston,initial);if(head.equals(target))continue;

                for(int direction=0;direction<6;direction++)if(direction!=initial)
                    addPower(result,target,piston,head,offset(piston,direction),initial,side^1,direction,false);

                var upper=offset(piston,1);
                for(int direction=1;direction<6;direction++)
                    addPower(result,target,piston,head,offset(upper,direction),initial,side^1,direction,false);

                if(side==1)addPower(result,target,piston,head,offset(target,0),initial,side^1,0,true);
            }
        }
        if(result.size()>MAX_LAYOUTS)throw new IllegalStateException("Bedrock geometry budget exceeded");
        return List.copyOf(result);
    }

    private static void addPower(Set<Layout> result,Vec3i target,Vec3i piston,Vec3i head,Vec3i torch,
                                 int initial,int breaking,int queryDirection,boolean throughTarget){
        if(torch.equals(target)||torch.equals(piston)||torch.equals(head))return;
        for(int face=1;face<6;face++){

            if(queryDirection==face)continue;
            var support=offset(torch,face^1);
            if(support.equals(piston)||support.equals(head)||support.equals(torch))continue;
            result.add(new Layout(target,piston,head,torch,support,initial,breaking,face,throughTarget));
        }
    }
}
