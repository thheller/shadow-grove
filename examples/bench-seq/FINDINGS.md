# diff-seq: findings

Exploration of a `simple-seq` alternative that needs no key-fn, plus a
`keyed-seq` rebuilt on the same algorithm. `keyed-seq` (and `sg/keyed-seq`)
now uses the new implementation and the old `KeyedCollection` is gone.
`simple-seq` is unchanged. `diff-seq` and `diff-seq-by-value` live in
`shadow.arborist.collections` but aren't exposed in `shadow.grove` yet.

The tables below were measured before the switch: "keyed-seq :id" is the old
implementation, "keyed-seq new" is today's `keyed-seq`.

`report.html` in this directory is the full interactive report (heatmaps for
every scenario and size, cost-by-size chart, tables). Open it in a browser.

## What it is

`shadow.arborist.collections` gains one `DiffCollection` type with three modes:

| Function | Key | Keys match when | Renders over other items |
|---|---|---|---|
| `diff-seq` | the item | `identical?` (strings/numbers by value) | yes, like `simple-seq` |
| `diff-seq-by-value` | the item | `=`, looked up by `hash` | yes, like `simple-seq` |
| `keyed-seq` | `(key-fn item)` | `=`, looked up by `hash` | never |

Sync steps, looping on the still unresolved part of old/new:

1. Skip matching heads, then matching tails. Covers insert/remove anywhere,
   append, prepend and single in-place updates.
2. One side empty: plain insert or remove.
3. Old head matches new tail (or old tail matches new head): move it. Covers
   swaps, rotations and single moves with `identical?` checks only.
4. Unkeyed only: heads differ but the next pair matches, so render the new head
   over the old head in place. Same for tails. Keeps scattered updates linear.
5. General case: JS `Map` from key to the first unclaimed old index, duplicate
   keys chained through an `Int32Array` (so `[1 1 1]` works). Unkeyed mode
   renders unmatched new items over unmatched old ones in order. Leftovers are
   created or destroyed. The longest increasing subsequence of reused items stays,
   everything else moves.

Matched items skip rendering when the render-fn is identical, the data is
identical (or `=` in by-value mode) and the item kept its `idx`. An item that
moved always renders again, so the `idx` it shows is current. (Originally moved
items skipped rendering too and kept a stale `idx`; the numbers below are from
then.)

## Findings

All numbers are µs per update from one run of the bench (median of 7 batches),
headless Chromium 156, arm64 Linux, release build, fragment rows. "incl. layout"
forces layout after every update. Each scenario alternates between two states,
so "remove first" averages one removal and one insertion.

- **Removing/inserting near the front, 100 rows:** 104 µs instead of 935 µs
  incl. layout, 0.5 renders and 2 DOM mutations per update instead of 99.5 and
  464. The gain starts at 2 items (1.4×) and reaches 13× at 1000 rows. Removing
  from the middle is 4.8× faster at 100 rows. Since shifted items render again
  for their new `idx` it's 99.5 renders but still 2 DOM mutations (the bench row
  doesn't show `idx`): 114 µs vs 992 µs at 100 rows (8.7×), 993 µs vs 10.2 ms at
  1000 (10×).
- **New keyed-seq vs current keyed-seq:** 1.0–22× faster in script time in every
  scenario and size. The only cells below 1× are a few at 1–3 items
  (0.94–0.99×), within noise. The current one rebuilds a persistent map of all
  keys on every update, about 155 µs at 1000 rows even when one row changes; the
  new one takes 7 µs. Incl. layout the gain is 1.0–1.3× since both make the same
  DOM changes.
- **Small lists (1–5 items):** every implementation costs 0.2–5 µs of script and
  12–70 µs incl. layout. `diff-seq` already wins removals and inserts at 2 items
  (1.4–2.5× incl. layout). It loses swaps and reverses once layout counts (about
  0.73×, e.g. 42 vs 31 µs for a swap in 5 rows). For in-place updates it is a
  fraction of a microsecond slower in script time.
- **Moving DOM costs more layout than rewriting text:** reverses, shuffles and
  swaps are faster with `simple-seq` once layout counts (reverse at 1000 rows:
  10.2 ms vs 14.8 ms), for both keyed implementations too. Moves still keep
  per-row DOM state (focus, input values).
- **simple-seq's best cases** (append, remove last, update in place) cost
  `diff-seq` up to about 1.5× more script time (8.7 vs 5.6 µs at 1000 rows).
  Even incl. layout.
- **Fresh equal maps** (a query doing `(map #(assoc % …))` on every run) defeat
  identity matching, so `diff-seq` does what `simple-seq` does plus a lookup.
  `diff-seq-by-value` is 8.6× faster there in script time (90 vs 777 µs; 0.92 vs
  9.7 ms incl. layout). Elsewhere `=` costs up to 10% more script, more for
  in-place updates: 1.8× for one changed row in a small list (under 0.3 µs) and
  1.5× when every 10th row changes (17 µs at 1000 rows).
- **keyed-seq new vs diff-seq:** same speed, except "replace all", where keyed
  mode destroys and creates every row instead of rendering over them (3.5–4×
  slower), and fresh maps, where keys still match and identity does not
  (1.5–5× faster).
