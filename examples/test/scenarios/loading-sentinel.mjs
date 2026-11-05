// a :db/loading sentinel + suspend for data arriving from outside
export default async (t) => {
  await t.goto("/loading-sentinel/");
  await t.waitFor(`!!document.querySelector("[data-fallback=sentinel]")`, { message: "sentinel suspends while loading" });
  t.assert(await t.exists("[data-login=nil-only]"), "nil for both shows the login form while loading");
  await t.waitFor(`!!document.querySelector("[data-welcome=sentinel]")`, { timeout: 2000 });
  t.assert(await t.exists("[data-welcome=nil-only]"), "both show the user once it arrived");
  let log = await t.log();
  t.assert(log.includes("nil-only showed the login form") && !log.includes("sentinel showed the login form"), "only nil-for-both flashed the form");

  log = await t.logOf(async () => {
    await t.click({ text: "reload session", selector: "button" });
    await t.sleep(300);
    t.assert(await t.exists("[data-welcome=sentinel]"), "a mounted component suspending keeps its last output");
    t.assert(await t.exists("[data-login=nil-only]"), "nil-only flashes the form again");
    await t.waitFor(`!!document.querySelector("[data-welcome=nil-only]")`, { timeout: 2000 });
  });
  t.assert(!log.includes("sentinel showed the login form"), "sentinel never showed the form");

  await t.click({ text: "log out", selector: "button" });
  t.assert(await t.exists("[data-login=sentinel]") && await t.exists("[data-login=nil-only]"), "nil is logged out");
};
