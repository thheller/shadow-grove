// EDN display
export default async (t) => {
  await t.goto("/edn-view/");
  await t.waitText("render-edn with a value");
  const v = await t.text("#edn-value");
  t.assert(v.includes(":name") && v.includes('"example"') && v.includes(":deepest"), "render-edn shows the map");
  t.assert((await t.text("#edn-str")).includes(":c"), "render-edn-str parsed the text");
  await t.fill("textarea", "[1 2 {:changed true}]");
  await t.waitFor(`document.querySelector("#edn-str").innerText.includes(":changed")`, { message: "re-parses on input" });
  t.assert((await t.text("#edn-diff")).length > 0, "edn-diff renders");
};