- **Matching cost (the Map + Set proposal):** works, but a JS `Map` with an
  `Int32Array` chain is 6.6× cheaper (37 vs 242 µs for 1000 shuffled maps), from
  not allocating a Set per value and not hashing through a CLJS map. Both are
  small next to the ~14 ms of moves and layout for a 1000-row shuffle, and the
  lookup only runs for real reorders.

## Open decisions

- **Replace the keyed-seq implementation?** Done. The new one keeps the contract
  (same args, render-fn gets `[item idx key]`, never renders across keys,
  identical data skips rendering). Behavior change: duplicate keys throw only in
  dev builds, since checking them in release needs the full key set on every
  update, which is most of the old cost.
- **Default matching for the unkeyed version:** `identical?` is cheapest for
  shared items; `=` also catches freshly built maps and costs little elsewhere
  because `=` returns early for identical items. Risk of `=`: large fresh items
  pay a full hash and deep compare per item. Suggestion: `=` by default.
- **Render-fn args:** a `simple-seq` render fn that uses `idx` would silently
  break after switching, because moved items aren't re-rendered. Suggestion: call
  the unkeyed render fn with just the item; keep `[item idx key]` for keyed.
- **simple-seq stays** for append-only and in-place lists, and for full reorders
  where layout matters.
- **Names:** `diff-seq`, `diff-seq-by-value` are working names.

## Correctness

- `src/test/shadow/arborist/diff_seq_test.cljs`, run by the `:node-test` build
  (`node out/node-test/test.js`): fixed transitions incl. duplicates, render
  counts, preserved DOM nodes, dev duplicate-key error, and six fuzz tests of
  3000 random transitions each (identity, by value, fresh maps, long lists,
  keyed). The keyed fuzz checks that no DOM node ever shows a different key
  than the one it was created for.
- The fuzz fails on deliberately broken versions: moves disabled, wrong anchor
  for the tail-to-head move, render-over enabled in keyed mode.

## Found along the way

- `shadow.arborist/destroy!` always throws: it calls the `p/destroy!` protocol
  method, but `TreeRoot` only defines `destroy!` as an `Object` method.
- The current `keyed-seq` seeks moved items with `.indexOf` from the front, which
  is quadratic for heavily shuffled lists.

## Re-running

Release the `:bench-seq` build, then `node examples/bench-seq/run.mjs`.
`--sizes=1,10,100`, `--scenarios=swap,shuffle` and `--json=out.json` narrow the
run or keep the raw data. Needs `/usr/bin/chromium` (or `CHROME=...`). Note that
Closure drops plain property reads like `.offsetHeight` in advanced builds, so
the bench forces layout with `getBoundingClientRect()`.

## Full results (Chrome 156, headless, arm64 Linux)

### Matching only, µs per op (lookup build + match all)

#### n=10

| data | cljs map → js/Set (proposal) | js Map identity → js/Set | js Map identity → Int32 chain (diff-seq) | js Map hash → chain + = (diff-seq =) |
|---|---:|---:|---:|---:|
| maps, shuffled (same instances) | 2.65 (10/10) | 0.72 (10/10) | 0.40 (10/10) | 0.52 (10/10) |
| maps, shuffled, fresh equal copies | 8.08 (10/10) | 0.42 (0/10) | 0.29 (0/10) | 5.33 (10/10) |
| strings w/ duplicates, shuffled | 0.73 (10/10) | 0.57 (10/10) | 0.33 (10/10) | 0.71 (10/10) |

#### n=100

| data | cljs map → js/Set (proposal) | js Map identity → js/Set | js Map identity → Int32 chain (diff-seq) | js Map hash → chain + = (diff-seq =) |
|---|---:|---:|---:|---:|
| maps, shuffled (same instances) | 18.1 (100/100) | 7.51 (100/100) | 3.83 (100/100) | 5.77 (100/100) |
| maps, shuffled, fresh equal copies | 76.8 (100/100) | 4.33 (0/100) | 2.95 (0/100) | 62.9 (100/100) |
| strings w/ duplicates, shuffled | 5.85 (100/100) | 4.26 (100/100) | 3.15 (100/100) | 7.78 (100/100) |

#### n=1000

| data | cljs map → js/Set (proposal) | js Map identity → js/Set | js Map identity → Int32 chain (diff-seq) | js Map hash → chain + = (diff-seq =) |
|---|---:|---:|---:|---:|
| maps, shuffled (same instances) | 242 (1000/1000) | 88.7 (1000/1000) | 36.9 (1000/1000) | 60.3 (1000/1000) |
| maps, shuffled, fresh equal copies | 1140 (1000/1000) | 50.0 (0/1000) | 29.8 (0/1000) | 900 (1000/1000) |
| strings w/ duplicates, shuffled | 73.8 (1000/1000) | 53.4 (1000/1000) | 30.2 (1000/1000) | 78.4 (1000/1000) |

### DOM, per op: µs script / µs incl. forced layout / renders / DOM mutations

#### n=1

