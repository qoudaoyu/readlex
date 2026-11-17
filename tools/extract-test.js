// Runs the app's exact extraction script in real Chromium against the test fixtures and
// writes what the WebView would hand to Kotlin next to them (*.extracted.json).
// Usage: node tools/extract-test.js <repo root>   (needs the playwright package)
const { chromium } = require('playwright');
const fs = require('fs');
const path = require('path');
(async () => {
  const root = process.argv[2];
  const assets = path.join(root, 'app/src/main/assets/readability');
  const script = fs.readFileSync(path.join(assets, 'extract.js'), 'utf8')
    .replace('/*READABILITY*/', fs.readFileSync(path.join(assets, 'Readability.js'), 'utf8'));
  const browser = await chromium.launch();
  const page = await browser.newPage();
  for (const name of ['article-page.html', 'front-page.html', 'bbc-like.html']) {
    await page.goto('file://' + path.join(root, 'app/src/test/resources', name));
    const out = await page.evaluate(script);
    // evaluateJavascript hands Kotlin the JSON encoding of the returned value.
    fs.writeFileSync(path.join(root, 'app/src/test/resources', name.replace('.html', '.extracted.json')), JSON.stringify(out));
    console.log(name, out === 'null' ? 'null' : JSON.parse(out).title, out.length);
  }
  await browser.close();
})();
