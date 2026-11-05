// history/init! with :path-prefix, :ui/route!, :ui/href, :ui/redirect!
export default async (t) => {
  await t.goto("/routing/");
  await t.waitFor(`!!document.querySelector("#tabs")`);
  const path = () => t.evaluate("location.pathname");
  t.assert(await path() === "/routing/items", ":start-token");
  await t.evaluate("window.__noReload = true");

  await t.click({ text: "second item", selector: "a" });
  t.assert(await path() === "/routing/item/2", "pushState with prefix");
  t.assert(await t.text("#item-title") === "second item", "page from route data");
  const tabs = await t.renders("#tabs");
  let log = await t.logOf(() => t.click({ text: "other item", selector: "a" }));
  t.assert(log.includes("ui-item mounted for 1"), "^:stable id remounts the page");
  t.assert(await t.renders("#tabs") === tabs, "tabs only get :tab, item -> item doesn't render them");

  await t.click({ text: "edit", selector: "a" });
  t.assert(await t.evaluate(`document.querySelector("#draft").value`) === "first item", "route tx seeded the draft");
  await t.fill("#draft", "renamed");
  await t.click({ text: "save", selector: "button" });
  await t.waitFor(`location.pathname === "/routing/item/1"`);
  t.assert(await t.text("#item-title") === "renamed", "saved, redirected");

  await t.click({ text: "delete", selector: "button" });
  await t.waitText("really delete?");
  await t.click({ text: "About", selector: "nav a" });
  await t.evaluate("history.back()");
  await t.waitFor(`location.pathname === "/routing/item/1"`);
  await t.settle();
  await t.assertNoText("really delete?");
  await t.click({ text: "delete", selector: "button" });
  await t.click({ text: "yes, delete", selector: "button" });
  await t.waitFor(`location.pathname === "/routing/items"`, { message: ":ui/redirect! from the delete tx" });
  await t.assertNoText("renamed");

  await t.click({ text: "an item that doesn't exist", selector: "a" });
  await t.waitFor(`location.pathname === "/routing/items"`, { message: "missing entity redirects" });
  await t.click({ text: "an unknown URL", selector: "a" });
  await t.waitFor(`location.pathname === "/routing/items"`, { message: "unknown URL redirects" });

  await t.click({ text: "About", selector: "nav a" });
  await t.click("#plain-href");
  t.assert(await path() === "/routing/items", "plain :href with the prefix is intercepted");
  t.assert(await t.evaluate("window.__noReload === true"), "no page load happened");
  t.assert(await t.evaluate(`document.querySelector("#tabs a").getAttribute("href")`) === "/routing/items", ":ui/href adds the prefix");

  await t.goto("/routing/item/3");
  await t.waitText("third item");
};