| scenario | simple-seq | keyed-seq :id | keyed-seq new | diff-seq | diff-seq = |
|---|---:|---:|---:|---:|---:|
| remove first ⇄ insert first | 10.6 / 23.1 / 0.5 / 5 | 8.86 / 23.9 / 0.5 / 5 | 8.74 / 24.8 / 0.5 / 5 | 8.83 / 24.8 / 0.5 / 5 | 8.61 / 23.2 / 0.5 / 5 |
| update 1 row | 0.23 / 11.6 / 1 / 1 | 0.35 / 12.4 / 1 / 1 | 0.25 / 12.0 / 1 / 1 | 0.27 / 12.8 / 1 / 1 | 0.48 / 12.1 / 1 / 1 |
| replace all (no overlap) | 0.68 / 19.4 / 1 / 5 | 3.06 / 30.7 / 1 / 4 | 2.94 / 31.2 / 1 / 4 | 0.73 / 19.5 / 1 / 5 | 0.79 / 19.6 / 1 / 5 |
| fresh equal maps, no change | 0.16 / 0.58 / 1 / 0 | 0.28 / 0.72 / 1 / 0 | 0.19 / 0.62 / 1 / 0 | 0.19 / 0.61 / 1 / 0 | 0.14 / 0.55 / 0 / 0 |

#### n=2

| scenario | simple-seq | keyed-seq :id | keyed-seq new | diff-seq | diff-seq = |
|---|---:|---:|---:|---:|---:|
| remove first ⇄ insert first | 2.08 / 29.3 / 1.5 / 7 | 1.51 / 21.5 / 0.5 / 2 | 1.36 / 21.4 / 0.5 / 2 | 1.36 / 20.6 / 0.5 / 2 | 1.39 / 20.3 / 0.5 / 2 |
| remove last ⇄ append | 1.38 / 20.1 / 0.5 / 2 | 1.56 / 20.8 / 0.5 / 2 | 1.36 / 21.2 / 0.5 / 2 | 1.36 / 20.4 / 0.5 / 2 | 1.32 / 20.8 / 0.5 / 2 |
| update 1 row | 0.24 / 14.1 / 1 / 1 | 0.40 / 13.7 / 1 / 1 | 0.30 / 12.7 / 1 / 1 | 0.29 / 13.6 / 1 / 1 | 0.50 / 13.6 / 1 / 1 |
| reverse | 1.32 / 28.2 / 2 / 10 | 1.48 / 41.8 / 0 / 8 | 0.76 / 26.8 / 0 / 4 | 0.69 / 27.5 / 0 / 4 | 0.75 / 28.1 / 0 / 4 |
| replace all (no overlap) | 1.32 / 28.0 / 2 / 10 | 5.55 / 46.8 / 2 / 8 | 5.55 / 47.9 / 2 / 8 | 1.63 / 29.7 / 2 / 10 | 1.73 / 30.0 / 2 / 10 |
| fresh equal maps, no change | 0.27 / 0.69 / 2 / 0 | 0.44 / 0.88 / 2 / 0 | 0.33 / 0.76 / 2 / 0 | 0.50 / 0.98 / 2 / 0 | 0.25 / 0.65 / 0 / 0 |
| fresh equal maps, remove first ⇄ insert first | 2.19 / 31.6 / 1.5 / 7 | 1.78 / 23.8 / 1.5 / 2 | 1.62 / 21.8 / 1.5 / 2 | 2.45 / 30.0 / 1.5 / 7 | 1.53 / 20.5 / 0.5 / 2 |
| strings w/ duplicates, remove first ⇄ insert first | 0.73 / 17.3 / 1.5 / 3 | n/a | n/a | 0.58 / 13.8 / 0.5 / 2 | 0.63 / 13.7 / 0.5 / 2 |
| inline render fn, remove first ⇄ insert first | 2.21 / 28.9 / 1.5 / 7 | 1.72 / 22.1 / 1.5 / 2 | 1.56 / 21.5 / 1.5 / 2 | 1.54 / 20.2 / 1.5 / 2 | 1.61 / 21.1 / 1.5 / 2 |

#### n=3

