package dev.betterlitematica.fabric;

import dev.betterlitematica.core.QuadVisibility;
import net.minecraft.block.Blocks;
import net.minecraft.state.property.Properties;

public final class ProjectionTintChecks {
    private static int checks;
    private static void check(boolean value,String message){checks++;if(!value)throw new AssertionError(message);}
    public static int run(){checks=0;short[] owners={0,1,2,-1,2,0,1};var part=new QuadVisibility.Part(owners);var hidden=new QuadVisibility.Mask();var wrong=new QuadVisibility.Mask();
        for(int h=0;h<8;h++)for(int w=0;w<8;w++){
            for(int c=0;c<3;c++){hidden.set(c,(h&(1<<c))!=0);wrong.set(c,(w&(1<<c))!=0);}
            for(int pass=0;pass<3;pass++){boolean[] blue=quads(part,hidden,wrong,false,owners.length),red=quads(part,hidden,wrong,true,owners.length);
                for(int i=0;i<owners.length;i++){boolean visible=owners[i]<0||!hidden.hidden(owners[i]);boolean tinted=owners[i]>=0&&wrong.hidden(owners[i]);check(blue[i]==(visible&&!tinted)&&red[i]==(visible&&tinted),"Color partition preserves exactly each visible quad, no double draw");}
            }
            int legacy=part.rangeCount(hidden);check(legacy>=0,"Legacy visibility API remains callable between colored draws");
        }
        hidden.clear();wrong.clear();check(part.rangeCount(hidden,wrong,false)==1&&part.rangeCount(hidden,wrong,true)==0,"Air/unload reset restores all geometry to blue");
        wrong.set(1,true);hidden.set(1,true);check(!quads(part,hidden,wrong,true,owners.length)[1],"Completion hides red geometry before either color pass");
        wrong.set(2,true);check(quads(part,hidden,wrong,true,owners.length)[2],"Dynamic wrong-cell update recolors cached ownership without rebuild");
        var stone=Blocks.STONE.getDefaultState();var air=Blocks.AIR.getDefaultState();
        check(ProjectionRenderer1201.wrongBlock(true,Blocks.GLASS.getDefaultState(),stone),"Wrong real block receives red tint");
        var stairs=Blocks.OAK_STAIRS.getDefaultState();check(ProjectionRenderer1201.wrongBlock(true,stairs.with(Properties.HORIZONTAL_FACING,stairs.get(Properties.HORIZONTAL_FACING).getOpposite()),stairs),"Different raw properties on same block receive red tint");
        check(!ProjectionRenderer1201.wrongBlock(true,air,stone),"Missing block retains blue tint");
        check(!ProjectionRenderer1201.wrongBlock(false,stone,Blocks.GLASS.getDefaultState()),"Unloaded chunk never marks wrong");
        check(!ProjectionRenderer1201.wrongBlock(true,null,stone)&&!ProjectionRenderer1201.wrongBlock(true,stone,null),"Unknown actual/expected never marks wrong");
        check(!ProjectionRenderer1201.wrongBlock(true,stone,stone)&&ProjectionRenderer1201.completedBlock(true,stone,stone),"Correct placement clears wrong state and hides surface");
        return checks;
    }
    private static boolean[] quads(QuadVisibility.Part part,QuadVisibility.Mask hidden,QuadVisibility.Mask wrong,boolean red,int count){var result=new boolean[count];int ranges=part.rangeCount(hidden,wrong,red);for(int r=0;r<ranges;r++)for(int i=part.firstQuad(r);i<part.firstQuad(r)+part.quadCount(r);i++){check(!result[i],"Draw ranges never duplicate vertices");result[i]=true;}return result;}
    public static void main(String[] args){net.minecraft.SharedConstants.createGameVersion();net.minecraft.Bootstrap.initialize();System.out.println("ProjectionTintChecks: "+run()+" checks");}
}
