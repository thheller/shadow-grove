// css placement, attributes, styles, svg, re-entering <<
export default async (t) => {
  await t.goto("/markup/");
  await t.waitText("css placement");
  const cls = (sel) => t.evaluate(`document.querySelector("${sel}").className.trim().split(/\\s+/).filter(Boolean).length`);

  t.assert(await cls("#first-child") === 1, "first-child css lifted into :class");
  t.assert(await cls("#with-attrs") === 2, "first-child css merged with attrs :class");
  t.assert(await t.evaluate(`document.querySelector("#with-attrs").title`) === "attrs still work", "attrs map after css");
  t.assert(await cls("#vector-class") === 1, "nil in a class vector ignored");
  await t.click({ text: "toggle on?", selector: "button" });
  t.assert(await cls("#vector-class") === 2, "(when on? $x) adds the class");
  t.assert(await t.evaluate(`document.querySelector("#data-aria").getAttribute("data-state")`) === "on", "data-* updates");
  t.assert(await t.exists("#when-branch"), "<< inside when");
  t.assert(/examples_markup/.test(await t.text("#css-not-first")), "css outside first-child renders its classname as text");

  t.assert(await t.evaluate(`getComputedStyle(document.querySelector("#style-map")).color`) === "rgb(102, 51, 153)", ":style map");
  t.assert(await t.evaluate(`document.querySelector("#style-string").style.width`) === "120px", ":style string");
  await t.click({ text: "wider", selector: "button" });
  t.assert(await t.evaluate(`document.querySelector("#style-string").style.width`) === "160px", ":style string updates");
  t.assert(await t.evaluate(`document.querySelector("#style-prop").style.width`) === "160px", ":style/width");

  t.assert(await t.evaluate(`document.querySelector("#ro-input").readOnly`), ":read-only -> readOnly");
  t.assert(await t.evaluate(`document.querySelector("#ro-input").tabIndex`) === -1, ":tab-index -> tabIndex");
  t.assert(await t.evaluate(`document.querySelector("#data-aria").getAttribute("aria-label")`) === "state holder", "aria-*");

  t.assert(await t.evaluate(`[...document.querySelectorAll("svg, svg path")].every((el) => el.namespaceURI === "http://www.w3.org/2000/svg")`), "svg namespace");
};
