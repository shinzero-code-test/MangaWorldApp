/**
 * Example script plugin (TEMPLATE — not a real source, never promoted as-is).
 *
 * Companions:
 * - plugin.json (unsigned template; hash + signature are added at promote time)
 * - README.md (this folder; publish checklist for script kinds)
 *
 * Bridge API v1 globals ONLY: fetch, parse, selectText, selectAttr,
 * selectHtml, resolveUrl, log. No timers, no storage, no Java — anything else
 * is a ReferenceError inside the sandbox. Entry points return plain JSON the
 * app validates into models (wrong shapes fail the call, never the app).
 *
 * The five required entries mirror the app's JVM fixture harness
 * (ScriptRunnerTest): this exact file (modulo base URLs) passes it.
 */

function home(ctx) {
  var body = fetch(ctx.baseUrl + '/');
  var doc = parse(body, ctx.baseUrl);
  var titles = selectText(doc, 'div.card a');
  var hrefs = selectAttr(doc, 'div.card a', 'href');
  var covers = selectAttr(doc, 'div.card img', 'data-src');
  var featured = titles.map(function (t, i) {
    return { id: 'm' + i, slug: 'm' + i, title: t, coverUrl: covers[i] || '' };
  });
  return { featured: featured, latest: [], trending: featured.slice(0, 8) };
}

function detail(ctx) {
  var body = fetch(ctx.baseUrl + '/manga/' + ctx.slug + '/');
  var doc = parse(body, ctx.baseUrl);
  var title = selectText(doc, 'h1.entry-title')[0] || ctx.slug;
  var cover = selectAttr(doc, '.summary_image img', 'data-src')[0] || '';
  var chapters = selectText(doc, '.listing-chapters_wrap a').map(function (t, i) {
    return { number: i + 1, title: t };
  });
  var urls = selectAttr(doc, '.listing-chapters_wrap a', 'href');
  chapters.forEach(function (c, i) { c.url = urls[i] || ''; });
  return {
    id: ctx.slug, slug: ctx.slug, title: title, coverUrl: cover,
    description: selectText(doc, '.description-summary p')[0] || '',
    genres: selectText(doc, '.genres-content a'),
    chapters: chapters
  };
}

function pages(ctx) {
  var body = fetch(ctx.chapterUrl);
  var doc = parse(body, ctx.chapterUrl);
  var urls = selectAttr(doc, '.reading-content img', 'data-src');
  return urls.map(function (u) {
    return { url: u, headers: { Referer: ctx.chapterUrl } };
  });
}

function search(ctx) {
  var body = fetch(ctx.baseUrl + '/?s=' + encodeURIComponent(ctx.query));
  var doc = parse(body, ctx.baseUrl);
  var titles = selectText(doc, '.listupd a');
  var hrefs = selectAttr(doc, '.listupd a', 'href');
  return titles.map(function (t, i) {
    var slug = (hrefs[i] || '').split('/').filter(Boolean).pop() || ('s' + i);
    return { id: slug, slug: slug, title: t };
  });
}

function browse(ctx) {
  var url = ctx.baseUrl + '/manga/?order=update&page=' + ctx.page;
  if (ctx.genre) { url += '&genre=' + encodeURIComponent(ctx.genre); }
  var body = fetch(url);
  var doc = parse(body, ctx.baseUrl);
  var titles = selectText(doc, '.listupd a');
  var hrefs = selectAttr(doc, '.listupd a', 'href');
  return titles.map(function (t, i) {
    var slug = (hrefs[i] || '').split('/').filter(Boolean).pop() || ('b' + i);
    return { id: slug, slug: slug, title: t };
  });
}

// Optional: omit when the site has no genre index (app treats absent as []).
// function genres(ctx) {
//   var body = fetch(ctx.baseUrl + '/manga/');
//   var doc = parse(body, ctx.baseUrl);
//   return selectText(doc, '.genres-content a');
// }
