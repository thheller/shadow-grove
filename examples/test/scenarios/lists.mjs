// simple-seq, diff-seq, diff-seq-by-value, keyed-seq
export default async (t) => {
  await t.goto("/lists/");
  await t.waitText("keyed-seq (:id)");
  const cols = ["simple", "diff", "diff-value", "keyed"];
  // [label, instance, idx, input value] per row
  const rows = (col) => t.evaluate(`[...document.querySelectorAll("#${col} [data-label]")].map((r) => [
    r.dataset.label, r.querySelector("[data-instance]").dataset.instance, r.querySelector("[data-idx]").dataset.idx, r.querySelector("input").value])`);

  for (const col of cols) {
    await t.evaluate(`(() => { const i = document.querySelector("#${col} [data-label='item 1'] input"); i.value = "typed"; })()`);
  }
  await t.click({ text: "remove first", selector: "button" });
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
  t.assert(after.keyed[0][2] !== "0", "keyed-seq: a moved item with identical data keeps its stale idx");

  // fresh equal maps: diff-seq can't match them, by-value can
  const b2 = Object.fromEntries(await Promise.all(cols.map(async (c) => [c, await rows(c)])));
  await t.click({ text: "reverse as fresh maps", selector: "button" });
  const a2 = Object.fromEntries(await Promise.all(cols.map(async (c) => [c, await rows(c)])));
  t.assert(a2.diff[0][1] === b2.diff[0][1], "diff-seq: fresh maps don't match, rendered over in place");
  t.assert(a2["diff-value"][0][1] === b2["diff-value"].at(-1)[1], "diff-seq-by-value: fresh equal maps still match");

  t.expectConsoleError(/duplicate|key/i);
  await t.click({ text: "duplicate a key", selector: "button" });
  await t.settle(50);
  t.assert(t.takeErrors().length > 0, "keyed-seq complains about duplicate keys in dev");
};
