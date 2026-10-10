package com.theproknightgamer.skein.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.theproknightgamer.skein.core.HookFor;
import com.theproknightgamer.skein.core.TickContext;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerCommonPacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Lets a worker's packets ride vanilla's per-tick batching instead of flushing
 * one at a time.
 *
 * <p>{@code MinecraftServer.tickChildren} calls {@code suspendFlushing()} on
 * every connection before the level loop and {@code resumeFlushing()} after it,
 * so for the whole of that loop vanilla writes packets without flushing and
 * flushes each connection exactly once at the end. Whether a given send takes
 * that path is decided by
 *
 * <pre>{@code boolean flag = !this.suspendFlushingOnServerThread || !this.server.isSameThread();}</pre>
 *
 * <p>which is a thread-identity test -- precisely the kind of gate Skein's
 * ownership model exists to replace -- and a worker fails it. So every packet
 * sent from a worker takes {@code writeAndFlush} rather than {@code write}: a
 * netty flush, and a write syscall, per packet instead of per connection per
 * tick. That is not hypothetical traffic. {@code ServerLevel.playSeededSound}
 * and {@code broadcastEntityEvent} are called straight out of ordinary mob
 * ticks, so every mob that takes damage or makes a sound is currently forcing a
 * flush, and the cluster tier made those ticks much more parallel.
 *
 * <p>Answering "yes, this is the ticking thread" for a Skein worker is
 * behaviour-preserving rather than a deferral: the packet is still handed to the
 * connection at the same moment and in the same order, and
 * {@code resumeFlushing()} flushes unconditionally at the end of the same tick.
 * Only the syscall moves.
 *
 * <p>Gated on the thread actually being inside a level tick, so a worker doing
 * anything else -- or a thread that merely happens to be a Skein worker between
 * ticks -- keeps vanilla's answer.
 *
 * <p>Nothing depends on it: without it a worker's packets are flushed one at a
 * time, which is slower and otherwise identical.
 */
@Mixin(ServerCommonPacketListenerImpl.class)
public abstract class ServerCommonPacketListenerMixin {

    @HookFor({})
    @WrapOperation(
            method = "send(Lnet/minecraft/network/protocol/Packet;Lnet/minecraft/network/PacketSendListener;)V",
            at = @At(value = "INVOKE",
                     target = "Lnet/minecraft/server/MinecraftServer;isSameThread()Z"))
    private boolean skein$batchFromWorkers(MinecraftServer server, Operation<Boolean> original) {
        return original.call(server) || TickContext.current() != null;
    }
}
