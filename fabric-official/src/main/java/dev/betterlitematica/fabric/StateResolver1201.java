package dev.betterlitematica.fabric;

import dev.betterlitematica.core.*;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.util.*;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import java.util.*;

/** Only this adapter knows Minecraft's registry, BlockState and state-transform API. */
final class StateResolver1201 {
    private final List<BlockStateSpec> source;
    private final BlockState[] resolved;
    private final Set<Integer> unsupported=new HashSet<>();
    private final Set<Integer> invalidStates=new HashSet<>();
    private final PlacementTransform transform;
    StateResolver1201(List<BlockStateSpec> source,PlacementTransform transform){this.source=source;resolved=new BlockState[source.size()];this.transform=transform;}
    BlockState resolve(int id){
        if(resolved[id]!=null)return resolved[id];BlockStateSpec spec=source.get(id);
        try{
            Identifier name=Identifier.parse(spec.name());
            if(!BuiltInRegistries.BLOCK.containsKey(name))throw new IllegalArgumentException("Unknown block "+name);
            Block block=BuiltInRegistries.BLOCK.getValue(name);BlockState state=block.defaultBlockState();
            for(var entry:spec.properties().entrySet()){
                Property<?> property=block.getStateDefinition().getProperty(entry.getKey());
                if(property==null)throw new IllegalArgumentException("Unknown property "+entry.getKey());
                state=with(state,property,entry.getValue());
            }
            if(transform.mirrorX())state=state.mirror(Mirror.FRONT_BACK);
            if(transform.mirrorZ())state=state.mirror(Mirror.LEFT_RIGHT);
            Rotation rotation=switch(transform.quarterTurns()){
                case 1->Rotation.CLOCKWISE_90;case 2->Rotation.CLOCKWISE_180;case 3->Rotation.COUNTERCLOCKWISE_90;default->Rotation.NONE;
            };
            resolved[id]=state.rotate(rotation);
        }catch(RuntimeException e){unsupported.add(id);invalidStates.add(id);resolved[id]=marker();}
        return resolved[id];
    }
    private static <T extends Comparable<T>> BlockState with(BlockState state,Property<T> property,String text){
        T value=property.getValue(text).orElseThrow(()->new IllegalArgumentException("Invalid property value "+text));return state.setValue(property,value);
    }
    void markUnsupported(int id){unsupported.add(id);}
    int unsupportedCount(){return unsupported.size();}
    boolean unresolved(int id){resolve(id);return unsupported.contains(id);}
    boolean unresolvedState(int id){resolve(id);return invalidStates.contains(id);}
    static BlockState marker(){return net.minecraft.core.registries.BuiltInRegistries.BLOCK.getValue(net.minecraft.resources.Identifier.fromNamespaceAndPath("minecraft","magenta_stained_glass")).defaultBlockState();}
    static BlockState checked(BlockStateSpec state){var resolver=new StateResolver1201(List.of(state),new PlacementTransform(Vec3i.ZERO,0,false,false));if(resolver.unresolved(0))throw new IllegalArgumentException("方块或状态不存在");return resolver.resolve(0);}
    static BlockState itemState(BlockState state,net.minecraft.world.item.ItemStack stack){
        return stack.getOrDefault(net.minecraft.core.component.DataComponents.BLOCK_STATE,net.minecraft.world.item.component.BlockItemStateProperties.EMPTY).apply(state);
    }
    private static <T extends Comparable<T>> BlockState itemProperty(BlockState state,Property<T> property,String value){var parsed=property.getValue(value);return parsed.isPresent()?state.setValue(property,parsed.get()):state;}
    static BlockStateSpec spec(BlockState state){var properties=new TreeMap<String,String>();state.getValues().forEach(e->properties.put(e.property().getName(),propertyValue(e.property(),e.value())));return new BlockStateSpec(BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString(),properties);}
    @SuppressWarnings({"rawtypes","unchecked"}) private static String propertyValue(Property property,Comparable value){return property.getName(value);}
    static BlockState unplace(BlockState state,PlacementTransform transform){
        state=state.rotate(switch(transform.quarterTurns()){case 1->Rotation.COUNTERCLOCKWISE_90;case 2->Rotation.CLOCKWISE_180;case 3->Rotation.CLOCKWISE_90;default->Rotation.NONE;});
        if(transform.mirrorZ())state=state.mirror(Mirror.LEFT_RIGHT);if(transform.mirrorX())state=state.mirror(Mirror.FRONT_BACK);return state;
    }
}
