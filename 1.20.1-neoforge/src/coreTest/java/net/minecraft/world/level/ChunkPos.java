package net.minecraft.world.level;

public final class ChunkPos {
    public static long asLong(int x, int z) {
        return (x & 0xffffffffL) | (z & 0xffffffffL) << 32;
    }
}