| scenario | simple-seq | keyed-seq :id | keyed-seq new | diff-seq | diff-seq = |
|---|---:|---:|---:|---:|---:|
| remove first ⇄ insert first | 2.76 / 44.1 / 2.5 / 11 | 1.66 / 21.6 / 0.5 / 2 | 1.40 / 22.3 / 0.5 / 2 | 1.45 / 22.9 / 0.5 / 2 | 1.50 / 23.9 / 0.5 / 2 |
| remove middle ⇄ insert middle | 2.22 / 33.6 / 1.5 / 6 | 1.82 / 24.0 / 0.5 / 2 | 1.51 / 22.5 / 0.5 / 2 | 1.44 / 20.7 / 0.5 / 2 | 1.41 / 21.3 / 0.5 / 2 |
| remove last ⇄ append | 1.39 / 29.4 / 0.5 / 2 | 1.76 / 30.0 / 0.5 / 2 | 1.41 / 28.5 / 0.5 / 2 | 1.38 / 26.5 / 0.5 / 2 | 1.36 / 25.9 / 0.5 / 2 |
| update 1 row | 0.26 / 14.2 / 1 / 1 | 0.46 / 14.2 / 1 / 1 | 0.32 / 14.1 / 1 / 1 | 0.32 / 14.8 / 1 / 1 | 0.55 / 14.3 / 1 / 1 |
| reverse | 1.34 / 30.2 / 2 / 10 | 1.50 / 43.1 / 0 / 8 | 1.60 / 43.7 / 0 / 8 | 1.38 / 40.7 / 0 / 8 | 1.41 / 42.0 / 0 / 8 |
| shuffle | 1.85 / 36.9 / 3 / 14 | 2.12 / 54.6 / 0 / 12 | 0.80 / 27.7 / 0 / 4 | 0.70 / 28.2 / 0 / 4 | 0.77 / 28.2 / 0 / 4 |
| replace all (no overlap) | 1.69 / 39.5 / 3 / 12 | 8.75 / 70.9 / 3 / 12 | 8.89 / 68.4 / 3 / 12 | 2.01 / 41.3 / 3 / 12 | 2.19 / 39.5 / 3 / 12 |
| fresh equal maps, no change | 0.39 / 0.82 / 3 / 0 | 0.62 / 1.07 / 3 / 0 | 0.48 / 0.92 / 3 / 0 | 0.75 / 1.25 / 3 / 0 | 0.33 / 0.76 / 0 / 0 |
| fresh equal maps, remove first ⇄ insert first | 3.11 / 44.9 / 2.5 / 11 | 2.03 / 24.9 / 2.5 / 2 | 1.82 / 22.5 / 2.5 / 2 | 3.17 / 39.2 / 2.5 / 11 | 1.60 / 20.8 / 0.5 / 2 |
| strings w/ duplicates, remove first ⇄ insert first | 0.84 / 21.7 / 2.5 / 4 | n/a | n/a | 0.67 / 14.7 / 0.5 / 2 | 0.68 / 15.1 / 0.5 / 2 |
| inline render fn, remove first ⇄ insert first | 2.74 / 42.8 / 2.5 / 11 | 2.05 / 24.4 / 2.5 / 2 | 1.72 / 22.6 / 2.5 / 2 | 1.65 / 25.1 / 2.5 / 2 | 1.75 / 22.1 / 2.5 / 2 |

#### n=5

| scenario | simple-seq | keyed-seq :id | keyed-seq new | diff-seq | diff-seq = |
|---|---:|---:|---:|---:|---:|
| remove first ⇄ insert first | 4.08 / 60.8 / 4.5 / 21 | 1.66 / 23.2 / 0.5 / 2 | 1.51 / 22.6 / 0.5 / 2 | 1.40 / 24.0 / 0.5 / 2 | 1.52 / 25.5 / 0.5 / 2 |
| remove middle ⇄ insert middle | 3.03 / 46.7 / 2.5 / 12 | 1.85 / 25.0 / 0.5 / 2 | 1.45 / 24.7 / 0.5 / 2 | 1.46 / 23.4 / 0.5 / 2 | 1.47 / 24.2 / 0.5 / 2 |
| remove last ⇄ append | 1.36 / 24.0 / 0.5 / 2 | 1.74 / 23.7 / 0.5 / 2 | 1.39 / 23.6 / 0.5 / 2 | 1.35 / 22.3 / 0.5 / 2 | 1.36 / 23.4 / 0.5 / 2 |
| swap rows 1 and n-2 | 1.38 / 30.6 / 2 / 10 | 1.61 / 43.6 / 0 / 8 | 1.45 / 41.4 / 0 / 8 | 1.29 / 42.3 / 0 / 8 | 1.39 / 42.8 / 0 / 8 |
| update 1 row | 0.27 / 15.6 / 1 / 1 | 0.58 / 16.5 / 1 / 1 | 0.32 / 15.6 / 1 / 1 | 0.33 / 16.3 / 1 / 1 | 0.57 / 16.3 / 1 / 1 |
| reverse | 2.59 / 49.6 / 4 / 20 | 2.88 / 67.6 / 0 / 16 | 2.85 / 69.1 / 0 / 16 | 2.51 / 68.2 / 0 / 16 | 2.77 / 69.7 / 0 / 16 |
| shuffle | 2.43 / 48.6 / 4 / 18 | 2.87 / 67.3 / 0 / 16 | 2.54 / 57.2 / 0 / 12 | 2.35 / 58.0 / 0 / 12 | 2.65 / 58.3 / 0 / 12 |
| replace all (no overlap) | 3.33 / 54.6 / 5 / 24 | 13.1 / 98.4 / 5 / 20 | 13.2 / 92.3 / 5 / 20 | 3.66 / 55.6 / 5 / 24 | 3.74 / 56.1 / 5 / 24 |
| fresh equal maps, no change | 0.60 / 1.03 / 5 / 0 | 1.00 / 1.44 / 5 / 0 | 0.76 / 1.21 / 5 / 0 | 1.13 / 1.65 / 5 / 0 | 0.52 / 0.92 / 0 / 0 |
| fresh equal maps, remove first ⇄ insert first | 4.33 / 59.2 / 4.5 / 21 | 2.32 / 25.2 / 4.5 / 2 | 2.02 / 23.9 / 4.5 / 2 | 4.69 / 58.6 / 4.5 / 21 | 1.83 / 24.8 / 0.5 / 2 |
| strings w/ duplicates, remove first ⇄ insert first | 1.04 / 27.3 / 4.5 / 6 | n/a | n/a | 0.64 / 14.3 / 0.5 / 2 | 0.66 / 14.7 / 0.5 / 2 |
| inline render fn, remove first ⇄ insert first | 4.06 / 63.9 / 4.5 / 21 | 2.31 / 28.0 / 4.5 / 2 | 2.01 / 26.0 / 4.5 / 2 | 2.00 / 26.3 / 4.5 / 2 | 2.03 / 27.2 / 4.5 / 2 |
| inline render fn, swap rows 1 and n-2 | 1.72 / 34.4 / 5 / 10 | 2.24 / 50.6 / 5 / 8 | 2.13 / 47.0 / 5 / 8 | 1.92 / 45.2 / 5 / 8 | 2.03 / 47.2 / 5 / 8 |

