// handle in a ref, query window, keyboard, select, set-opts!, focus-row!
export default async (t) => {
  await t.goto("/vlist/");
  await t.waitFor(`document.querySelectorAll("#rows [data-idx]").length > 0`);
  const idxs = (sel) => t.evaluate(`[...document.querySelectorAll("${sel} [data-idx]")].map((r) => Number(r.dataset.idx))`);
  let rows = await idxs("#rows");
  t.assert(rows[0] === 0 && rows.length < 60, `only the window is rendered (${rows.length} rows)`);

  await t.evaluate(`(() => { const el = document.querySelector("#rows > div"); el.scrollTop = 28 * 3000; el.dispatchEvent(new Event("scroll")); })()`);
  await t.waitFor(`[...document.querySelectorAll("#rows [data-idx]")].some((r) => r.dataset.idx === "3000")`, { message: "scrolling moves the window" });

  await t.click({ text: "focus row 5000", selector: "button" });
  await t.focus("#rows > div");
  await t.waitFor(`document.querySelector("#rows [data-focus]")?.dataset.idx === "5000"`, { message: "focus-row! scrolled to 5000" });
  await t.key("ArrowDown");
  await t.waitFor(`document.querySelector("#rows [data-focus]")?.dataset.idx === "5001"`);
  const log = await t.logOf(() => t.key("Enter"));
  t.assert(log.includes("selected idx 5001 row 5001"), `enter dispatches :select-event:\n${log}`);
  await t.waitText('selected: "row 5001"');
  await t.key("Home");
  await t.waitFor(`document.querySelector("#rows [data-focus]")?.dataset.idx === "0"`, { message: "home" });

  await t.type("#filter", "999");
  await t.waitFor(`document.querySelectorAll("#rows mark").length > 0`, { message: "set-query! + set-item-fn! from hooks" });
  rows = await idxs("#rows");
  t.assert(rows.length === 19, `filtered to 19 rows: 999, x999, 9990-9998 (${rows.length})`);

  t.assert(await t.evaluate(`document.querySelector("#rows > div").tabIndex`) === 0, "active: tab-index 0");
  await t.click({ text: "active, tab-index 0", selector: "button" });
  t.assert(await t.evaluate(`document.querySelector("#rows > div").tabIndex`) === -1, "set-opts! from a hook");


  t.assert(await t.evaluate(`getComputedStyle(document.querySelector("#map-rows > div > div > div")).display`) === "grid", ":box-class grid");
  t.assert(await t.evaluate(`document.querySelector("#map-rows").innerText.includes(":key-0")`), "one-call vlist3/render");
};
