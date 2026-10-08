package dev.betterlitematica.fabric;

import net.minecraft.block.entity.BlockEntity;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.registry.RegistryWrapper;
import net.minecraft.storage.NbtReadView;
import net.minecraft.storage.NbtWriteView;
import net.minecraft.storage.ReadView;
import net.minecraft.util.ErrorReporter;

/** Keep serialization failures visible instead of accepting partial clipboard or entity data. */
final class StorageData {
    private StorageData() {}
    private static final ErrorReporter STRICT = new ErrorReporter() {
        public ErrorReporter makeChild(ErrorReporter.Context context) { return this; }
        public void report(ErrorReporter.Error error) { throw new IllegalArgumentException(error.getMessage()); }
    };
    static NbtWriteView write(RegistryWrapper.WrapperLookup registries) {
        return NbtWriteView.create(STRICT, registries);
    }
    static ReadView read(NbtCompound nbt, RegistryWrapper.WrapperLookup registries) {
        return NbtReadView.create(STRICT, registries, nbt);
    }
    static NbtCompound block(BlockEntity entity, RegistryWrapper.WrapperLookup registries) {
        var view = write(registries);
        entity.writeDataWithId(view);
        return view.getNbt();
    }
}