#### n=10

| scenario | simple-seq | keyed-seq :id | keyed-seq new | diff-seq | diff-seq = |
|---|---:|---:|---:|---:|---:|
| remove first ⇄ insert first | 7.72 / 111 / 9.5 / 44 | 2.95 / 30.8 / 0.5 / 2 | 1.51 / 28.8 / 0.5 / 2 | 1.51 / 27.5 / 0.5 / 2 | 1.52 / 27.6 / 0.5 / 2 |
| remove middle ⇄ insert middle | 4.21 / 65.5 / 4.5 / 21 | 2.99 / 29.8 / 0.5 / 2 | 1.51 / 28.3 / 0.5 / 2 | 1.48 / 28.3 / 0.5 / 2 | 1.53 / 30.4 / 0.5 / 2 |
| remove last ⇄ append | 1.43 / 27.1 / 0.5 / 2 | 2.91 / 29.8 / 0.5 / 2 | 1.48 / 27.3 / 0.5 / 2 | 1.42 / 28.3 / 0.5 / 2 | 1.47 / 27.4 / 0.5 / 2 |
| swap rows 1 and n-2 | 1.22 / 34.4 / 2 / 8 | 2.83 / 56.2 / 0 / 8 | 1.68 / 50.2 / 0 / 8 | 1.39 / 49.4 / 0 / 8 | 1.50 / 50.8 / 0 / 8 |
| update 1 row | 0.32 / 20.8 / 1 / 1 | 1.83 / 22.4 / 1 / 1 | 0.36 / 20.2 / 1 / 1 | 0.38 / 20.3 / 1 / 1 | 0.62 / 19.9 / 1 / 1 |
| reverse | 5.55 / 102 / 10 / 40 | 7.61 / 153 / 0 / 40 | 6.15 / 134 / 0 / 36 | 5.68 / 137 / 0 / 36 | 6.11 / 142 / 0 / 36 |
| shuffle | 5.74 / 94.1 / 9 / 42 | 6.95 / 140 / 0 / 36 | 4.16 / 86.9 / 0 / 20 | 3.77 / 91.9 / 0 / 20 | 4.20 / 90.4 / 0 / 20 |
| replace all (no overlap) | 6.28 / 104 / 10 / 47 | 28.8 / 184 / 10 / 40 | 27.4 / 176 / 10 / 40 | 6.95 / 103 / 10 / 47 | 7.15 / 105 / 10 / 47 |
| fresh equal maps, no change | 1.24 / 1.70 / 10 / 0 | 2.82 / 3.35 / 10 / 0 | 1.53 / 2.04 / 10 / 0 | 2.15 / 2.65 / 10 / 0 | 0.99 / 1.43 / 0 / 0 |
| fresh equal maps, remove first ⇄ insert first | 7.70 / 104 / 9.5 / 44 | 4.19 / 31.0 / 9.5 / 2 | 2.84 / 29.7 / 9.5 / 2 | 8.68 / 113 / 9.5 / 44 | 2.46 / 28.7 / 0.5 / 2 |
| strings w/ duplicates, remove first ⇄ insert first | 1.62 / 46.2 / 9.5 / 11 | n/a | n/a | 0.68 / 18.5 / 0.5 / 2 | 0.70 / 16.6 / 0.5 / 2 |
| inline render fn, remove first ⇄ insert first | 7.59 / 106 / 9.5 / 44 | 3.91 / 31.4 / 9.5 / 2 | 2.55 / 29.1 / 9.5 / 2 | 2.78 / 29.6 / 9.5 / 2 | 2.68 / 33.1 / 9.5 / 2 |
| inline render fn, swap rows 1 and n-2 | 2.12 / 38.4 / 10 / 8 | 4.47 / 55.3 / 10 / 8 | 2.90 / 52.3 / 10 / 8 | 2.57 / 54.5 / 10 / 8 | 2.79 / 52.8 / 10 / 8 |

#### n=20

