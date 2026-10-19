package com.theproknightgamer.skein.core;

import net.minecraft.world.entity.Entity;

import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicIntegerArray;

/** Deterministic reference checks plus opt-in, warmed scheduling microbenchmarks. */
public final class CoreVerification {
    private static volatile long sink;
    private static final java.util.function.ToDoubleFunction<Entity> REACH = e -> e.reach;

    public static void main(String[] args) throws Exception {
        if (args.length != 0 && args[0].equals("--benchmark")) {
            benchmark();
            return;
        }
        entityLevels();
        spatialBounds();
        pointGrid();
        clusterPartition();
        clusterPartitionLinks();
        gridPartition();
        widePartition();
        pool();
        System.out.println("Core verification passed: entity precedence, point queries, component ordering, grid safety, wide members, nested barriers.");
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static List<Entity> entities(Random random, int count, double spread, boolean positive) {
        List<Entity> result = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            double reach = positive || random.nextInt(10) == 0 ? 1 + random.nextInt(6) : 0;
            result.add(new Entity((random.nextDouble() - .5) * spread,
                    (random.nextDouble() - .5) * spread, (random.nextDouble() - .5) * spread, reach));
        }
        return result;
    }

    private static boolean near(Entity a, Entity b, double reach) {
        return Math.abs(a.getX() - b.getX()) <= reach && Math.abs(a.getY() - b.getY()) <= reach
                && Math.abs(a.getZ() - b.getZ()) <= reach;
    }

    private static void entityLevels() {
        Random random = new Random(71432);
        EntityLevels actual = new EntityLevels();
        for (int trial = 0; trial < 1500; trial++) {
            List<Entity> items = entities(random, random.nextInt(180), trial % 2 == 0 ? 10 : 100, trial % 3 == 0);
            verifyLevels(actual, items);
        }
        verifyLevels(actual, List.of(new Entity(-6, 0, 0, 6), new Entity(0, 0, 0, 0),
                new Entity(6, 0, 0, 6), new Entity(0, 6, 0, 0)));
        verifyLevels(actual, java.util.Collections.nCopies(10000, new Entity(0, 0, 0, 0)));
        verifyLevels(actual, List.of());
        List<Entity> chain = new ArrayList<>();
        for (int i = 0; i < 100; i++) chain.add(new Entity(i, 0, 0, i < 5 ? 1 : 0));
        int fullCount = actual.build(chain, REACH);
        check(fullCount > 1, "limit fixture needs several levels");
        for (int limit = 0; limit <= fullCount + 1; limit++) {
            int count = actual.build(chain, REACH, limit);
            check(count == (limit < fullCount ? -1 : fullCount), "caller level limit mismatch");
        }
        verifyLevels(actual, entities(random, 300, 100, false));
        // Exercise both sides of the influence-index cutoff, including changes
        // of strategy and table size on the same reusable instance.
        for (int percent : new int[] { 0, 1, 10, 24, 25, 26, 50, 100 }) {
            for (int trial = 0; trial < 60; trial++) {
                List<Entity> items = new ArrayList<>();
                for (int i = 0; i < 160; i++) {
                    double reach = random.nextInt(100) < percent ? .125 * (1 + random.nextInt(40)) : 0;
                    items.add(new Entity(random.nextDouble() * 48 - 24, random.nextDouble() * 48 - 24,
                            random.nextDouble() * 48 - 24, reach));
                }
                verifyLevels(actual, items);
            }
        }
        for (double reach : new double[] { .125, 1, 2, 6 }) {
            for (double origin : new double[] { -30_000_000, -12, 0, 12, 30_000_000 }) {
                double at = origin + reach;
                for (double point : new double[] { Math.nextDown(origin), origin, Math.nextUp(origin) }) {
                    for (boolean reverse : new boolean[] { false, true }) {
                        List<Entity> edge = new ArrayList<>();
                        Entity positive = new Entity(at, at, at, reach);
                        Entity zero = new Entity(point, point, point, 0);
                        edge.add(reverse ? zero : positive);
                        edge.add(reverse ? positive : zero);
                        verifyLevels(actual, edge); // ordinary index
                        for (int i = 0; i < 30; i++) edge.add(new Entity(origin + 100 + i * 20, 0, 0, 0));
                        verifyLevels(actual, edge); // influence index
                    }
                }
            }
        }
        verifyLevels(actual, List.of(new Entity(-Math.ulp(1.0) / 2, 0, 0, 0), new Entity(1, 0, 0, 1)));
        // Tiny radii at large world coordinates saturate integer bucket keys;
        // inclusive loops must terminate even when a bound is Integer.MAX_VALUE.
        verifyLevels(actual, List.of(new Entity(30_000_000, 0, 0, .001), new Entity(30_000_000, 0, 0, 0)));
    }

