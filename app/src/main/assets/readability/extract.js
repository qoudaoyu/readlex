// Runs inside the in-app browser: extracts the article on the current page with Readability
// and returns it as a JSON string ("null" when the page has no article).
// The body comes back as blocks so that paragraphs, subheadings and lists keep their shape.
(function() {
  try {
    /*READABILITY*/
    var doc = document.cloneNode(true);

    // Page furniture that Readability sometimes keeps: bylines, image credits, share bars,
    // related-link lists, ad slots, newsletter boxes.
    var NOISE = [
      'figure', 'figcaption', 'aside', 'nav', 'button', 'form', 'noscript', 'iframe', 'svg',
      '[aria-hidden="true"]', '[role="complementary"]',
      '[data-component*="byline"]', '[data-component*="image"]', '[data-component*="media"]',
      '[data-component*="links"]', '[data-component*="tag"]', '[data-component*="ad-"]',
      '[data-testid*="byline"]', '[data-testid*="share"]', '[data-testid*="related"]',
      '[class*="byline"]', '[class*="Byline"]', '[class*="caption"]', '[class*="Caption"]',
      '[class*="newsletter"]', '[class*="Newsletter"]', '[class*="related"]', '[class*="Related"]',
      '[class*="share-"]', '[class*="social"]', '[class*="promo"]', '[class*="Promo"]'
    ];
    NOISE.forEach(function(sel) {
      try { doc.querySelectorAll(sel).forEach(function(el) { el.remove(); }); } catch (e) {}
    });

    // Author names from page metadata; byline boxes mix names with dates, roles and buttons.
    var authors = [];
    document.querySelectorAll('meta[name="author"], meta[property="article:author"], [rel="author"]').forEach(function(el) {
      var v = (el.getAttribute('content') || el.textContent || '').replace(/\s+/g, ' ').trim();
      if (v && !/^https?:/.test(v) && authors.indexOf(v) < 0) authors.push(v);
    });

    var a = new Readability(doc, { charThreshold: 300 }).parse();
    if (!a) return "null";

    var body = new DOMParser().parseFromString(a.content || '', 'text/html').body;
    var BLOCKS = 'h1,h2,h3,h4,h5,h6,p,li,blockquote,pre,dd';
    var blocks = [];
    body.querySelectorAll(BLOCKS).forEach(function(el) {
      // Leaf blocks only: a <li> or <blockquote> that wraps <p>s is read through its <p>s.
      if (el.querySelector(BLOCKS)) return;
      var text = (el.textContent || '').replace(/\s+/g, ' ').trim();
      if (!text) return;
      var tag = el.tagName.toLowerCase();
      var type = /^h[1-6]$/.test(tag) ? 'h'
        : tag === 'li' ? 'li'
        : (tag === 'blockquote' || el.closest('blockquote')) ? 'q'
        : 'p';
      blocks.push({ type: type, text: text });
    });

    var byline = authors.slice(0, 3).join(', ') || a.byline || '';
    if (byline.length > 100) byline = '';
    return JSON.stringify({
      title: a.title || document.title || "",
      byline: byline,
      siteName: a.siteName || "",
      content: a.content || "",
      blocks: blocks,
      url: location.href
    });
  } catch (e) {
    return JSON.stringify({ error: String(e) });
  }
})();