| scenario | simple-seq | keyed-seq :id | keyed-seq new | diff-seq | diff-seq = |
|---|---:|---:|---:|---:|---:|
| remove first ⇄ insert first | 15.1 / 221 / 19.5 / 91 | 5.30 / 48.4 / 0.5 / 2 | 1.64 / 42.8 / 0.5 / 2 | 1.77 / 38.9 / 0.5 / 2 | 1.64 / 38.3 / 0.5 / 2 |
| remove middle ⇄ insert middle | 7.78 / 124 / 9.5 / 44 | 5.52 / 45.4 / 0.5 / 2 | 1.62 / 39.5 / 0.5 / 2 | 1.64 / 39.9 / 0.5 / 2 | 1.65 / 40.6 / 0.5 / 2 |
| remove last ⇄ append | 1.52 / 37.7 / 0.5 / 2 | 5.55 / 43.5 / 0.5 / 2 | 1.56 / 37.7 / 0.5 / 2 | 2.35 / 41.3 / 0.5 / 2 | 1.59 / 38.2 / 0.5 / 2 |
| swap rows 1 and n-2 | 1.51 / 43.0 / 2 / 10 | 4.93 / 63.1 / 0 / 8 | 1.61 / 57.5 / 0 / 8 | 1.46 / 58.9 / 0 / 8 | 1.54 / 55.2 / 0 / 8 |
| update 1 row | 0.35 / 30.1 / 1 / 1 | 3.68 / 33.8 / 1 / 1 | 0.41 / 28.4 / 1 / 1 | 0.46 / 29.2 / 1 / 1 | 0.69 / 29.6 / 1 / 1 |
| update every 10th row | 0.56 / 31.0 / 2 / 2 | 3.82 / 38.5 / 2 / 2 | 0.68 / 32.9 / 2 / 2 | 0.70 / 32.0 / 2 / 2 | 1.10 / 33.0 / 2 / 2 |
| reverse | 12.3 / 199 / 20 / 94 | 16.5 / 299 / 0 / 80 | 13.2 / 294 / 0 / 76 | 11.9 / 282 / 0 / 76 | 13.1 / 273 / 0 / 76 |
| shuffle | 13.0 / 189 / 20 / 90 | 15.9 / 285 / 0 / 80 | 10.9 / 209 / 0 / 56 | 10.8 / 203 / 0 / 56 | 10.9 / 205 / 0 / 56 |
| replace all (no overlap) | 12.9 / 194 / 20 / 94 | 55.9 / 345 / 20 / 80 | 52.2 / 334 / 20 / 80 | 15.5 / 198 / 20 / 94 | 14.9 / 188 / 20 / 94 |
| fresh equal maps, no change | 2.46 / 2.89 / 20 / 0 | 6.32 / 6.95 / 20 / 0 | 3.00 / 3.51 / 20 / 0 | 4.45 / 5.08 / 20 / 0 | 1.78 / 2.31 / 0 / 0 |
| fresh equal maps, remove first ⇄ insert first | 15.0 / 189 / 19.5 / 91 | 7.94 / 47.8 / 19.5 / 2 | 4.34 / 39.0 / 19.5 / 2 | 17.8 / 202 / 19.5 / 91 | 3.16 / 39.9 / 0.5 / 2 |
| strings w/ duplicates, remove first ⇄ insert first | 2.57 / 71.7 / 19.5 / 21 | n/a | n/a | 0.74 / 20.8 / 0.5 / 2 | 0.78 / 22.2 / 0.5 / 2 |
| inline render fn, remove first ⇄ insert first | 14.4 / 194 / 19.5 / 91 | 7.41 / 43.4 / 19.5 / 2 | 3.82 / 38.9 / 19.5 / 2 | 3.80 / 39.4 / 19.5 / 2 | 3.83 / 39.1 / 19.5 / 2 |
| inline render fn, swap rows 1 and n-2 | 3.33 / 44.9 / 20 / 10 | 7.15 / 66.1 / 20 / 8 | 3.80 / 63.0 / 20 / 8 | 3.63 / 58.0 / 20 / 8 | 3.77 / 59.3 / 20 / 8 |

#### n=50

