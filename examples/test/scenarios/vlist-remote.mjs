// fetching missing rows from the query, suspend with a default, :more?
export default async (t) => {
  await fetch(`${t.base}/vlist-remote/api/reset`, { method: "POST" });
  await t.goto("/vlist-remote/");
  await t.waitFor(`document.querySelectorAll("#big [data-idx]").length > 0`, { message: "first slice arrived" });
  const requests = async () => Number((await t.text("#requests")).split(": ")[1]);
  const first = () => t.evaluate(`Number(document.querySelector("#big [data-idx]").dataset.idx)`);
  const scrollTo = (row) => t.evaluate(`(() => { const el = document.querySelector("#big > div"); el.scrollTop = 28 * ${row}; el.dispatchEvent(new Event("scroll")); })()`);
  const r0 = await requests();

  await scrollTo(3);
  await t.settle(100);
  t.assert(await requests() === r0, "scrolling within the slack doesn't query");

  await scrollTo(50000);
  await t.settle(10);
  t.assert(await first() < 100, "previous rows stay while the query is suspended");
  await t.waitFor(`[...document.querySelectorAll("#big [data-idx]")].some((r) => r.dataset.idx === "50000")`, { message: "rows around 50000 fetched" });
  t.assert(await requests() === r0 + 1, "one fetch for the new window");

  const spacer = () => t.evaluate(`document.querySelector("#growing > div > div").style.height`);
  t.assert(await spacer() === `${301 * 28}px`, `300 rows + the more row (${await spacer()})`);
  await t.evaluate(`(() => { const el = document.querySelector("#growing > div"); el.scrollTop = 28 * 300; el.dispatchEvent(new Event("scroll")); })()`);
  await t.waitFor(`!!document.querySelector("#more")`, { message: ":more-fn row" });
  await t.click("#more");
  await t.waitFor(`document.querySelector("#growing > div > div").style.height === "${601 * 28}px"`, { message: "item-count grew" });
};
