package dev.betterlitematica.fabric;

import dev.betterlitematica.core.*;
import net.minecraft.block.*;
import net.minecraft.registry.Registries;
import net.minecraft.state.property.Property;
import net.minecraft.util.*;
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
            Identifier name=new Identifier(spec.name());
            if(!Registries.BLOCK.containsId(name))throw new IllegalArgumentException("Unknown block "+name);
            Block block=Registries.BLOCK.get(name);BlockState state=block.getDefaultState();
            for(var entry:spec.properties().entrySet()){
                Property<?> property=block.getStateManager().getProperty(entry.getKey());
                if(property==null)throw new IllegalArgumentException("Unknown property "+entry.getKey());
                state=with(state,property,entry.getValue());
            }
            if(transform.mirrorX())state=state.mirror(BlockMirror.FRONT_BACK);
            if(transform.mirrorZ())state=state.mirror(BlockMirror.LEFT_RIGHT);
            BlockRotation rotation=switch(transform.quarterTurns()){
                case 1->BlockRotation.CLOCKWISE_90;case 2->BlockRotation.CLOCKWISE_180;case 3->BlockRotation.COUNTERCLOCKWISE_90;default->BlockRotation.NONE;
            };
            resolved[id]=state.rotate(rotation);
        }catch(RuntimeException e){unsupported.add(id);invalidStates.add(id);resolved[id]=marker();}
        return resolved[id];
    }
    private static <T extends Comparable<T>> BlockState with(BlockState state,Property<T> property,String text){
        T value=property.parse(text).orElseThrow(()->new IllegalArgumentException("Invalid property value "+text));return state.with(property,value);
    }
    void markUnsupported(int id){unsupported.add(id);}
    int unsupportedCount(){return unsupported.size();}
    boolean unresolved(int id){resolve(id);return unsupported.contains(id);}
    boolean unresolvedState(int id){resolve(id);return invalidStates.contains(id);}
    static BlockState marker(){return Blocks.MAGENTA_STAINED_GLASS.getDefaultState();}
    static BlockState checked(BlockStateSpec state){var resolver=new StateResolver1201(List.of(state),new PlacementTransform(Vec3i.ZERO,0,false,false));if(resolver.unresolved(0))throw new IllegalArgumentException("方块或状态不存在");return resolver.resolve(0);}
    static BlockState itemState(BlockState state,net.minecraft.item.ItemStack stack){
        return stack.getOrDefault(net.minecraft.component.DataComponentTypes.BLOCK_STATE,net.minecraft.component.type.BlockStateComponent.DEFAULT).applyToState(state);
    }
    private static <T extends Comparable<T>> BlockState itemProperty(BlockState state,Property<T> property,String value){var parsed=property.parse(value);return parsed.isPresent()?state.with(property,parsed.get()):state;}
    static BlockStateSpec spec(BlockState state){var properties=new TreeMap<String,String>();state.getEntries().forEach((property,value)->properties.put(property.getName(),propertyValue(property,value)));return new BlockStateSpec(Registries.BLOCK.getId(state.getBlock()).toString(),properties);}
    @SuppressWarnings({"rawtypes","unchecked"}) private static String propertyValue(Property property,Comparable value){return property.name(value);}
    static BlockState unplace(BlockState state,PlacementTransform transform){
        state=state.rotate(switch(transform.quarterTurns()){case 1->BlockRotation.COUNTERCLOCKWISE_90;case 2->BlockRotation.CLOCKWISE_180;case 3->BlockRotation.CLOCKWISE_90;default->BlockRotation.NONE;});
        if(transform.mirrorZ())state=state.mirror(BlockMirror.LEFT_RIGHT);if(transform.mirrorX())state=state.mirror(BlockMirror.FRONT_BACK);return state;
    }
}
