# Skein cross-version benchmark report

Each target is measured on a real dedicated server with the same generated world and scenarios: A is the loader alone, B adds Skein (defaults, `threadCount = 8`). Values are medians of per-leg medians, in ms per tick. Every leg's correctness checks passed (entity counts, item conservation, bots online), and every Skein run reported 0 missing hooks.

## Vanilla vs Skein, per target

| target | entities | hoppers | random | mixed | quiet |
| --- | --- | --- | --- | --- | --- |
| 1.20.1-forge | 15.37 → 8.67 (**1.77x**) | 1.81 → 1.84 (**0.99x**) | 4.53 → 2.66 (**1.70x**) | 25.05 → 10.21 (**2.45x**) | 2.43 → 2.35 (**1.03x**) |
| 1.20.1-neoforge | 14.97 → 8.52 (**1.76x**) | 1.84 → 1.89 (**0.98x**) | 4.45 → 2.64 (**1.69x**) | 24.81 → 10.22 (**2.43x**) | 2.39 → 2.18 (**1.10x**) |
| 1.21.1-forge | 12.14 → 7.24 (**1.68x**) | 1.64 → 1.67 (**0.98x**) | 5.71 → 2.73 (**2.09x**) | 23.15 → 10.17 (**2.28x**) | 1.91 → 1.99 (**0.96x**) |
| 1.21.1-neoforge | 13.91 → 8.64 (**1.61x**) | 2.16 → 2.18 (**0.99x**) | 8.42 → 3.75 (**2.25x**) | 24.96 → 11.45 (**2.18x**) | 3.16 → 2.72 (**1.16x**) |
| 1.21.11-forge | 12.38 → 5.95 (**2.08x**) | 1.34 → 1.25 (**1.08x**) | 6.24 → 2.46 (**2.53x**) | 28.04 → 8.44 (**3.32x**) | 1.88 → 1.92 (**0.98x**) |
| 1.21.11-neoforge | 13.51 → 7.17 (**1.88x**) | 2.23 → 2.29 (**0.97x**) | 8.80 → 2.82 (**3.12x**) | 28.29 → 8.97 (**3.15x**) | 2.37 → 2.13 (**1.12x**) |
| 26.2-forge | 13.35 → 6.11 (**2.18x**) | 2.12 → 1.33 (**1.59x**) | 7.43 → 2.39 (**3.11x**) | 29.16 → 8.42 (**3.47x**) | 2.44 → 1.77 (**1.38x**) |
| 26.2-neoforge | 12.05 → 5.79 (**2.08x**) | 1.29 → 1.39 (**0.93x**) | 6.51 → 2.45 (**2.66x**) | 27.77 → 8.25 (**3.37x**) | 1.80 → 1.67 (**1.08x**) |

## Rule 1, as specified: speedup ≥ 0.95 × the 1.21.1-neoforge speedup

| target | entities | hoppers | random | mixed | quiet | verdict |
| --- | --- | --- | --- | --- | --- | --- |
| 1.20.1-forge | 1.10 | 0.99 | 0.76 ✗ | 1.13 | 0.89 ✗ | fail |
| 1.20.1-neoforge | 1.09 | 0.98 | 0.75 ✗ | 1.11 | 0.94 ✗ | fail |
| 1.21.1-forge | 1.04 | 0.99 | 0.93 ✗ | 1.04 | 0.83 ✗ | fail |
| 1.21.11-forge | 1.29 | 1.08 | 1.13 | 1.52 | 0.84 ✗ | fail |
| 1.21.11-neoforge | 1.17 | 0.98 | 1.39 | 1.45 | 0.96 | pass |
| 26.2-forge | 1.36 | 1.60 | 1.38 | 1.59 | 1.19 | pass |
| 26.2-neoforge | 1.29 | 0.94 ✗ | 1.18 | 1.54 | 0.93 ✗ | fail |

## Same version, other loader: Skein ms/tick against the NeoForge build of that version

Same Minecraft version, same world. Lower is better, and a ratio ≤ 1.00 means the Forge build with Skein ticks at least as fast as the NeoForge build with Skein.