    private static void verifyLevels(EntityLevels actual, List<Entity> items) {
        int[] expected = new int[items.size()];
        int highest = -1;
        for (int i = 0; i < items.size(); i++) {
            for (int j = 0; j < i; j++) {
                double reach = Math.max(items.get(i).reach, items.get(j).reach);
                if (reach > 0 && near(items.get(i), items.get(j), reach)) {
                    expected[i] = Math.max(expected[i], expected[j] + 1);
                }
            }
            highest = Math.max(highest, expected[i]);
        }
        int count = actual.build(items, e -> e.reach);
        boolean decline = items.isEmpty() || highest + 1 > Math.max(4, items.size() / 4);
        check((count < 0) == decline, "graph fallback differs from reference");
        if (decline) return;
        check(count == highest + 1, "wrong level count");
        boolean[] seen = new boolean[items.size()];
        for (int l = 0; l < count; l++) {
            int previous = -1;
            for (int j = actual.levelStart()[l]; j < actual.levelStart()[l + 1]; j++) {
                int item = actual.order()[j];
                check(!seen[item] && item > previous && expected[item] == l, "precedence/order mismatch");
                seen[item] = true;
                previous = item;
            }
        }
        for (boolean present : seen) check(present, "entity missing");
    }

    private static void pointGrid() {
        Random random = new Random(98231);
        PointGrid grid = new PointGrid();
        for (int trial = 0; trial < 200; trial++) {
            List<Entity> points = entities(random, random.nextInt(250), 80, false);
            double reach = trial % 2 == 0 ? 6 : .25;
            grid.build(points, reach);
            for (Entity query : entities(random, 100, 80, false)) {
                boolean expected = points.stream().anyMatch(p -> near(p, query, reach));
                check(grid.anyWithin(query.getX(), query.getY(), query.getZ(), reach) == expected, "point query mismatch");
            }
        }
        grid.build(List.of(new Entity(-6, 0, 6, 0)), 6);
        check(grid.anyWithin(0, 0, 0, 6), "inclusive point boundary");
        grid.build(List.of(), 6);
        check(grid.isEmpty() && !grid.anyWithin(0, 0, 0, 6), "stale point after clear");
        for (int count : new int[] { 1, 8, 9, 4000, 3, 32, 0, 9 }) {
            List<Entity> points = entities(random, count, 80, false);
            grid.build(points, 2);
            for (Entity query : entities(random, 100, 80, false)) {
                for (double reach : new double[] { 0, 1, 2, 4 }) {
                    boolean expected = points.stream().anyMatch(p -> near(p, query, reach));
                    check(grid.anyWithin(query.getX(), query.getY(), query.getZ(), reach) == expected,
                            "point-grid strategy/size transition mismatch");
                }
            }
        }
        for (double point : new double[] { -Math.ulp(1.0) / 2, 0, Math.nextUp(0.0) }) {
            List<Entity> points = new ArrayList<>();
            points.add(new Entity(point, 0, 0, 0));
            for (int i = 0; i < 10; i++) points.add(new Entity(100 + i, 0, 0, 0));
            grid.build(points, 1);
            check(grid.anyWithin(1, 0, 0, 1), "rounded distance boundary lost");
        }
        // Filtered queries, including ones far wider than the buckets were
        // built for: the exact projectile test pads its reach by the widest
        // outsider hitbox, which can be many buckets across.
        for (int trial = 0; trial < 120; trial++) {
            List<Entity> points = entities(random, random.nextInt(300), 80, false);
            grid.build(points, trial % 3 == 0 ? .5 : 4);
            int modulus = 1 + random.nextInt(4);
            for (Entity query : entities(random, 60, 80, false)) {
                for (double reach : new double[] { 0, 3, 17, 90 }) {
                    boolean expected = false;
                    for (int j = 0; j < points.size(); j++) {
                        expected |= j % modulus == 0 && near(points.get(j), query, reach);
                    }
                    boolean[] visited = new boolean[points.size()];
                    boolean actual = grid.anyWithin(query.getX(), query.getY(), query.getZ(), reach, j -> {
                        visited[j] = true;
                        return j % modulus == 0;
                    });
                    check(actual == expected, "filtered point query mismatch");
                    for (int j = 0; j < points.size(); j++) {
                        check(!visited[j] || near(points.get(j), query, reach),
                                "filtered point query tested a point out of reach");
                    }
                }
            }
        }
        grid.build(entities(random, 50, 80, false), .001);
        check(grid.anyWithin(0, 0, 0, 1.0e9, j -> true), "saturated bucket range must fall back to a scan");
    }

