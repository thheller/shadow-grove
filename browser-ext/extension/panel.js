const rootEl = document.getElementById("root");

function init() {
  chrome.devtools.inspectedWindow.eval(
    "shadow.grove.preload.extension_info()",
    function (result, isException) {
      if (isException) {
        console.log("shadow-grove not found on page");
        rootEl.innerHTML = "shadow-grove not found on page.";
      } else {
        console.log("shadow-grove found on page", result);

        var iframe = document.createElement("iframe");
        iframe.src =
          "http" +
          (result.ssl ? "s" : "") +
          "://" +
          result.server_host +
          ":" +
          result.server_port + 
          "/classpath/shadow/grove/devtools.html?runtime=" +
          result.client_id;

        rootEl.innerHTML = "";
        rootEl.append(iframe);
      }
    }
  );
}

function stop() {
    rootEl.innerHTML = "";
}