| scenario | simple-seq | keyed-seq :id | keyed-seq new | diff-seq | diff-seq = |
|---|---:|---:|---:|---:|---:|
| remove first ⇄ insert first | 34.9 / 458 / 49.5 / 231 | 8.45 / 72.9 / 0.5 / 2 | 1.86 / 60.2 / 0.5 / 2 | 1.80 / 60.2 / 0.5 / 2 | 1.77 / 60.4 / 0.5 / 2 |
| remove middle ⇄ insert middle | 17.9 / 272 / 24.5 / 114 | 8.63 / 70.9 / 0.5 / 2 | 1.86 / 61.8 / 0.5 / 2 | 1.77 / 65.2 / 0.5 / 2 | 1.83 / 62.4 / 0.5 / 2 |
| remove last ⇄ append | 1.66 / 66.3 / 0.5 / 2 | 9.21 / 76.8 / 0.5 / 2 | 1.78 / 64.2 / 0.5 / 2 | 1.70 / 62.4 / 0.5 / 2 | 1.70 / 61.5 / 0.5 / 2 |
| swap rows 1 and n-2 | 1.70 / 71.2 / 2 / 10 | 8.26 / 94.5 / 0 / 8 | 1.81 / 86.4 / 0 / 8 | 1.63 / 83.4 / 0 / 8 | 1.78 / 83.8 / 0 / 8 |
| update 1 row | 0.52 / 52.4 / 1 / 1 | 6.83 / 62.6 / 1 / 1 | 0.62 / 55.4 / 1 / 1 | 0.69 / 55.5 / 1 / 1 | 0.96 / 57.5 / 1 / 1 |
| update every 10th row | 1.39 / 68.2 / 5 / 5 | 8.05 / 76.6 / 5 / 5 | 1.59 / 72.0 / 5 / 5 | 1.76 / 72.5 / 5 / 5 | 2.75 / 72.3 / 5 / 5 |
| reverse | 35.0 / 490 / 50 / 234 | 39.0 / 765 / 0 / 200 | 34.4 / 712 / 0 / 196 | 31.2 / 703 / 0 / 196 | 34.3 / 719 / 0 / 196 |
| shuffle | 30.4 / 452 / 47 / 204 | 38.7 / 689 / 0 / 188 | 27.3 / 527 / 0 / 152 | 26.9 / 515 / 0 / 152 | 27.7 / 518 / 0 / 152 |
| replace all (no overlap) | 33.0 / 461 / 50 / 234 | 137 / 816 / 50 / 200 | 130 / 806 / 50 / 200 | 36.5 / 463 / 50 / 234 | 36.6 / 461 / 50 / 234 |
| fresh equal maps, no change | 6.44 / 6.85 / 50 / 0 | 13.8 / 15.3 / 50 / 0 | 7.68 / 8.43 / 50 / 0 | 10.8 / 11.6 / 50 / 0 | 4.52 / 5.11 / 0 / 0 |
| fresh equal maps, remove first ⇄ insert first | 37.3 / 515 / 49.5 / 231 | 16.7 / 83.2 / 49.5 / 2 | 9.50 / 75.0 / 49.5 / 2 | 42.9 / 532 / 49.5 / 231 | 6.36 / 70.1 / 0.5 / 2 |
| strings w/ duplicates, remove first ⇄ insert first | 5.57 / 172 / 49.5 / 51 | n/a | n/a | 1.02 / 36.7 / 0.5 / 2 | 1.08 / 37.3 / 0.5 / 2 |
| inline render fn, remove first ⇄ insert first | 36.5 / 479 / 49.5 / 231 | 14.8 / 81.4 / 49.5 / 2 | 7.73 / 72.6 / 49.5 / 2 | 7.77 / 71.5 / 49.5 / 2 | 7.72 / 71.1 / 49.5 / 2 |
| inline render fn, swap rows 1 and n-2 | 6.87 / 74.2 / 50 / 10 | 14.3 / 108 / 50 / 8 | 7.60 / 92.0 / 50 / 8 | 7.37 / 92.4 / 50 / 8 | 7.41 / 85.3 / 50 / 8 |

#### n=100

| scenario | simple-seq | keyed-seq :id | keyed-seq new | diff-seq | diff-seq = |
|---|---:|---:|---:|---:|---:|
| remove first ⇄ insert first | 69.2 / 935 / 99.5 / 464 | 14.0 / 121 / 0.5 / 2 | 2.35 / 103 / 0.5 / 2 | 2.27 / 104 / 0.5 / 2 | 2.24 / 106 / 0.5 / 2 |
| remove middle ⇄ insert middle | 35.7 / 502 / 49.5 / 231 | 14.0 / 116 / 0.5 / 2 | 2.21 / 100.0 / 0.5 / 2 | 2.20 / 105 / 0.5 / 2 | 2.23 / 102 / 0.5 / 2 |
| remove last ⇄ append | 1.86 / 96.6 / 0.5 / 2 | 13.9 / 122 / 0.5 / 2 | 2.12 / 98.2 / 0.5 / 2 | 2.00 / 102 / 0.5 / 2 | 2.01 / 99.6 / 0.5 / 2 |
| swap rows 1 and n-2 | 1.72 / 102 / 2 / 8 | 13.5 / 135 / 0 / 8 | 2.12 / 119 / 0 / 8 | 1.96 / 118 / 0 / 8 | 2.07 / 118 / 0 / 8 |
| update 1 row | 0.78 / 88.8 / 1 / 1 | 11.9 / 107 / 1 / 1 | 0.98 / 90.1 / 1 / 1 | 1.05 / 87.3 / 1 / 1 | 1.29 / 90.2 / 1 / 1 |
| update every 10th row | 2.58 / 119 / 10 / 10 | 14.2 / 141 / 10 / 10 | 3.03 / 118 / 10 / 10 | 3.34 / 122 / 10 / 10 | 5.24 / 123 / 10 / 10 |
| reverse | 57.1 / 886 / 100 / 400 | 70.5 / 1325 / 0 / 400 | 67.0 / 1280 / 0 / 396 | 60.8 / 1260 / 0 / 396 | 67.0 / 1265 / 0 / 396 |
| shuffle | 64.2 / 883 / 99 / 446 | 71.9 / 1300 / 0 / 396 | 57.0 / 1100 / 0 / 328 | 56.0 / 1075 / 0 / 328 | 58.9 / 1067 / 0 / 328 |
| replace all (no overlap) | 64.5 / 893 / 100 / 467 | 270 / 1625 / 100 / 400 | 261 / 1563 / 100 / 400 | 71.3 / 896 / 100 / 467 | 70.7 / 918 / 100 / 467 |
| fresh equal maps, no change | 12.9 / 13.3 / 100 / 0 | 25.5 / 27.1 / 100 / 0 | 14.7 / 16.8 / 100 / 0 | 21.2 / 22.3 / 100 / 0 | 8.93 / 9.71 / 0 / 0 |
| fresh equal maps, remove first ⇄ insert first | 72.1 / 923 / 99.5 / 464 | 27.8 / 126 / 99.5 / 2 | 16.8 / 113 / 99.5 / 2 | 81.5 / 904 / 99.5 / 464 | 10.9 / 109 / 0.5 / 2 |
| strings w/ duplicates, remove first ⇄ insert first | 10.6 / 310 / 99.5 / 101 | n/a | n/a | 1.38 / 51.1 / 0.5 / 2 | 1.42 / 56.1 / 0.5 / 2 |
| inline render fn, remove first ⇄ insert first | 72.7 / 1000 / 99.5 / 464 | 26.2 / 137 / 99.5 / 2 | 14.0 / 121 / 99.5 / 2 | 13.9 / 118 / 99.5 / 2 | 13.9 / 118 / 99.5 / 2 |
| inline render fn, swap rows 1 and n-2 | 12.5 / 121 / 100 / 8 | 25.9 / 162 / 100 / 8 | 13.4 / 138 / 100 / 8 | 13.7 / 145 / 100 / 8 | 14.1 / 137 / 100 / 8 |

