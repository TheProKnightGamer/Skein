package com.theproknightgamer.skein.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.theproknightgamer.skein.core.HookFor;
import com.theproknightgamer.skein.core.Hooks;
import com.theproknightgamer.skein.core.TickContext;
import net.minecraft.world.level.Level;
import net.minecraftforge.common.ForgeHooks;
import net.minecraftforge.common.util.BlockSnapshot;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import java.util.ArrayList;

/**
 * The writer side of NeoForge's block-snapshot capture bracket.
 *
 * <p>{@code onPlaceItemIntoWorld} sets {@code level.captureBlockSnapshots},
 * runs the item's use, and then reads back everything that was placed so it can
 * be offered to {@code BlockPlaceEvent} as one group. Both the flag and the list
 * live on the level, which is fine when a level only ever has one thread in it.
 *
 * <p>It is not fine during a parallel pass, and the flag is the dangerous half:
 * while it is set, {@code Level.setBlock} skips {@code markAndNotifyBlock}
 * entirely. One thread opening a bracket would stop every other thread's block
 * changes in that dimension from reaching clients or triggering physics -- a
 * block that is placed, is solid, and is invisible.
 *
 * <p>{@link LevelMixin} and {@link LevelChunkMixin} take over the reads; this
 * takes over the writes, so the whole bracket belongs to the thread that opened
 * it. Outside a pass every access still goes to the level's own fields.
 *
 * <p>Every access here is per thread only while a pass defers writes, so a
 * missing piece costs the in-level phases and nothing else.
 */
@HookFor(Hooks.Feature.IN_LEVEL)
@Mixin(value = ForgeHooks.class, remap = false)
public abstract class CommonHooksMixin {

    @WrapOperation(
            method = "onPlaceItemIntoWorld",
            at = @At(value = "FIELD", opcode = Opcodes.PUTFIELD,
                     target = "Lnet/minecraft/world/level/Level;captureBlockSnapshots:Z"))
    private static void skein$setCapturing(Level level, boolean capturing, Operation<Void> original) {
        if (TickContext.deferring()) {
            TickContext.local().capturing = capturing;
        } else {
            original.call(level, capturing);
        }
    }

    @WrapOperation(
            method = "onPlaceItemIntoWorld",
            at = @At(value = "FIELD", opcode = Opcodes.PUTFIELD,
                     target = "Lnet/minecraft/world/level/Level;restoringBlockSnapshots:Z"))
    private static void skein$setRestoring(Level level, boolean restoring, Operation<Void> original) {
        if (TickContext.deferring()) {
            TickContext.local().restoring = restoring;
        } else {
            original.call(level, restoring);
        }
    }

    @WrapOperation(
            method = "onPlaceItemIntoWorld",
            at = @At(value = "FIELD", opcode = Opcodes.GETFIELD,
                     target = "Lnet/minecraft/world/level/Level;capturedBlockSnapshots:Ljava/util/ArrayList;"))
    private static ArrayList<BlockSnapshot> skein$captured(Level level, Operation<ArrayList<BlockSnapshot>> original) {
        return TickContext.deferring() ? TickContext.local().captured : original.call(level);
    }
}
