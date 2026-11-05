// a managed node providing the slot for tracked reads in user code
export default async (t) => {
  await t.goto("/slot-provider/");
  await t.waitText("even n, read by the node: 0");

  let log = await t.logOf(() => t.click({ text: "n + 1", selector: "button" }));
  t.assert(log.includes("read-n ran"), "a change of n runs the read again");
  await t.assertText("n = 1");
  await t.assertText("even n, read by the node: 0");
  await t.click({ text: "n + 1", selector: "button" });
  await t.waitText("even n, read by the node: 2");

  log = await t.logOf(() => t.click({ text: "other + 1", selector: "button" }));
  t.assert(!log.includes("read-n ran"), `a key the read didn't touch runs nothing:\n${log}`);

  await t.click({ text: "hide", selector: "button" });
  log = await t.logOf(() => t.click({ text: "n + 1", selector: "button" }));
  t.assert(!log.includes("read-n ran"), "destroy! ran the slot cleanup, the read is released");

  await t.click({ text: "show", selector: "button" });
  await t.assertNoText("even n, read by the node");
  await t.click({ text: "n + 1", selector: "button" });
  await t.waitText("even n, read by the node: 4");
};
