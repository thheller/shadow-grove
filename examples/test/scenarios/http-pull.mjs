// http-pull
export default async (t) => {
  // known issue: suspense's fallback swap runs outside a devtools trace (see suspense.mjs)
  t.expectConsoleError(/not tracing\?/);
  await fetch(`${t.base}/http-pull/api/reset`, { method: "POST" });
  await t.goto("/http-pull/");
  t.assert(await t.exists("#list-loading"), "list suspends while pending");
  await t.waitText("alpha");
  t.assert(!(await t.exists("#list-loading")), "list rendered from KV");
  await t.waitText("info (no table): {:server");

  await t.click({ text: "alpha", selector: "button" });
  t.assert(await t.exists("#detail-loading"), "detail suspends");
  await t.waitText("alpha · alpha details · rev 1");

  await t.click({ text: "rename (server)", selector: "button" });
  let sawFallback = false;
  for (let i = 0; i < 20 && !(await t.evaluate(`document.querySelector("#detail")?.innerText.includes("rev 2")`)); i++) {
    if (await t.exists("#detail-loading")) sawFallback = true;
    await t.sleep(100);
  }
  await t.waitText("alpha! · alpha details · rev 2");
  t.assert(!sawFallback, "invalidate! refetches without suspending again");
  await t.waitFor(`document.querySelector("#list").innerText.includes("alpha!")`, { message: "list invalidated too" });

  await t.click({ text: "beta", selector: "button" });
  await t.waitText("beta · beta details");

  await t.type("#search", "al");
  await t.waitFor(`document.querySelector("#results")?.innerText.includes("alpha!")`, { message: "POST pull with :transform" });

  await t.waitFor(`document.querySelector("#time").innerText.includes(":success")`);
  const t1 = await t.text("#time");
  await t.sleep(1500);
  t.assert(await t.text("#time") !== t1, ":refresh-every polls");

  await t.waitFor(`document.querySelector("#fail").innerText.includes(":state :error")`);
  t.assert((await t.text("#fail")).includes(":status 500"), "error result map");
  t.assert((await t.log()).includes("pull-fail :on-error, status 500"), ":on-error ran");
};
