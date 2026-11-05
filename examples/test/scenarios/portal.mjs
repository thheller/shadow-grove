// portal
export default async (t) => {
  await t.goto("/portal/");
  await t.waitText("open dialog");
  await t.click({ text: "open dialog", selector: "button" });
  await t.waitFor(`document.querySelector("#overlay")?.parentElement === document.body`, { message: "overlay is a child of body" });
  await t.click({ text: "n + 1", selector: "button" });
  t.assert(await t.text("#portal-n") === "1", "reads inside the portal update");
  t.assert(await t.text("#side-target") === "rendered into #side-target, n = 1", "portal into a given node");
  const log = await t.logOf(() => t.click({ text: "ping the owner", selector: "button" }));
  t.assert(log.includes("owner slot handled ::local-ping!"), "events from the portal reach the owner's slot");
  await t.click({ text: "close", selector: "button" });
  t.assert(!(await t.exists("#overlay")), "closing removes the portal content");
};
