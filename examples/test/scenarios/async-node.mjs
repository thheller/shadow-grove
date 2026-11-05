// async content that cooperates with suspense
export default async (t) => {
  await t.goto("/async-node/");
  await t.waitFor(`!!document.querySelector("#fallback")`);
  await t.sleep(500);
  t.assert(await t.exists("#fallback") && !(await t.exists("#fast")), "fallback until every node finished");
  await t.waitFor(`!!document.querySelector("#slow")`, { timeout: 2000 });
  t.assert(await t.exists("#fast") && !(await t.exists("#fallback")), "both in, fallback gone");
  t.assert(await t.evaluate(`document.querySelector("#content").innerText.replace(/\\s+/g, " ").trim()`) === "fast content, round 0 slow content, round 0", "content in order after the markers");

  await t.click({ text: "again", selector: "button" });
  await t.waitFor(`!!document.querySelector("#fallback")`);
  await t.waitText("slow content, round 1", { timeout: 2000 });

  await t.click({ text: "again", selector: "button" });
  await t.sleep(100);
  await t.click({ text: "hide", selector: "button" });
  await t.sleep(1000);
  t.assert(!(await t.exists("#fast")), "destroyed before finishing: nothing inserted later");
};
