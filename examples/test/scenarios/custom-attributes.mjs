// custom attributes: per-element behavior and the handler lifecycle
export default async (t) => {
  await t.goto("/custom-attributes/");
  await t.waitText("registering a handler");
  const rows = () => t.evaluate(`document.querySelector("#text").rows`);
  await t.waitFor(`document.querySelector("#text").rows === 2`, { message: "initial fit after the rAF" });
  let log = await t.log();
  t.assert(log.includes("oval nil nval 6"), `handler on create:\n${log}`);

  await t.type("#text", "\nthree\nfour\nfive\nsix\nseven\neight");
  t.assert(await rows() === 6, `grows up to max 6, got ${await rows()}`);

  log = await t.logOf(() => t.click({ text: "max 3 rows", selector: "button" }));
  t.assert(log.includes("oval 6 nval 3"), `handler on change:\n${log}`);
  t.assert(await rows() === 3, "refit to the new max");

  log = await t.logOf(() => t.click({ text: "turn it off", selector: "button" }));
  t.assert(log.includes("oval 3 nval nil"), `handler on removal of the attribute:\n${log}`);
  await t.type("#text", "\nnine");
  t.assert(await rows() === 3, "listener removed, no more fitting");

  await t.click({ text: "turn it on", selector: "button" });
  log = await t.logOf(() => t.click({ text: "remove the textarea", selector: "button" }));
  t.assert(log.includes("oval 3 nval nil"), `a destroyed fragment clears its namespaced attributes:\n${log}`);
  t.assert(!(await t.exists("#text")), "textarea gone");
};
