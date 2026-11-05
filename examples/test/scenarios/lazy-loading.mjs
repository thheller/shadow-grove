// lazy/use with a module loaded on demand
export default async (t) => {
  await t.goto("/lazy-loading/");
  await t.waitText("show chart");
  const loaded = () => t.evaluate(`performance.getEntriesByType("resource").some((r) => r.name.endsWith("/lazy-loading-heavy.js"))`);
  t.assert(!(await loaded()), "lazy-loading-heavy.js not loaded before use");
  await t.click({ text: "show chart", selector: "button" });
  await t.waitFor(`!!document.querySelector("#bar-chart")`, { message: "chart rendered after the module loaded" });
  t.assert(await loaded(), "lazy-loading-heavy.js loaded on demand");
  t.assert((await t.log()).includes("heavy module loaded"), "module code ran");
  const bars = () => t.evaluate(`document.querySelectorAll("#bar-chart rect").length`);
  t.assert(await bars() === 5, "5 bars");
  await t.click({ text: "add a value", selector: "button" });
  t.knownIssue(await bars() === 0, `new args reach the lazy fn as nil, LoadableRoot.dom-sync! reads (.-opts next) but the field is args (${await bars()} bars)`);
};
