package net.minecraft.world.entity;

/** Position-only fixture; no Minecraft runtime is loaded by the core tests. */
public final class Entity {
    private final double x, y, z;
    public final double reach;

    public Entity(double x, double y, double z, double reach) {
        this.x = x;
        this.y = y;
        this.z = z;
        this.reach = reach;
    }

    public double getX() { return x; }
    public double getY() { return y; }
    public double getZ() { return z; }
}