    private static void spatialBounds() {
        Random random = new Random(6187);
        for (int trial = 0; trial < 50000; trial++) {
            double coordinate = (random.nextDouble() - .5) * 60_000_000;
            double reach = Math.scalb(1 + random.nextDouble(), random.nextInt(18) - 12);
            double bucket = 2 * reach;
            double point = coordinate + (random.nextBoolean() ? reach : -reach);
            point = switch (trial % 3) {
                case 0 -> Math.nextDown(point);
                case 1 -> Math.nextUp(point);
                default -> point;
            };
            if (Math.abs(point - coordinate) <= reach) {
                int cell = SpatialBounds.floor(point / bucket);
                check(cell >= SpatialBounds.lower(coordinate, reach, bucket)
                                && cell <= SpatialBounds.upper(coordinate, reach, bucket),
                        "rounded distance escaped conservative bucket bounds");
            }
        }
    }

    private static void clusterPartition() {
        Random random = new Random(9182);
        ClusterPartition<Integer> partition = new ClusterPartition<>();
        for (int trial = 0; trial < 400; trial++) {
            int count = random.nextInt(200), link = 1 + random.nextInt(100);
            int[] x = new int[count], z = new int[count];
            int[] parent = new int[count];
            partition.begin(link);
            for (int i = 0; i < count; i++) {
                x[i] = random.nextInt(2000) - 1000;
                z[i] = random.nextInt(2000) - 1000;
                parent[i] = i;
                partition.add(i, x[i], z[i]);
            }
            // Independent, quadratic bucket-box connectivity reference.
            int[] minX = x.clone(), maxX = x.clone(), minZ = z.clone(), maxZ = z.clone();
            for (int i = 0; i < count; i++) {
                for (int j = 0; j < count; j++) {
                    if (sameBucket(x, z, link, i, j)) {
                        minX[i] = Math.min(minX[i], x[j]); maxX[i] = Math.max(maxX[i], x[j]);
                        minZ[i] = Math.min(minZ[i], z[j]); maxZ[i] = Math.max(maxZ[i], z[j]);
                    }
                }
            }
            for (int i = 0; i < count; i++) {
                for (int j = 0; j < count; j++) {
                    if ((long) minX[j] - maxX[i] <= link && (long) minX[i] - maxX[j] <= link
                            && (long) minZ[j] - maxZ[i] <= link && (long) minZ[i] - maxZ[j] <= link) {
                        parent[root(parent, j)] = root(parent, i);
                    }
                }
            }
            partition.finish();
            int[] groupOf = new int[count];
            Arrays.fill(groupOf, -1);
            for (int g = 0; g < partition.groups().size(); g++) {
                int previous = -1;
                for (int item : partition.groups().get(g).items()) {
                    check(item > previous && groupOf[item] == -1, "component order/duplicate");
                    previous = item;
                    groupOf[item] = g;
                }
            }
            for (int i = 0; i < count; i++) {
                check(groupOf[i] >= 0, "missing scheduled item");
                for (int j = 0; j < count; j++) {
                    check((groupOf[i] == groupOf[j]) == (root(parent, i) == root(parent, j)), "component mismatch");
                }
            }
            partition.release();
        }
        partition.begin(1);
        partition.add(0, -30_000_000, -30_000_000);
        partition.add(1, 30_000_000, 30_000_000);
        partition.add(2, -30_000_000, -29_999_999);
        partition.finish();
        check(partition.groups().size() == 2, "world-border bucket coordinates aliased");
        check(partition.groups().get(0).items().equals(List.of(0, 2)), "world-border ordering");
        partition.release();
        partition.begin(1);
        for (int x : new int[] { 0, 1 << 21, Integer.MIN_VALUE, Integer.MAX_VALUE }) {
            partition.add(x, x, 0);
        }
        partition.finish();
        check(partition.groups().size() == 4, "truncated keys or overflowing neighbour gaps merged distant buckets");
        partition.release();
    }

