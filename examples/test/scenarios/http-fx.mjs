// http-fx
export default async (t) => {
  await fetch(`${t.base}/http-fx/api/reset`, { method: "POST" });
  await t.goto("/http-fx/");
  await t.waitText("load items");
  const items = () => t.text("[data-items]");
  const click = (label) => t.click({ text: label, selector: "button" });
  t.expectConsoleError(/request error|status/);

  await click("load items");
  await t.waitText('items: ["first" "second"]');

  await t.type("input[name=label]", "third");
  await click("create");
  await t.waitText('items: ["first" "second" "third"]');
  await t.type("input[name=label]", "!bad");
  await click("create");
  await t.waitFor(`document.querySelector("[data-error]")?.innerText.includes("422")`, { message: "handler :on-error" });
  t.assert(!(await items()).includes("!bad"), "pessimistic: nothing written on error");

  await click("add optimistically");
  t.assert((await items()).includes('"optimistic"'), "optimistic: shown before the response");
  await t.sleep(500);
  t.assert((await items()).includes('"optimistic"'), "confirmed");
  await click("add optimistically (server rejects)");
  t.assert((await items()).includes('"!optimistic"'), "shown at once");
  await t.waitFor(`document.querySelector("[data-error]")?.innerText.includes("rolled back")`, { message: "request :on-error replaces the handler's" });
  t.assert(!(await items()).includes('"!optimistic"'), "rolled back");

  let log = await t.logOf(async () => { await click("GET /status/404"); await t.sleep(500); });
  t.assert(log.includes("request-error! 404 /http-fx/api/status/404"), `vector request, status and sent-request:\n${log}`);

  await t.click("#slow");
  t.assert(await t.evaluate(`document.querySelector("#slow").disabled`), "trace-state :started disables the button");
  t.assert((await t.text("#slow-trace")).includes(":started"), "trace :started");
  await t.waitFor(`document.querySelector("#slow-trace").innerText.includes(":completed")`, { timeout: 4000 });
  t.assert(/:duration \d+/.test(await t.text("#slow-trace")), "duration recorded");

  await click("same with :timeout 500");
  await t.sleep(1000);
  const timeoutTrace = await t.text("#timeout-trace");
  t.knownIssue(timeoutTrace.includes(":started"), `a request hitting :timeout never ends, the trace stays ${timeoutTrace} and no :on-error runs (http-fx only console.logs)`);

  await click("items + count");
  await t.waitFor(`document.querySelector("#many").innerText.includes(":count {:count")`, { message: ":request-many result map" });

  await click("transit roundtrip");
  await t.waitFor(`document.querySelector("#transit").innerText.includes(":received")`);
  const tr = await t.text("#transit");
  t.assert(tr.includes(":kw :a-keyword") && tr.includes("#{1 2}") && tr.includes("#inst"), `transit types survive: ${tr}`);
  t.takeErrors();
};
