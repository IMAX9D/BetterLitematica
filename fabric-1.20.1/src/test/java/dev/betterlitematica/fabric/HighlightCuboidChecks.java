package dev.betterlitematica.fabric;
import dev.betterlitematica.core.*;
import java.util.*;
final class HighlightCuboidChecks {
    static int run(){int checks=0;var cells=new ArrayList<HighlightCuboids.Cell>();
        for(int x=-4;x<5;x++)for(int y=0;y<4;y++)for(int z=0;z<5;z++)cells.add(new HighlightCuboids.Cell(new Vec3i(x,y,z),1));
        var solid=HighlightCuboids.merge(cells);if(solid.size()!=1)throw new AssertionError("Solid cuboid must merge");checks++;
        cells.removeIf(c->c.position().equals(new Vec3i(0,1,2)));
        cells.add(new HighlightCuboids.Cell(new Vec3i(0,1,2),2));
        var random=new Random(49350);
        for(int round=0;round<40;round++){
            var expected=new HashSet<>(cells);var actual=new HashSet<HighlightCuboids.Cell>();
            for(var box:HighlightCuboids.merge(cells))for(int x=box.min().x();x<=box.max().x();x++)for(int y=box.min().y();y<=box.max().y();y++)for(int z=box.min().z();z<=box.max().z();z++){
                if(!actual.add(new HighlightCuboids.Cell(new Vec3i(x,y,z),box.group())))throw new AssertionError("Overlapping merged boxes");checks++;
            }
            if(!actual.equals(expected))throw new AssertionError("Merge filled a hole or changed type");checks++;
            if(!cells.isEmpty())cells.remove(random.nextInt(cells.size()));
        }
        return checks;
    }
}
