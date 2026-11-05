// keyboard events
export default async (t) => {
  await t.goto("/keyboard/");
  await t.waitText("ctrl+enter in a textarea");

  await t.type("#code", "abc");
  let log = await t.logOf(() => t.key("Enter", ["ctrl"]));
  await t.waitText('submitted: ["abc"]');
  t.assert(log.includes("ev = [:shadow.grove.keyboard/ctrl+enter]"), `slot ev is [event-id]:\n${log}`);
  t.assert(await t.evaluate(`document.querySelector("#code").value`) === "", "slot cleared the node");

  await t.focus("[data-panel=b]");
  await t.waitText("b (active)");
  t.assert(await t.evaluate(`document.querySelector("[data-panel=a]").tabIndex`) === -1, "inactive panel tab-index -1");
  t.assert(await t.evaluate(`document.querySelector("[data-panel=b]").tabIndex`) === 0, "active panel tab-index 0");
  await t.key("ArrowDown");
  await t.key("ArrowDown");
  t.assert(await t.evaluate(`document.querySelector("[data-panel=b] [data-focus]").textContent`) === "row 2", "arrowdown moved");
  await t.key("Escape");
  t.assert(await t.evaluate(`document.querySelector("[data-panel=b] [data-focus]").textContent`) === "row 0", "escape reset");
  t.assert(await t.evaluate(`document.querySelector("[data-panel=a] [data-focus]").textContent`) === "row 0", "other panel untouched");

  await t.focus("#inner");
  log = await t.logOf(() => t.key("Enter"));
  t.assert(log.includes("inner: enter"), "owner's slot fires");
  t.assert(log.includes("outer: enter"), "the DOM event bubbles to the listening ancestor");
  log = await t.logOf(() => t.key("Escape"));
  t.assert(log.includes("inner: escape") && !log.includes("outer: escape"), `stopPropagation keeps it from the ancestor:\n${log}`);
};