#### n=1000

| scenario | simple-seq | keyed-seq :id | keyed-seq new | diff-seq | diff-seq = |
|---|---:|---:|---:|---:|---:|
| remove first ⇄ insert first | 682 / 10400 / 999.5 / 4664 | 158 / 1021 / 0.5 / 2 | 10.2 / 839 / 0.5 / 2 | 10.8 / 819 / 0.5 / 2 | 10.5 / 831 / 0.5 / 2 |
| remove middle ⇄ insert middle | 341 / 5700 / 499.5 / 2331 | 160 / 1064 / 0.5 / 2 | 10.1 / 817 / 0.5 / 2 | 10.6 / 863 / 0.5 / 2 | 10.5 / 850 / 0.5 / 2 |
| remove last ⇄ append | 6.20 / 828 / 0.5 / 2 | 155 / 1057 / 0.5 / 2 | 8.40 / 869 / 0.5 / 2 | 8.93 / 894 / 0.5 / 2 | 9.37 / 956 / 0.5 / 2 |
| swap rows 1 and n-2 | 6.59 / 806 / 2 / 8 | 156 / 1164 / 0 / 8 | 8.46 / 888 / 0 / 8 | 8.68 / 887 / 0 / 8 | 8.69 / 881 / 0 / 8 |
| update 1 row | 5.60 / 811 / 1 / 1 | 155 / 1036 / 1 / 1 | 7.15 / 828 / 1 / 1 | 8.66 / 825 / 1 / 1 | 8.76 / 800 / 1 / 1 |
| update every 10th row | 27.9 / 1250 / 100 / 100 | 188 / 1540 / 100 / 100 | 33.4 / 1275 / 100 / 100 | 35.4 / 1258 / 100 / 100 | 52.3 / 1267 / 100 / 100 |
| reverse | 576 / 10150 / 1000 / 4000 | 810 / 14025 / 0 / 4000 | 685 / 14100 / 0 / 3996 | 656 / 14825 / 0 / 3996 | 709 / 14800 / 0 / 3996 |
| shuffle | 679 / 10350 / 999 / 4422 | 850 / 14825 / 0 / 3996 | 703 / 14400 / 0 / 3788 | 632 / 13525 / 0 / 3788 | 703 / 13325 / 0 / 3788 |
| replace all (no overlap) | 659 / 11275 / 1000 / 4667 | 3225 / 18675 / 1000 / 4000 | 2912 / 17625 / 1000 / 4000 | 750 / 10600 / 1000 / 4667 | 833 / 11225 / 1000 / 4667 |
| fresh equal maps, no change | 129 / 133 / 1000 / 0 | 294 / 319 / 1000 / 0 | 150 / 162 / 1000 / 0 | 187 / 203 / 1000 / 0 | 87.0 / 95.2 / 0 / 0 |
| fresh equal maps, remove first ⇄ insert first | 732 / 10650 / 999.5 / 4664 | 304 / 1300 / 999.5 / 2 | 154 / 1042 / 999.5 / 2 | 777 / 9675 / 999.5 / 4664 | 90.0 / 917 / 0.5 / 2 |
| strings w/ duplicates, remove first ⇄ insert first | 105 / 3212 / 999.5 / 1001 | n/a | n/a | 9.08 / 388 / 0.5 / 2 | 8.99 / 391 / 0.5 / 2 |
| inline render fn, remove first ⇄ insert first | 666 / 9800 / 999.5 / 4664 | 276 / 1164 / 999.5 / 2 | 121 / 964 / 999.5 / 2 | 122 / 979 / 999.5 / 2 | 121 / 1013 / 999.5 / 2 |
| inline render fn, swap rows 1 and n-2 | 120 / 1007 / 1000 / 8 | 293 / 1370 / 1000 / 8 | 122 / 1064 / 1000 / 8 | 120 / 1021 / 1000 / 8 | 127 / 1007 / 1000 / 8 |