| version | entities | hoppers | random | mixed | quiet |
| --- | --- | --- | --- | --- | --- |
| 1.20.1 Forge vs NeoForge | 8.67 vs 8.52 (1.02) | 1.84 vs 1.89 (0.97) | 2.66 vs 2.64 (1.01) | 10.21 vs 10.22 (1.00) | 2.35 vs 2.18 (1.08) |
| 1.21.1 Forge vs NeoForge | 7.24 vs 8.64 (0.84) | 1.67 vs 2.18 (0.77) | 2.73 vs 3.75 (0.73) | 10.17 vs 11.45 (0.89) | 1.99 vs 2.72 (0.73) |
| 1.21.11 Forge vs NeoForge | 5.95 vs 7.17 (0.83) | 1.25 vs 2.29 (0.55) | 2.46 vs 2.82 (0.87) | 8.44 vs 8.97 (0.94) | 1.92 vs 2.13 (0.90) |
| 26.2 Forge vs NeoForge | 6.11 vs 5.79 (1.06) | 1.33 vs 1.39 (0.96) | 2.39 vs 2.45 (0.98) | 8.42 vs 8.25 (1.02) | 1.77 vs 1.67 (1.06) |

## Phase efficiency: overlap from `/skein status` (work ÷ wall-clock, 8 workers)

| target | random ticks (random) | entities (entities) | random ticks (mixed) | entities (mixed) | block entities (mixed) |
| --- | --- | --- | --- | --- | --- |
| 1.20.1-forge | 5.48x | 5.86x | 5.19x | 4.64x | 1.99x |
| 1.20.1-neoforge | 4.87x | 6.05x | 4.71x | 4.24x | 1.93x |
| 1.21.1-forge | 5.95x | 5.32x | 6.18x | 4.20x | -x |
| 1.21.1-neoforge | 6.40x | 5.99x | 4.94x | 4.71x | -x |
| 1.21.11-forge | 5.94x | 4.96x | 5.12x | 5.50x | -x |
| 1.21.11-neoforge | 6.21x | 5.07x | 4.80x | 5.89x | 3.51x |
| 26.2-forge | 5.97x | 4.67x | 5.29x | 4.82x | 2.62x |
| 26.2-neoforge | 5.81x | 5.05x | 4.12x | 4.29x | 2.34x |

## Quiet server and hoppers, 4 repetitions: does Skein ever cost time?

Each repetition runs its vanilla leg and its Skein leg back to back, so B/A is taken per pair (Skein ms/tick ÷ vanilla ms/tick of the same repetition), which cancels load that drifts between repetitions. The table gives the median pair ratio and the range of the pair ratios. Below 1.00 means Skein was faster. A range that straddles 1.00 means no difference was measurable.

| target | quiet B/A | quiet range | hoppers B/A | hoppers range |
| --- | --- | --- | --- | --- |
| 1.20.1-forge | 0.85 (n=4) | 0.63–1.07 | 1.02 (n=4) | 0.95–1.09 |
| 1.20.1-neoforge | 0.87 (n=4) | 0.76–0.95 | 1.00 (n=4) | 0.84–1.04 |
| 1.21.1-forge | 0.90 (n=4) | 0.81–1.11 | 1.02 (n=4) | 0.85–1.05 |
| 1.21.1-neoforge | 0.86 (n=4) | 0.82–0.87 | 1.00 (n=4) | 0.86–1.15 |
| 1.21.11-forge | 0.96 (n=4) | 0.76–1.03 | 0.99 (n=4) | 0.91–1.07 |
| 1.21.11-neoforge | 0.86 (n=4) | 0.84–0.96 | 0.99 (n=4) | 0.82–1.07 |
| 26.2-forge | 1.01 (n=3) | 0.91–1.04 | 0.98 (n=2) | 0.98–0.98 |
| 26.2-neoforge | 0.93 (n=4) | 0.82–1.02 | 1.01 (n=4) | 0.97–1.14 |
