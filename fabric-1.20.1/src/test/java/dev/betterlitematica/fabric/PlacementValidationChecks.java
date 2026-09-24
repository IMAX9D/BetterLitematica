package dev.betterlitematica.fabric;

import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.item.BlockItem;
import net.minecraft.item.Item;
import net.minecraft.item.ItemPlacementContext;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;

/** Real 1.20.1 states/items; never creates a client, world, or registered test item. */
public final class PlacementValidationChecks {
    private PlacementValidationChecks() { }
    private static final class Checks {
        int count;
        void require(boolean value, String message) {
            count++;
            if (!value) throw new AssertionError(message);
        }
    }

    public static int run() {
        var checks = new Checks();
        int ordinaryItems = 0, ordinaryStates = 0;
        // A null context deliberately fails on any second call to vanilla canPlace.
        // These states represent the already-validated getPlacementState result.
        for (Item item : Registries.ITEM) {
            if (item.getClass() != BlockItem.class) continue;
            ordinaryItems++;
            var blockItem = (BlockItem) item;
            for (BlockState state : blockItem.getBlock().getStateManager().getStates()) {
                ordinaryStates++;
                checks.require(AccuratePlacement.validatePlacement(blockItem, null, state, state) == state,
                        "Unchanged exact BlockItem does not access world again: " + state);
                checks.require(AccuratePlacement.validatePlacement(blockItem, null, state, null) == null,
                        "Rejected decoded state remains rejected: " + state);
            }
            checks.require(AccuratePlacement.validatePlacement(blockItem, null, null, null) == null,
                    "Null base and result never become a successful placement");
        }
        checks.require(ordinaryItems > 100 && ordinaryStates > 1000,
                "Test covers real registered ordinary items and state variants");

        var custom = allocateWithoutRegistration(ControlledItem.class);
        BlockState base = Blocks.OAK_LOG.getDefaultState();
        BlockState changed = base.with(net.minecraft.state.property.Properties.AXIS,
                net.minecraft.util.math.Direction.Axis.X);
        checks.require(base != changed, "Changed state actually differs from vanilla result");

        custom.allowed = false;
        checks.require(AccuratePlacement.validatePlacement(custom, null, base, base) == null,
                "Custom same-state result must still honor canPlace rejection");
        checks.require(custom.calls == 1 && custom.lastState == base,
                "Custom same-state canPlace called exactly once with original state");
        custom.allowed = true;
        checks.require(AccuratePlacement.validatePlacement(custom, null, base, base) == base,
                "Custom same-state result may pass its own validation");
        checks.require(custom.calls == 2, "Accepted custom same-state is also revalidated");

        custom.allowed = false;
        checks.require(AccuratePlacement.validatePlacement(custom, null, base, changed) == null,
                "Decoded changed state cannot bypass custom rejection");
        checks.require(custom.calls == 3 && custom.lastState == changed,
                "Validation sees changed state, not the formerly valid base");
        custom.allowed = true;
        checks.require(AccuratePlacement.validatePlacement(custom, null, base, changed) == changed,
                "Decoded changed state returns precisely the validated result");
        checks.require(custom.calls == 4, "Changed result is validated exactly once");
        checks.require(AccuratePlacement.validatePlacement(custom, null, base, null) == null
                        && AccuratePlacement.validatePlacement(custom, null, null, null) == null,
                "Null results always reject, including custom items");
        checks.require(custom.calls == 4, "Null results never call custom validation with null state");

        // Ordinary changed-state path must reach vanilla canPlace, which cannot use a null context.
        boolean changedReachedWorldValidation = false;
        try {
            AccuratePlacement.validatePlacement((BlockItem) Items.OAK_LOG, null, base, changed);
        } catch (NullPointerException expected) {
            changedReachedWorldValidation = true;
        }
        checks.require(changedReachedWorldValidation,
                "Even an exact ordinary BlockItem revalidates changed decoded state");

        // A real registered subclass must never take the exact-class shortcut either.
        checks.require(Items.RED_BED instanceof BlockItem && Items.RED_BED.getClass() != BlockItem.class,
                "Real bed item is a custom placement subclass");
        boolean subclassReachedWorldValidation = false;
        BlockState bed = Blocks.RED_BED.getDefaultState();
        try {
            AccuratePlacement.validatePlacement((BlockItem) Items.RED_BED, null, bed, bed);
        } catch (NullPointerException expected) {
            subclassReachedWorldValidation = true;
        }
        checks.require(subclassReachedWorldValidation,
                "Real same-state BedItem still reaches required world validation");
        return checks.count;
    }

    /** Only canPlace is used; allocating avoids mutating the already-frozen item registry. */
    private static <T> T allocateWithoutRegistration(Class<T> type) {
        try {
            Class<?> unsafeType = Class.forName("sun.misc.Unsafe");
            var field = unsafeType.getDeclaredField("theUnsafe");
            field.setAccessible(true);
            Object unsafe = field.get(null);
            return type.cast(unsafeType.getMethod("allocateInstance", Class.class).invoke(unsafe, type));
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError("Cannot allocate isolated validation test double", failure);
        }
    }

    private static final class ControlledItem extends BlockItem {
        int calls;
        boolean allowed;
        BlockState lastState;
        private ControlledItem(Block block, Item.Settings settings) { super(block, settings); }
        @Override public boolean canPlace(ItemPlacementContext context, BlockState state) {
            calls++;
            lastState = state;
            return allowed;
        }
    }
}
