// async callbacks start a new tx with run-tx!
export default async (t) => {
  await t.goto("/external-events/");
  await t.waitText("ticks");
  const ticks = async () => Number((await t.text("#ticks")).split(": ")[1]);
  const t0 = await ticks();
  await t.sleep(1100);
  t.assert(await ticks() > t0, "timer -> run-tx!");

  await t.cdp("Emulation.setDeviceMetricsOverride", { width: 777, height: 600, deviceScaleFactor: 1, mobile: false });
  await t.waitText("window width (resize listener → run-tx!): 777");
  await t.cdp("Emulation.clearDeviceMetricsOverride");

  const t2 = await t.openTab("/external-events/");
  await t2.waitText("ticks");
  await t.click({ text: "send", selector: "button" });
  await t2.waitText('"hello 0"');
  await t2.click({ text: "send", selector: "button" });
  await t.waitText('"hello 0"');
  await t2.close();
};