    /**
     * Work filed with links of its own: joined at the mean of the two, never
     * further apart than a pair that could reach each other, and in the same
     * components as a quadratic reference over the same bucket boxes.
     */
    private static void clusterPartitionLinks() {
        Random random = new Random(4417);
        ClusterPartition<Integer> partition = new ClusterPartition<>();
        for (int trial = 0; trial < 400; trial++) {
            int count = random.nextInt(160), link = 2 + random.nextInt(100);
            int[] x = new int[count], z = new int[count], span = new int[count];
            int[] parent = new int[count];
            partition.begin(link);
            for (int i = 0; i < count; i++) {
                x[i] = random.nextInt(800) - 400;
                z[i] = random.nextInt(800) - 400;
                span[i] = random.nextInt(link + 1);
                parent[i] = i;
                partition.addLinking(i, x[i], z[i], span[i]);
            }
            int[] minX = x.clone(), maxX = x.clone(), minZ = z.clone(), maxZ = z.clone();
            int[] widest = span.clone();
            for (int i = 0; i < count; i++) {
                for (int j = 0; j < count; j++) {
                    if (sameBucket(x, z, link, i, j)) {
                        minX[i] = Math.min(minX[i], x[j]); maxX[i] = Math.max(maxX[i], x[j]);
                        minZ[i] = Math.min(minZ[i], z[j]); maxZ[i] = Math.max(maxZ[i], z[j]);
                        widest[i] = Math.max(widest[i], span[j]);
                    }
                }
            }
            for (int i = 0; i < count; i++) {
                for (int j = 0; j < count; j++) {
                    long joined = (long) widest[i] + widest[j];
                    if (2L * Math.max(0, Math.max(minX[j] - maxX[i], minX[i] - maxX[j])) <= joined
                            && 2L * Math.max(0, Math.max(minZ[j] - maxZ[i], minZ[i] - maxZ[j])) <= joined) {
                        parent[root(parent, j)] = root(parent, i);
                    }
                }
            }
            partition.finish();
            int[] groupOf = new int[count];
            Arrays.fill(groupOf, -1);
            for (int g = 0; g < partition.groups().size(); g++) {
                int previous = -1;
                ClusterPartition.Group<Integer> group = partition.groups().get(g);
                for (int k = 0; k < group.items().size(); k++) {
                    int item = group.items().get(k);
                    check(item > previous && groupOf[item] == -1, "linked component order/duplicate");
                    check(group.ranks().getInt(k) == item, "rank is not the filing order");
                    previous = item;
                    groupOf[item] = g;
                }
            }
            for (int i = 0; i < count; i++) {
                check(groupOf[i] >= 0, "missing linked item");
                for (int j = 0; j < count; j++) {
                    check((groupOf[i] == groupOf[j]) == (root(parent, i) == root(parent, j)), "linked component mismatch");
                    long reach = (long) span[i] + span[j];
                    if (2L * Math.abs(x[i] - x[j]) <= reach && 2L * Math.abs(z[i] - z[j]) <= reach) {
                        check(groupOf[i] == groupOf[j], "a pair within its own link was split");
                    }
                }
            }
            partition.release();
        }
        // A long reach is filed as points that far out, joined to the work.
        partition.begin(80);
        partition.add(0, 0, 0);
        partition.add(1, 120, 0);
        partition.finish();
        check(partition.groups().size() == 2, "work 120 apart joined at a link of 80");
        partition.release();
        partition.begin(80);
        partition.add(0, 0, 0, 42);
        partition.add(1, 120, 0);
        partition.add(2, 0, -300);
        partition.finish();
        check(partition.groups().size() == 2, "a 42-block reach did not join work 78 past it");
        check(partition.groups().get(0).items().equals(List.of(0, 1)), "reach joined the wrong work");
        partition.release();
    }

