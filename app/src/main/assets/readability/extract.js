// Runs inside the in-app browser: extracts the article on the current page with Readability
// and returns it as a JSON string ("null" when the page has no article).
(function() {
  try {
    /*READABILITY*/
    var doc = document.cloneNode(true);
    var a = new Readability(doc, { charThreshold: 300 }).parse();
    if (!a) return "null";
    return JSON.stringify({
      title: a.title || document.title || "",
      byline: a.byline || "",
      siteName: a.siteName || "",
      content: a.content || "",
      url: location.href
    });
  } catch (e) {
    return JSON.stringify({ error: String(e) });
  }
})();
