// :use-fragment
export default async (t) => {
  await t.goto("/hash-routing/");
  await t.waitText("page: :one");
  t.assert(await t.evaluate("location.hash") === "#/one", "start-token written to the hash");
  t.assert(await t.evaluate(`document.querySelector("#link-two").getAttribute("href")`) === "#/two", ":ui/href writes #/...");
  await t.click("#link-two");
  await t.waitText("page: :two");
  await t.evaluate("history.back()");
  await t.waitText("page: :one");

  await t.click({ text: ":ui/redirect! to /two", selector: "button" });
  await t.waitText("page: :two");
  const loc = await t.evaluate("location.pathname + location.hash");
  t.knownIssue(loc !== "/hash-routing/#/two", `:ui/redirect! with :use-fragment pushes a path instead of the hash (location is ${loc})`);
};