    private static boolean sameBucket(int[] x, int[] z, int link, int i, int j) {
        return Math.floorDiv(x[i], link) == Math.floorDiv(x[j], link)
                && Math.floorDiv(z[i], link) == Math.floorDiv(z[j], link);
    }

    private static int root(int[] parent, int node) {
        while (parent[node] != node) node = parent[node];
        return node;
    }

    private static void gridPartition() {
        Random random = new Random(7214);
        Partition<Integer> partition = new Partition<>();
        for (int trial = 0; trial < 100; trial++) {
            Grid grid = new Grid(128 + 32 * (trial % 5), 32);
            partition.begin(grid);
            for (int i = 0; i < 1000; i++) {
                double x = random.nextDouble() * 60000 - 30000, z = random.nextDouble() * 60000 - 30000;
                double span = trial % 3 == 0 ? 16 : 0;
                partition.place(i, x, x + span, z, z + span);
                for (double min : new double[] { x, z }) {
                    int axis = grid.axis(min, min + span);
                    if (axis >= 0) {
                        double start = (double) grid.cell(min, axis) * grid.regionBlocks() + axis * grid.regionBlocks() / 2;
                        check(min - start >= 32 && start + grid.regionBlocks() - min - span >= 32, "unsafe grid interior");
                    }
                }
            }
            partition.fileWide();
            int count = partition.sort() + partition.serial().size();
            check(count == 1000 && partition.all().size() == 1000, "partition lost items");
            boolean[] seen = new boolean[1000];
            for (int pass = 0; pass < Partition.PASSES; pass++) {
                Partition.Cell<Integer> previous = null;
                for (Partition.Cell<Integer> cell : partition.pass(pass)) {
                    check(previous == null || previous.items().size() > cell.items().size()
                                    || previous.items().size() == cell.items().size() && previous.key < cell.key,
                            "cell sort order changed");
                    previous = cell;
                    int lastItem = -1;
                    for (int item : cell.items()) {
                        check(!seen[item] && item > lastItem, "cell duplicated or reordered an item");
                        seen[item] = true;
                        lastItem = item;
                    }
                }
            }
            partition.collapse(0);
            partition.release();
            check(partition.all().isEmpty(), "partition retained items");
        }
        for (int width : new int[] { 64, 80, 127, 128, 160, 384 }) {
            for (int margin : new int[] { 0, 1, 16, 32 }) {
                Grid grid = new Grid(width, margin);
                for (int cell = -3; cell <= 3; cell++) {
                    for (int offset : new int[] { 0, margin, width / 2, width - margin }) {
                        double edge = cell * width + offset;
                        for (double min : new double[] { Math.nextDown(edge), edge, Math.nextUp(edge) }) {
                            for (double span : new double[] { 0, 1, 16, 65 }) {
                                int expected = -1;
                                for (int axis = 0; axis < 2; axis++) {
                                    double shifted = min - axis * (width / 2);
                                    double start = Math.floor(shifted / width) * width;
                                    if (shifted - start >= margin
                                            && start + width - (min + span - axis * (width / 2)) >= margin) {
                                        expected = axis;
                                        break;
                                    }
                                }
                                check(grid.axis(min, min + span) == expected, "grid boundary assignment changed");
                                long packed = grid.locate(min, min + span);
                                if (expected < 0) check(packed == Grid.NO_CELL, "invalid span accepted");
                                else check((packed >> 1) == grid.cell(min, expected), "packed cell mismatch");
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * Members too wide for the grid go to the wide grid's passes: no two of one
     * pass's cells may reach each other, nothing is lost or doubled, a village of
     * follow-range-48 mobs all finds cells, and a few huge outliers do not
     * coarsen the wide grid for everyone else.
     */
    private static void widePartition() {
        Random random = new Random(9113);
        Partition<Integer> partition = new Partition<>();
        for (int trial = 0; trial < 60; trial++) {
            int margin = 32;
            Grid grid = new Grid(128 + 32 * (trial % 3), margin);
            partition.begin(grid);
            int count = 600;
            double[][] spans = new double[count][];
            for (int i = 0; i < count; i++) {
                double x = random.nextDouble() * 1500 - 750, z = random.nextDouble() * 1500 - 750;
                int kind = random.nextInt(10);
                double reach = kind < 5 ? 0 : kind < 9 ? 16 : random.nextDouble() * 160;
                spans[i] = new double[] { x - reach, x + reach, z - reach, z + reach };
                partition.place(i, spans[i][0], spans[i][1], spans[i][2], spans[i][3]);
            }
            partition.fileWide();
            int parallel = partition.sort();
            check(parallel + partition.serial().size() == count && partition.all().size() == count,
                    "wide filing lost items");
            int[] cellOf = new int[count];
            int[] passOf = new int[count];
            java.util.Arrays.fill(cellOf, -1);
            int cellId = 0;
            for (int pass = 0; pass < Partition.PASSES; pass++) {
                for (Partition.Cell<Integer> cell : partition.pass(pass)) {
                    for (int item : cell.items()) {
                        check(cellOf[item] < 0, "item filed twice");
                        cellOf[item] = cellId;
                        passOf[item] = pass;
                    }
                    cellId++;
                }
            }
            for (int item : partition.serial()) {
                check(cellOf[item] < 0, "item both serial and in a cell");
            }
            for (int a = 0; a < count; a++) {
                if (cellOf[a] < 0) continue;
                for (int b = a + 1; b < count; b++) {
                    if (cellOf[b] < 0 || passOf[b] != passOf[a] || cellOf[b] == cellOf[a]) continue;
                    // Each reaches its span plus the margin; across cells of one pass these never meet.
                    boolean overlap = spans[a][0] - margin < spans[b][1] + margin && spans[b][0] - margin < spans[a][1] + margin
                            && spans[a][2] - margin < spans[b][3] + margin && spans[b][2] - margin < spans[a][3] + margin;
                    check(!overlap, "two cells of one pass reach each other");
                }
            }
            partition.release();
        }

        // A village: every follow-range-48 mob finds a cell, in the ordinary or the wide grid.
        Grid grid = new Grid(128, 32);
        partition.begin(grid);
        for (int i = 0; i < 300; i++) {
            double x = random.nextDouble() * 384, z = random.nextDouble() * 384;
            double reach = i % 10 == 0 ? 16 : 0;
            partition.place(i, x - reach, x + reach, z - reach, z + reach);
        }
        partition.fileWide();
        partition.sort();
        check(partition.serial().isEmpty(), "a village member was left serial");
        int wideMembers = 0;
        for (int pass = Partition.WIDE_PASS; pass < Partition.PASSES; pass++) {
            for (Partition.Cell<Integer> cell : partition.pass(pass)) wideMembers += cell.items().size();
        }
        check(wideMembers > 0 && wideMembers < 30, "wide grid took the wrong members");
        partition.release();

        // Two huge outliers among forty villagers leave the wide grid sized for villagers.
        partition.begin(grid);
        for (int i = 0; i < 42; i++) {
            double x = random.nextDouble() * 1024, z = random.nextDouble() * 1024;
            double reach = i < 2 ? 400 : 16;
            partition.place(i, x - reach, x + reach, z - reach, z + reach);
        }
        partition.fileWide();
        partition.sort();
        for (int item : partition.serial()) {
            check(item < 2, "a villager went serial beside huge outliers");
        }
        partition.release();

        // Only footprints the ordinary grid could hold somewhere are admitted: a ghast's
        // (width 136) or a width of exactly a cell less two margins stays serial.
        partition.begin(grid);
        for (int i = 0; i < 40; i++) {
            double x = 100.0 * i, z = 300 + 7.0 * i;
            double reach = i < 10 ? 68 : i < 20 ? 32 : 16;
            partition.place(i, x - reach, x + reach, z - reach, z + reach);
        }
        partition.fileWide();
        partition.sort();
        java.util.Set<Integer> serialSet = new java.util.HashSet<>(partition.serial());
        for (int i = 0; i < 20; i++) {
            check(serialSet.contains(i), "an over-wide member left the level thread: " + i);
        }
        for (int i = 20; i < 40; i++) {
            check(!serialSet.contains(i), "a villager-width member stayed serial: " + i);
        }
        partition.release();

        // Fewer wide members than the minimum run serially, as before.
        partition.begin(grid);
        for (int i = 0; i < Partition.MIN_WIDE - 1; i++) {
            partition.place(i, 40.0 * i - 16, 40.0 * i + 16, 70 - 16, 70 + 16);
        }
        partition.fileWide();
        check(partition.sort() + partition.serial().size() == Partition.MIN_WIDE - 1, "small wide set lost items");
        partition.release();
    }

    private static void pool() throws Exception {
        for (int workers : new int[] { 0, 1, 4 }) {
            SkeinPool pool = new SkeinPool(workers);
            try {
                for (int round = 0; round < 100; round++) {
                    AtomicIntegerArray counts = new AtomicIntegerArray(512);
                    List<Runnable> outer = new ArrayList<>();
                    for (int i = 0; i < 8; i++) {
                        int from = i * 64;
                        outer.add(() -> {
                            List<Runnable> inner = new ArrayList<>();
                            for (int j = 0; j < 64; j++) {
                                int at = from + j;
                                inner.add(() -> counts.incrementAndGet(at));
                            }
                            pool.invokeAll(inner);
                            for (int j = 0; j < 64; j++) check(counts.get(from + j) == 1, "nested barrier returned early");
                        });
                    }
                    pool.invokeAll(outer);
                    for (int i = 0; i < 512; i++) check(counts.get(i) == 1, "task skipped or duplicated");
                }
                CountDownLatch done = new CountDownLatch(3);
                for (int i = 0; i < 3; i++) {
                    Thread submitter = new Thread(() -> {
                        try {
                            for (int j = 0; j < 50; j++) pool.invokeAll(List.of(() -> {}, () -> {}));
                        } finally {
                            done.countDown();
                        }
                    });
                    submitter.setDaemon(true);
                    submitter.start();
                }
                check(done.await(10, TimeUnit.SECONDS), "concurrent submitters stalled");
                int[] published = new int[1024];
                pool.invokeAll(32, outer -> pool.invokeAll(32, inner -> published[outer * 32 + inner]++));
                for (int count : published) check(count == 1, "indexed tasks missing/duplicated/unpublished");
                pool.invokeAll(0, index -> { throw new AssertionError("empty batch ran"); });
                RuntimeException marker = new RuntimeException("expected task failure");
                AtomicIntegerArray completed = new AtomicIntegerArray(128);
                try {
                    pool.invokeAll(128, index -> {
                        if (index == 0) throw marker;
                        java.util.concurrent.locks.LockSupport.parkNanos(10_000);
                        completed.incrementAndGet(index);
                    });
                    throw new AssertionError("task failure was swallowed");
                } catch (RuntimeException error) {
                    check(error == marker, "task failure changed");
                }
                for (int i = 1; i < 128; i++) check(completed.get(i) == 1, "failure escaped before the barrier closed");
                AssertionError fatal = new AssertionError("expected fatal task failure");
                try {
                    pool.invokeAll(4, index -> { if (index == 0) throw fatal; });
                    throw new AssertionError("fatal task failure was swallowed");
                } catch (AssertionError error) {
                    check(error == fatal, "fatal task failure changed");
                }
                // A helper can discover another batch's failure. It must be
                // delivered to that batch's caller, after its own barrier.
                pool.invokeAll(8, outer -> {
                    try {
                        pool.invokeAll(32, inner -> { if (inner == 5) throw marker; });
                        throw new AssertionError("nested failure swallowed");
                    } catch (RuntimeException error) {
                        check(error == marker, "nested failure routed incorrectly");
                    }
                });
            } finally {
                pool.shutdown();
            }
        }
    }

    private static void benchmark() {
        System.out.println("Java " + System.getProperty("java.version") + "; microseconds/op (median of 7 samples), caller bytes/op");
        EntityLevels levels = new EntityLevels();
        List<Entity> dense = java.util.Collections.nCopies(10000, new Entity(0, 0, 0, 2));
        List<Entity> sparse = entities(new Random(317), 20000, 512, true);
        List<Entity> mixed = entities(new Random(817), 20000, 64, false);
        List<Entity> mixedSparse = entities(new Random(918), 20000, 512, false);
        List<Entity> zero = java.util.Collections.nCopies(20000, new Entity(0, 0, 0, 0));
        measure("entity dense 10000", () -> sink = levels.build(dense, REACH));
        measure("entity sparse 20000", () -> sink = levels.build(sparse, REACH));
        measure("entity mixed 20000", () -> sink = levels.build(mixed, REACH));
        measure("entity mixed sparse 20000", () -> sink = levels.build(mixedSparse, REACH));
        measure("entity zero 20000", () -> sink = levels.build(zero, REACH));
        ClusterPartition<Integer> partition = new ClusterPartition<>();
        Integer[] ids = new Integer[20000];
        int[] x = new int[ids.length], z = new int[ids.length];
        Random random = new Random(9123);
        for (int i = 0; i < ids.length; i++) {
            ids[i] = i;
            x[i] = random.nextInt(8000) - 4000;
            z[i] = random.nextInt(8000) - 4000;
        }
        measure("scheduled spread 20000", () -> {
            partition.begin(80);
            for (int i = 0; i < ids.length; i++) partition.add(ids[i], x[i], z[i]);
            partition.finish();
            sink = partition.groups().size();
            partition.release();
        });
        Partition<Integer> gridPartition = new Partition<>();
        Grid grid = new Grid(128, 32);
        measure("grid points 20000", () -> {
            gridPartition.begin(grid);
            for (int i = 0; i < ids.length; i++) gridPartition.place(ids[i], x[i], x[i], z[i], z[i]);
            sink = gridPartition.sort();
            gridPartition.release();
        });
        measure("grid spans 20000", () -> {
            gridPartition.begin(grid);
            for (int i = 0; i < ids.length; i++) gridPartition.place(ids[i], x[i], x[i] + 16, z[i], z[i] + 16);
            sink = gridPartition.sort();
            gridPartition.release();
        });
        PointGrid points = new PointGrid();
        List<Entity> outsiders = entities(new Random(617), 100, 64, false);
        List<Entity> queries = entities(new Random(112), 20000, 64, false);
        measure("isolation distant 20000", () -> {
            points.build(outsiders, 6);
            int hits = 0;
            for (Entity query : queries) {
                if (points.anyWithin(query.getX() + 512, query.getY(), query.getZ(), 6)) hits++;
            }
            sink = hits;
        });
        measure("isolation nearby 20000", () -> {
            points.build(outsiders, 6);
            int hits = 0;
            for (Entity query : queries) {
                if (points.anyWithin(query.getX(), query.getY(), query.getZ(), 6)) hits++;
            }
            sink = hits;
        });
        SkeinPool pool = new SkeinPool(4);
        try {
            List<Runnable> tasks = new ArrayList<>();
            for (int i = 0; i < 1024; i++) tasks.add(() -> {});
            measure("pool 1024 tiny tasks", () -> pool.invokeAll(tasks));
        } finally {
            pool.shutdown();
        }
    }

    private static void measure(String label, Runnable operation) {
        if (!label.contains(System.getProperty("skein.benchmark.filter", ""))) return;
        long warmup = System.nanoTime() + 1_500_000_000L;
        do { operation.run(); } while (System.nanoTime() < warmup);
        double[] samples = new double[7];
        double allocation = 0;
        com.sun.management.ThreadMXBean bean = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
        for (int sample = 0; sample < samples.length; sample++) {
            long beforeBytes = bean.getThreadAllocatedBytes(Thread.currentThread().getId());
            long start = System.nanoTime(), elapsed;
            int operations = 0;
            do { operation.run(); operations++; elapsed = System.nanoTime() - start; } while (elapsed < 250_000_000L);
            samples[sample] = elapsed / (double) operations / 1000;
            allocation += (bean.getThreadAllocatedBytes(Thread.currentThread().getId()) - beforeBytes) / (double) operations;
        }
        Arrays.sort(samples);
        System.out.printf(java.util.Locale.ROOT, "%-28s %10.2f us/op %10.1f B/op%n", label, samples[3], allocation / 7);
    }
}
