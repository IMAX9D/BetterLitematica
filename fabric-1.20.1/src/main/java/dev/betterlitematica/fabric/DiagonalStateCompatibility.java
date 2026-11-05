package dev.betterlitematica.fabric;

import net.minecraft.block.*;
import net.fabricmc.loader.api.FabricLoader;
import java.util.*;

/** Optional Diagonal Blocks v2 conversion registry; never equate unrelated blocks sharing an item. */
final class DiagonalStateCompatibility {
    private DiagonalStateCompatibility() {}
    private static final class Registry {
        static final Map<Block,Block> BASES=read();
        private static Map<Block,Block> read(){
            if(!FabricLoader.getInstance().isModLoaded("diagonalblocks"))return Map.of();
            var bases=new IdentityHashMap<Block,Block>();
            try{
                Class<?> api=Class.forName("fuzs.diagonalblocks.api.v2.DiagonalBlockType");
                var conversions=api.getMethod("getBlockConversions");
                for(Object type:(Iterable<?>)api.getField("TYPES").get(null)){
                    for(var entry:((Map<?,?>)conversions.invoke(type)).entrySet()){
                        if(entry.getKey() instanceof Block base&&entry.getValue() instanceof Block diagonal
                            &&(base instanceof FenceBlock&&diagonal instanceof FenceBlock
                                ||base instanceof PaneBlock&&diagonal instanceof PaneBlock
                                ||base instanceof WallBlock&&diagonal instanceof WallBlock))bases.put(diagonal,base);
                    }
                }
            }catch(ReflectiveOperationException|LinkageError incompatible){return Map.of();}
            return Collections.unmodifiableMap(bases);
        }
    }
    static Block base(Block block){return Registry.BASES.getOrDefault(block,block);}
    static boolean sameBlock(BlockState a,BlockState b){return a!=null&&b!=null&&base(a.getBlock())==base(b.getBlock());}
    static BlockState canonical(BlockState state){
        Block block=base(state.getBlock());
        return block==state.getBlock()?state:block.getStateWithProperties(state);
    }
}
