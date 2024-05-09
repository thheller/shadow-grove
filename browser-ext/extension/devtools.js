var panelWin = null;

chrome.devtools.panels.create(
  "shadow-grove",
  "shadow-cljs.png",
  "panel.html",
  function (panel) {
    panel.onShown.addListener(function (win) {
      panelWin = win;
      panelWin.init();
    });

    panel.onHidden.addListener(function () {
      if (panelWin) {
        panelWin.stop();
        panelWin = null;
      }
    });

    chrome.devtools.network.onNavigated.addListener(
      function(url) {
        // FIXME: this fires way too early before page is loaded, before scripts actually finished loading
        // dunno how I'm supposed to wait for it finishing, too lazy to find out, so just waiting a sec

        setTimeout(function() {
          if (panelWin) {
            panelWin.init();
          }
        }, 1000); 
      }
    );
  }
);
