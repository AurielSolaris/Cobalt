package app.auriel.cobalt.content

/**
 * Gives Chromium's own pages (`chrome://…`) a phone-width viewport.
 *
 * They are desktop pages and declare no viewport, so on a phone Blink lays
 * them out 980px wide and shrinks the result until nothing is legible. This
 * adds `width=device-width, initial-scale=1`, before first paint, so they
 * render at a readable size. Parts with fixed desktop widths (an extension
 * card is 400px) still scroll sideways; accepted for nightly rather than
 * restyling Chromium's pages from outside.
 *
 * Run by `ChromiumSession` on `chrome://` pages only. `evaluateJavaScript` is
 * refused for anything that is not WebUI, so it cannot reach a website even
 * by mistake.
 */
internal const val WEBUI_MOBILE_SCRIPT = """(function () {
  var m = document.querySelector('meta[name="viewport"]');
  if (!m) {
    m = document.createElement('meta');
    m.name = 'viewport';
    (document.head || document.documentElement).appendChild(m);
  }
  m.content = 'width=device-width, initial-scale=1';
})();"""
