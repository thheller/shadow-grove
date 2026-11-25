// simple-seq, diff-seq, diff-seq-by-value, keyed-seq
export default async (t) => {
  await t.goto("/lists/");
  await t.waitText("keyed-seq (:id)");
  const cols = ["simple", "diff", "diff-value", "keyed"];
  // [label, instance, idx, input value] per row
  const rows = (col) => t.evaluate(`[...document.querySelectorAll("#${col} [data-label]")].map((r) => [
    r.dataset.label, r.querySelector("[data-instance]").dataset.instance, r.querySelector("[data-idx]").dataset.idx, r.querySelector("input").value])`);

  // every row shows its current position, whatever moved
  const idxCurrent = async (action) => {
    for (const col of cols) {
      const stale = (await rows(col)).map((r, i) => [r[0], r[2], i]).filter(([, idx, i]) => idx !== String(i));
      t.assert(stale.length === 0, `${col}: idx current after ${action}, stale: ${JSON.stringify(stale)}`);
    }
  };

  for (const col of cols) {
    await t.evaluate(`(() => { const i = document.querySelector("#${col} [data-label='item 1'] input"); i.value = "typed"; })()`);
  }
  await t.click({ text: "remove first", selector: "button" });
  await idxCurrent("remove first");
  const typedOn = async (col) => (await rows(col)).filter((r) => r[3] === "typed").map((r) => r[0]);
  t.assert(JSON.stringify(await typedOn("simple")) === '["item 2"]', "simple-seq renders item 2 over item 1's DOM, the input text moves to it");
  for (const col of ["diff", "diff-value", "keyed"]) {
    t.assert((await typedOn(col)).length === 0, `${col}: the removed row took its DOM with it`);
  }

  // moves: keyed and matched rows keep their instance, positional ones don't
  const before = Object.fromEntries(await Promise.all(cols.map(async (c) => [c, await rows(c)])));
  await t.click({ text: "reverse", selector: "button" });
  const after = Object.fromEntries(await Promise.all(cols.map(async (c) => [c, await rows(c)])));
  t.assert(after.simple[0][1] === before.simple[0][1], "simple-seq: first row keeps its instance, renders the new item");
  for (const col of ["diff", "diff-value", "keyed"]) {
    t.assert(after[col][0][1] === before[col].at(-1)[1], `${col}: the last row's instance moved to the top`);
  }
  await idxCurrent("reverse");

  await t.click({ text: "shuffle", selector: "button" });
  await idxCurrent("shuffle");
  await t.click({ text: "insert at top", selector: "button" });
  await idxCurrent("insert at top");

  // fresh equal maps: diff-seq can't match them, by-value can
  const b2 = Object.fromEntries(await Promise.all(cols.map(async (c) => [c, await rows(c)])));
  await t.click({ text: "reverse as fresh maps", selector: "button" });
  const a2 = Object.fromEntries(await Promise.all(cols.map(async (c) => [c, await rows(c)])));
  t.assert(a2.diff[0][1] === b2.diff[0][1], "diff-seq: fresh maps don't match, rendered over in place");
  t.assert(a2["diff-value"][0][1] === b2["diff-value"].at(-1)[1], "diff-seq-by-value: fresh equal maps still match");
  await idxCurrent("reverse as fresh maps");

  // sg/each: an inline fn renders every row on every parent render, each only what changed
  const renders = (col) => t.evaluate(`[...document.querySelectorAll("#${col} [data-renders]")].map((r) => Number(r.dataset.renders))`);
  const ticks = () => t.evaluate(`document.querySelector("[data-ticks]").dataset.ticks`);
  const same = (a, b) => JSON.stringify(a) === JSON.stringify(b);
  const inc = (xs, by) => xs.map((x, i) => x + by[i]);
  let inline0 = await renders("rows-inline"), each0 = await renders("rows-each");
  t.assert(same(inline0, [1, 1, 1]) && same(each0, [1, 1, 1]), `each demo: every row rendered once, ${inline0} ${each0}`);

  await t.click({ text: "render parent", selector: "#each-demo button" });
  await t.waitFor(`document.querySelector("[data-ticks]").dataset.ticks === "1"`);
  t.assert(same(await renders("rows-inline"), inc(inline0, [1, 1, 1])), "inline fn: parent render renders every row");
  t.assert(same(await renders("rows-each"), each0), "sg/each: parent render renders no row");

  inline0 = await renders("rows-inline"); each0 = await renders("rows-each");
  await t.click({ text: "rename first", selector: "#each-demo button" });
  await t.settle(50);
  t.assert(same(await renders("rows-inline"), inc(inline0, [1, 1, 1])), "inline fn: renaming one item renders every row");
  t.assert(same(await renders("rows-each"), inc(each0, [1, 0, 0])), "sg/each: renaming one item renders only that row");

  inline0 = await renders("rows-inline"); each0 = await renders("rows-each");
  await t.click({ text: "mark next", selector: "#each-demo button" });
  await t.settle(50);
  t.assert(same(await renders("rows-each"), inc(each0, [1, 1, 1])), "sg/each: a changed dep renders every row");

  // keyed rows move, the body doesn't use idx
  const labels = (col) => t.evaluate(`[...document.querySelectorAll("#${col} [data-label]")].map((r) => r.dataset.label)`);
  const labels0 = await labels("rows-each");
  inline0 = await renders("rows-inline"); each0 = await renders("rows-each");
  await t.click({ text: "reverse", selector: "#each-demo button" });
  await t.settle(50);
  t.assert(same(await labels("rows-each"), [...labels0].reverse()), "sg/each: reverse moved the rows");
  t.assert(same(await renders("rows-inline"), inc([...inline0].reverse(), [1, 1, 1])), "inline fn: reverse renders every row");
  t.assert(same(await renders("rows-each"), [...each0].reverse()), "sg/each without idx: moved rows don't render");

  t.expectConsoleError(/duplicate|key/i);
  await t.click({ text: "duplicate a key", selector: "button" });
  await t.settle(50);
  t.assert(t.takeErrors().length > 0, "keyed-seq complains about duplicate keys in dev");
};
