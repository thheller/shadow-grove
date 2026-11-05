// ^:stable args, ^:stable in destructure, :supports?
export default async (t) => {
  await t.goto("/stable-args/");
  await t.waitText("showing id 1");
  const value = (label) => t.evaluate(`document.querySelector("[aria-label='${label}']").value`);
  t.assert(await value("updating editor") === "text of item 1", "mount filled the plain editor");

  let log = await t.logOf(() => t.click({ text: "item 2", selector: "button" }));
  t.assert(await value("updating editor") === "text of item 1",
    "a non-stable editor filled by :mount keeps showing the previous entity's text");
  t.assert(await value("stable editor") === "text of item 2", "^:stable editor shows the new item");
  t.assert(log.includes("stable editor: mounted with id 2"), "^:stable remounted");
  t.assert(!log.includes("updating editor: mounted"), "plain editor updated in place");

  const row = () => t.renders({ text: "row ", selector: "[data-demo] > div" });
  const r0 = await row();
  log = await t.logOf(() => t.click({ text: "change label", selector: "button" }));
  t.assert(await row() === r0 + 1 && !log.includes("mounted"), "non-stable key updates in place");
  log = await t.logOf(() => t.click({ text: "change id", selector: "button" }));
  t.assert(log.includes("row: unmounted for id 1") && log.includes("row: mounted for id 2"), `stable key remounts:\n${log}`);

  log = await t.logOf(() => t.click({ text: "change value", selector: "button" }));
  t.assert(!log.includes("mounted"), ":supports? true updates in place");
  log = await t.logOf(() => t.click({ text: "change kind", selector: "button" }));
  t.assert(log.includes("custom: unmounted as :circle") && log.includes("custom: mounted as :square"), `:supports? false remounts:\n${log}`);
};
