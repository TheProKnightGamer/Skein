package com.theproknightgamer.skein.compat;

import io.netty.channel.Channel;
import io.netty.channel.embedded.EmbeddedChannel;
import net.minecraft.network.Connection;
import net.minecraft.network.DisconnectionDetails;
import net.minecraft.network.PacketListener;
import io.netty.channel.ChannelFutureListener;
import net.minecraft.network.ProtocolInfo;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.common.ClientboundKeepAlivePacket;
import net.minecraft.network.protocol.common.ServerboundKeepAlivePacket;
import net.minecraft.network.protocol.game.ClientboundBundlePacket;
import net.minecraft.network.protocol.game.ClientboundChunkBatchFinishedPacket;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.server.level.ServerPlayer;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;

/**
 * An in-memory client for a benchmark player: like the integrated server's local
 * connection, packets are never encoded, and like a real client it acknowledges
 * chunk batches and answers keep-alives, so chunk sending keeps flowing.
 */
final class BenchConnection extends Connection {
    private final Channel fake = new EmbeddedChannel();
    private int batchesToAck;
    private long keepAlive = -1;
    long packets;
    long chunkBatches;
    long chunksSent;
    /** Sees every packet as it is handed over, for tests that record them. */
    java.util.function.Consumer<Packet<?>> listener;

    BenchConnection() {
        super(PacketFlow.SERVERBOUND);
    }

    /** What the client would have sent back since the last tick; call on the server thread. */
    void answer(ServerPlayer player) {
        for (; this.batchesToAck > 0; this.batchesToAck--) {
            // The vanilla client's ChunkBatchSizeCalculator settles near its cap on a fast machine.
            player.connection.chunkSender.onChunkBatchReceivedByClient(64.0F);
        }
        if (this.keepAlive >= 0) {
            player.connection.handleKeepAlive(new ServerboundKeepAlivePacket(this.keepAlive));
            this.keepAlive = -1;
        }
    }

    private void observe(Packet<?> packet) {
        if (this.listener != null) this.listener.accept(packet);
        this.packets++;
        if (packet instanceof ClientboundChunkBatchFinishedPacket) {
            this.batchesToAck++;
            this.chunkBatches++;
        } else if (packet instanceof ClientboundLevelChunkWithLightPacket) {
            this.chunksSent++;
        } else if (packet instanceof ClientboundKeepAlivePacket keepAlive) {
            this.keepAlive = keepAlive.getId();
        } else if (packet instanceof ClientboundBundlePacket bundle) {
            for (Packet<?> inner : bundle.subPackets()) observe(inner);
        }
    }

    @Override public Channel channel() { return this.fake; }
    @Override public void send(Packet<?> packet) { observe(packet); }
    @Override public void send(Packet<?> packet, ChannelFutureListener listener) { observe(packet); }
    @Override public void send(Packet<?> packet, ChannelFutureListener listener, boolean flush) { observe(packet); }
    @Override public void flushChannel() {}
    @Override public void tick() {}
    @Override public void setReadOnly() {}
    @Override public void handleDisconnection() {}
    @Override public void setupCompression(int threshold, boolean validate) {}
    @Override public <T extends PacketListener> void setupInboundProtocol(ProtocolInfo<T> protocol, T listener) {}
    @Override public void setupOutboundProtocol(ProtocolInfo<?> protocol) {}
    @Override public boolean isConnected() { return true; }
    @Override public boolean isMemoryConnection() { return true; }
    @Override public SocketAddress getRemoteAddress() { return new InetSocketAddress(InetAddress.getLoopbackAddress(), 0); }
    @Override public void disconnect(Component reason) {}
    @Override public void disconnect(DisconnectionDetails details) {}
}
