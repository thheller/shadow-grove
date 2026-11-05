// wrapping a third-party widget
export default async (t) => {
  await t.goto("/widget-node/");
  await t.waitFor(`document.querySelectorAll("[data-chart] canvas").length === 2`, { message: "widgets constructed" });
  const widths = (await t.log()).match(/element width (\d+)/g);
  t.assert(widths && widths.every((w) => Number(w.split(" ").pop()) > 0), `constructed after dom-entered!, measured > 0: ${widths}`);

  const clickBar = (id) => t.evaluate(`(() => { const c = document.querySelector("[data-chart=${id}] canvas"); const r = c.getBoundingClientRect();
    c.dispatchEvent(new MouseEvent("click", { clientX: r.left + 2, clientY: r.top + 30, bubbles: true })); })()`);
  let log = await t.logOf(() => clickBar("a"));
  t.assert(log.includes("chart a bar 0 selected"), `widget event reached the component slot:\n${log}`);

  await t.evaluate(`document.querySelector("[data-chart=a] canvas").__mark = true`);
  log = await t.logOf(() => t.click({ text: "reverse", selector: "button" }));
  t.assert(await t.evaluate(`document.querySelector("[data-chart]").dataset.chart`) === "b", "reversed");
  t.assert(await t.evaluate(`document.querySelector("[data-chart=a] canvas").__mark === true`), "the widget moved with dom-insert, not rebuilt");
  t.assert(!log.includes("constructed") && !log.includes("destroyed"), "moving doesn't reconstruct");

  log = await t.logOf(() => t.click({ text: "add a value to the first", selector: "button" }));
  t.assert(!log.includes("constructed"), "new opts go through dom-sync!");

  log = await t.logOf(() => t.click({ text: "hide", selector: "button" }));
  t.assert((log.match(/widget destroyed/g) || []).length === 2, `destroy! for each:\n${log}`);

  const output = () => t.evaluate(`document.querySelector("output").textContent`);
  await t.evaluate(`document.querySelector("output").__mark = true`);
  t.assert(await output() === "label 0", "metadata IConstruct renders");
  await t.click({ text: "n + 1", selector: "button" });
  t.assert(await output() === "label 1" && await t.evaluate(`document.querySelector("output").__mark === true`), "identical-creator? keeps the node");
};
