// ref, IRefInit, IRefDestroy, IRefRender
export default async (t) => {
  await t.goto("/refs/");
  await t.waitText("seed 1");
  t.assert(await t.evaluate(`document.activeElement?.placeholder === "focused on mount"`), "effect saw the :dom/ref node");
  let log = await t.log();
  t.assert(log.includes("@input is a INPUT"), "ref set by the time :mount runs");
  t.assert(log.includes("IRefInit: building runtime info"), "IRefInit called");
  await t.assertText("app-id from the IRefInit ref: :app");

  const created = async () => (await t.text({ text: "ref created at", selector: "[data-demo] > div" })).match(/created at (\S+),/)[1];
  const c0 = await created();
  log = await t.logOf(() => t.click({ text: "n + 1", selector: "button" }));
  t.assert(await created() === c0 && !log.includes("ref init ran"), "ref init doesn't run again on update");
  t.assert(log.includes("IRefRender: render took"), "IRefRender around an actual render");

  log = await t.logOf(() => t.click({ text: "n + 2", selector: "button" }));
  t.assert(!log.includes("IRefRender"), `IRefRender not called when render was skipped:\n${log}`);

  log = await t.logOf(() => t.click({ text: "new seed", selector: "button" }));
  t.assert(log.includes("ref init ran for seed 2"), "^:stable change creates new refs");

  log = await t.logOf(() => t.click({ text: "hide", selector: "button" }));
  const second = log.indexOf("second ref, destroyed first");
  const first = log.indexOf("first ref, destroyed last");
  t.assert(second >= 0 && first > second, `IRefDestroy in reverse order:\n${log}`);
};
