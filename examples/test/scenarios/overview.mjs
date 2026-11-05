// one interaction end to end: only what read changed data renders
export default async (t) => {
  await t.goto("/overview/");
  await t.waitText("1 of 3 done");
  const snap = async () => ({
    item1: await t.renders("[data-item='1']"),
    item2: await t.renders("[data-item='2']"),
    item3: await t.renders("[data-item='3']"),
    summary: await t.renders("[data-summary]"),
    checklist: await t.renders({ text: "checklist rendered", selector: "div" }),
  });
  const before = await snap();
  await t.click("[data-item='1'] input");
  await t.waitText("2 of 3 done");
  const after = await snap();
  t.assert(after.item1 === before.item1 + 1, "the toggled row renders");
  t.assert(after.summary === before.summary + 1, "the summary renders");
  t.assert(after.item2 === before.item2 && after.item3 === before.item3, "other rows don't");
  t.assert(after.checklist === before.checklist, "the checklist doesn't, its ids didn't change");
  t.assert(await t.evaluate(`document.querySelector("[data-item='1'] input").checked`), "checkbox follows the data");
};
