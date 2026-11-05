// one claim per bind, custom sources, claim-slot!, claims under a condition
export default async (t) => {
  await t.goto("/bind-helpers/");
  await t.waitText('{1 "one", 2 "two", 3 "three"}');

  t.expectConsoleError(/component failed run properly|ExceptionInfo|slot already claimed/);
  await t.click({ text: "try two reads in one bind", selector: "button" });
  await t.settle(100);
  const rendered = await t.exists({ text: '["one" "two"]', selector: "span" });
  const errors = t.takeErrors();
  t.assert(!rendered, "two reads in one bind don't render");
  // the console only shows the ExceptionInfo object, its message is "slot already claimed"
  t.assert(errors.some((e) => /component failed run properly/.test(e)), `two reads in one bind throw: ${errors}`);

  const ticker = () => t.renders({ text: "ticks / 5", selector: "[data-demo] > div" });
  const k0 = await ticker();
  await t.sleep(2100);
  const k1 = await ticker();
  t.assert(k1 - k0 >= 1 && k1 - k0 <= 3, `ticker renders every 5th tick (${k0} -> ${k1})`);

  await t.waitFor(`/seconds mounted: [1-9]/.test(document.body.innerText)`, { message: "outside swap! on the claimed ref re-renders" });
  let log = await t.logOf(() => t.click({ text: "unmount", selector: "button" }));
  t.assert(log.includes("always: interval cleared"), "set-cleanup! runs on unmount");

  await t.click({ text: "turn on", selector: "button" });
  await t.waitText("conditional claim: on, 1", { timeout: 3000 });
  log = await t.logOf(async () => {
    await t.click({ text: "turn off", selector: "button" });
    await t.sleep(1500);
  });
  t.assert(!log.includes("conditional: interval cleared"), "a claim under a when that turned false isn't released");
  t.assert(log.includes("conditional: interval fired"), "it keeps running until unmount");
};
