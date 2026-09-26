/**
 * manonga.online — official script canary v1 (bridge API v1).
 *
 * Pipeline-proving source: small, server-rendered, no Cloudflare, no auth.
 * Custom theme (fixed engine walks cannot express its routes), so a script is
 * genuinely required — not just convenient.
 *
 * Bridge globals ONLY (fetch, parse, selectText, selectAttr, selectHtml,
 * resolveUrl, log). Entry points
 * return plain JSON the app validates into models; wrong shapes fail the call.
 *
 * Audited 2026-09-26 (see AUDIT.md):
 * - home: `.manga-item` cards (heading link + chapter link + cover + time)
 * - browse: `.col-sm-4` cards, `?page=N`, `?cat=<id>` (table below)
 * - detail: `/manga/<slug>` (title tag, h3 meta, img.img-responsive, ul.chapters)
 * - pages: bare `img[src*="onma.top/uploads"]` (trimmed, deduped)
 * - search: GET /search?query= → {"suggestions":[{"value","data"}]}
 * Hosts: manonga.online (+ images), www.onma.top (images/covers) — literal
 * allow-list, no wildcards (plan v1 rule).
 */

var GENRE_CATS = {
  "أكشن": "1", "مغامرة": "2", "كوميدي": "3", "شياطين": "4", "دراما": "5",
  "إيتشي": "6", "خيال": "7", "انحراف جنسي": "8", "حريم": "9", "تاريخي": "10",
  "رعب": "11", "جوسي": "12", "فنون قتالية": "13", "ناضج": "14", "ميكا": "15",
  "غموض": "16", "وان شوت": "17", "نفسي": "18", "رومنسي": "19",
  "حياة مدرسية": "20", "خيال علمي": "21", "سينين": "22", "شوجو": "23",
  "شوجو أي": "24", "شونين": "25", "شونين أي": "26", "شريحة من الحياة": "27",
  "رياضة": "28", "خارق للطبيعة": "29", "مأساة": "30", "مصاصي الدماء": "31",
  "سحر": "32", "ويب تون": "35", "دوجينشي": "36"
};

function lastSegment(href) {
  var parts = (href || "").split("?")[0].split("/").filter(function (p) { return !!p; });
  return parts.length ? parts[parts.length - 1] : "";
}

function firstNum(text) {
  var m = /[0-9]+(?:\.[0-9]+)?/.exec(text || "");
  return m ? Number(m[0]) : NaN;
}

function trim(s) { return (s || "").replace(/^\s+|\s+$/g, ""); }

/** Lazy-image rule (mirrors the native engines): data-src first, src after. */
function pickSrc(doc, selector, index) {
  var ds = selectAttr(doc, selector, "data-src");
  var ss = selectAttr(doc, selector, "src");
  var raw = trim(ds[index] || "") || trim(ss[index] || "");
  if (/^data:/i.test(raw)) { return ""; }
  return resolveUrl(doc, raw);
}

function home(ctx) {
  var doc = parse(fetch(ctx.baseUrl + "/"), ctx.baseUrl);
  var titles = selectText(doc, ".manga-item .manga-heading a");
  var hrefs = selectAttr(doc, ".manga-item .manga-heading a", "href");
  var chTexts = selectText(doc, ".manga-item .manga-chapter a");
  var chHrefs = selectAttr(doc, ".manga-item .manga-chapter a", "href");
  var bySlug = {};
  var featured = [];
  for (var i = 0; i < titles.length; i++) {
    var slug = lastSegment(resolveUrl(doc, hrefs[i] || ""));
    var title = trim(titles[i]);
    if (!slug || !title) { continue; }
    bySlug[slug] = title;
    featured.push({
      id: slug, slug: slug, title: title,
      coverUrl: pickSrc(doc, ".manga-item .right-image img", i)
    });
  }
  var latest = [];
  for (var j = 0; j < chTexts.length; j++) {
    var n = firstNum(chTexts[j]);
    var curl = resolveUrl(doc, chHrefs[j] || "");
    var segs = curl.split("?")[0].split("/").filter(Boolean);
    var mslug = segs.length >= 2 ? segs[segs.length - 2] : "";
    if (!isFinite(n) || !mslug || !curl) { continue; }
    latest.push({
      mangaSlug: mslug,
      mangaTitle: bySlug[mslug] || mslug,
      chapterNumber: n,
      chapterUrl: curl
    });
  }
  return { featured: featured, latest: latest, trending: featured.slice(0, 8) };
}

function browse(ctx) {
  var page = ctx.page && ctx.page > 0 ? ctx.page : 1;
  var url = ctx.baseUrl + "/manga-list?page=" + page;
  if (ctx.genre && GENRE_CATS[ctx.genre]) {
    url += "&cat=" + GENRE_CATS[ctx.genre];
  } else if (ctx.genre) {
    log("info", "unknown genre, unfiltered: " + ctx.genre);
  }
  var doc = parse(fetch(url), ctx.baseUrl);
  var titles = selectText(doc, ".col-sm-4 .media-heading a");
  var hrefs = selectAttr(doc, ".col-sm-4 .media-heading a", "href");
  var coverSel = ".col-sm-4 .chapter-image img";
  var out = [];
  for (var i = 0; i < titles.length; i++) {
    var slug = lastSegment(resolveUrl(doc, hrefs[i] || ""));
    var title = trim(titles[i]);
    if (!slug || !title) { continue; }
    out.push({ id: slug, slug: slug, title: title, coverUrl: pickSrc(doc, coverSel, i) });
  }
  return out;
}

function search(ctx) {
  var res = JSON.parse(fetch(ctx.baseUrl + "/search?query=" + encodeURIComponent(ctx.query || "")));
  var sug = (res && res.suggestions) || [];
  if (!Array.isArray(sug)) { throw new Error("bad search shape"); }
  var out = [];
  for (var i = 0; i < sug.length; i++) {
    var title = sug[i] && sug[i].value;
    var slug = sug[i] && sug[i].data;
    if (typeof title !== "string" || !trim(title)) { continue; }
    if (typeof slug !== "string" || !trim(slug)) { continue; }
    out.push({ id: trim(slug), slug: trim(slug), title: trim(title) });
  }
  return out;
}

function h3Value(lines, label) {
  for (var i = 0; i < lines.length; i++) {
    var t = trim(lines[i]);
    if (t.indexOf(label) === 0) { return trim(t.slice(label.length)); }
  }
  return "";
}

function detail(ctx) {
  var doc = parse(fetch(ctx.baseUrl + "/manga/" + ctx.slug + "/"), ctx.baseUrl);
  var rawTitle = selectText(doc, "title")[0] || "";
  var title = trim(rawTitle.replace(/^مانجا\s+/, "").replace(/\s*مترجمة\s*\|\s*مانجا اون لاين\s*$/, ""));
  if (!title) { throw new Error("no title"); }
  var coverImgs = selectAttr(doc, "img.img-responsive", "src");
  var cover = "";
  for (var c = 0; c < coverImgs.length; c++) {
    var u = resolveUrl(doc, coverImgs[c]);
    if (u.indexOf("/uploads/manga/") !== -1) { cover = u; break; }
  }
  var h3s = selectText(doc, "h3");
  var author = h3Value(h3s, "المؤلف :");
  var artist = h3Value(h3s, "الرسام :");
  var status = h3Value(h3s, "الحالة :");
  var genres = h3Value(h3s, "التصنيفات :").split(",").map(trim).filter(Boolean);
  var typeRaw = h3Value(h3s, "النوع :");
  var type = /صينية|manhua/i.test(typeRaw) ? "manhua"
    : /كورية|manhwa/i.test(typeRaw) ? "manhwa"
    : /مانجا|manga/i.test(typeRaw) ? "manga" : typeRaw;
  var views = (h3Value(h3s, "الزيارة :").match(/[0-9,]+/) || [""])[0].replace(/,/g, "");
  var chTexts = selectText(doc, "ul.chapters li h5 a");
  var chHrefs = selectAttr(doc, "ul.chapters li h5 a", "href");
  var chapters = [];
  for (var i = 0; i < chTexts.length; i++) {
    var n = firstNum(chTexts[i]);
    var url = resolveUrl(doc, chHrefs[i] || "");
    if (!isFinite(n) || !url) { continue; }
    chapters.push({ number: n, title: trim(chTexts[i]), url: url });
  }
  return {
    id: ctx.slug, slug: ctx.slug, title: title, coverUrl: cover,
    authorName: author, artistName: artist, status: status, type: type,
    genres: genres, views: views, chapters: chapters
  };
}

function pages(ctx) {
  var doc = parse(fetch(ctx.chapterUrl), ctx.chapterUrl);
  var dataSrcs = selectAttr(doc, "img", "data-src");
  var srcs = selectAttr(doc, "img", "src");
  var n = Math.max(dataSrcs.length, srcs.length);
  var seen = {};
  var out = [];
  for (var i = 0; i < n; i++) {
    var raw = trim(dataSrcs[i] || "") || trim(srcs[i] || "");
    if (/^data:/i.test(raw)) { continue; }
    var u = resolveUrl(doc, raw);
    if (u.indexOf("/uploads/manga/") === -1 || seen[u]) { continue; }
    seen[u] = true;
    out.push({ url: u, headers: { Referer: ctx.chapterUrl } });
  }
  return out;
}

function genres(ctx) {
  var doc = parse(fetch(ctx.baseUrl + "/manga-list"), ctx.baseUrl);
  var names = selectText(doc, 'a[href*="cat="]');
  var seen = {};
  var out = [];
  for (var i = 0; i < names.length; i++) {
    var t = trim(names[i]);
    if (!t || seen[t]) { continue; }
    seen[t] = true;
    out.push(t);
  }
  return out;
}
