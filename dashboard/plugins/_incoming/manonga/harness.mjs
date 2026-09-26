/**
 * Shape test for the manonga canary source.js (human publish gate).
 *
 * Strategy (documented in the publish checklist): selectors are validated
 * against captured HTML with BeautifulSoup (same selector strings, same
 * documents — see the audit transcript); THIS harness executes the REAL entry
 * functions in Node with stubbed bridge values to prove control flow, key
 * names, and JSON shapes — the failure class BeautifulSoup cannot see
 * (a `cover` instead of `coverUrl` passes selection but dies in app validation).
 *
 * Run: node harness.mjs
 */
import { readFileSync } from "node:fs";
import vm from "node:vm";
import assert from "node:assert/strict";

const SRC = readFileSync(new URL("./source.js", import.meta.url), "utf8");

// Hand-fed bridge values (representative, from the captured fixtures).
const FIX = {
  homeTitles: ["One Piece"],
  homeHrefs: ["https://manonga.online/manga/one-piece-"],
  homeCovers: ["/uploads/manga/one-piece-/cover/cover_250x350.jpg"],
  homeChTexts: ["#1194. كل شيء يتغير"],
  homeChHrefs: ["https://manonga.online/manga/one-piece-/1194"],
  browseTitles: ["2 jikanme Hoken Taiiku"],
  browseHrefs: ["https://manonga.online/manga/2-jikanme-hoken-taiiku"],
  browseCovers: ["https://www.onma.top/uploads/manga/2-jikanme-hoken-taiiku/cover/cover_250x350.jpg"],
  detailTitle: ["مانجا A Test مترجمة | مانجا اون لاين"],
  detailCover: ["https://www.onma.top/uploads/manga/a/cover/cover_250x350.jpg"],
  detailH3: ["النوع :مانها صينية", "المؤلف :Foo", "الرسام :Bar", "الحالة :مستمرة",
    "التصنيفات :كوميدي,رومنسي", "الزيارة :12345"],
  detailChTexts: ["44 : فصل", "43 : فصل"],
  detailChHrefs: ["https://manonga.online/manga/a/44", "https://manonga.online/manga/a/43"],
  pageSrcs: ["data:image/gif;base64,xx", " https://www.onma.top/uploads/manga/a/chapters/44/01.png "],
  pageDataSrcs: [" https://www.onma.top/uploads/manga/a/chapters/44/01.png ", ""],
  genres: ["أكشن", "أكشن", "دراما"],
  searchBody: JSON.stringify({ suggestions: [{ value: "Naruto", data: "naruto_" }] }),
};

const BASE = "https://manonga.online";
const abs = (h) => new URL(h, BASE).toString();

const sandbox = {
  fetch: (url) => {
    if (url === BASE + "/") return "<home>";
    if (url === BASE + "/manga-list?page=1") return "<browse>";
    if (url === BASE + "/manga-list?page=1&cat=1") return "<browse-cat>";
    if (url === BASE + "/search?query=" + encodeURIComponent("naruto")) return FIX.searchBody;
    if (url === BASE + "/manga/a/") return "<detail>";
    if (url === BASE + "/manga-list") return "<genres>";
    if (url === "https://manonga.online/manga/a-dimwitted-monk-fell-from-heaven/44") return "<pages>";
    throw new Error("unexpected fetch: " + url);
  },
  parse: () => 1,
  selectText: (doc, sel) => {
    if (sel === ".manga-item .manga-heading a") return FIX.homeTitles;
    if (sel === ".manga-item .manga-chapter a") return FIX.homeChTexts;
    if (sel === ".col-sm-4 .media-heading a") return FIX.browseTitles;
    if (sel === "title") return FIX.detailTitle;
    if (sel === "h3") return FIX.detailH3;
    if (sel === "ul.chapters li h5 a") return FIX.detailChTexts;
    if (sel === 'a[href*="cat="]') return FIX.genres;
    throw new Error("unexpected selectText: " + sel);
  },
  selectAttr: (doc, sel, attr) => {
    if (sel === ".manga-item .manga-heading a") return FIX.homeHrefs;
    if (sel === ".manga-item .right-image img") return attr === "data-src" ? [""] : FIX.homeCovers;
    if (sel === ".manga-item .manga-chapter a") return FIX.homeChHrefs;
    if (sel === ".col-sm-4 .media-heading a") return FIX.browseHrefs;
    if (sel === ".col-sm-4 .chapter-image img") return attr === "data-src" ? [""] : FIX.browseCovers;
    if (sel === "img.img-responsive") return FIX.detailCover;
    if (sel === "ul.chapters li h5 a") return FIX.detailChHrefs;
    if (sel === "img" && attr === "data-src") return FIX.pageDataSrcs;
    if (sel === "img" && attr === "src") return FIX.pageSrcs;
    throw new Error(`unexpected selectAttr: ${sel} ${attr}`);
  },
  selectHtml: () => { throw new Error("unused"); },
  resolveUrl: (doc, href) => {
    const t = (href || "").trim();
    if (!t) return "";
    if (/^(data|javascript|mailto|blob):/i.test(t)) return t;
    return abs(t);
  },
  log: () => {},
  encodeURIComponent,
};
vm.createContext(sandbox);
vm.runInContext(SRC + "\nthis.__entries = { home, detail, pages, search, browse, genres };", sandbox);
const E = sandbox.__entries;

// home
{
  const r = E.home({ baseUrl: BASE });
  assert.equal(r.featured.length, 1);
  assert.equal(r.featured[0].slug, "one-piece-");
  assert.equal(r.featured[0].title, "One Piece");
  assert.equal(r.featured[0].coverUrl, BASE + "/uploads/manga/one-piece-/cover/cover_250x350.jpg");
  assert.equal(r.latest.length, 1);
  assert.equal(r.latest[0].mangaSlug, "one-piece-");
  assert.equal(r.latest[0].mangaTitle, "One Piece");
  assert.equal(r.latest[0].chapterNumber, 1194);
  assert.equal(r.latest[0].chapterUrl, BASE + "/manga/one-piece-/1194");
  assert.equal(r.trending.length, 1);
}
// browse (+genre mapping to cat id)
{
  const r = E.browse({ baseUrl: BASE, page: 1 });
  assert.equal(r.length, 1);
  assert.equal(r[0].slug, "2-jikanme-hoken-taiiku");
  assert.ok(r[0].coverUrl.startsWith("https://www.onma.top/"));
  const rg = E.browse({ baseUrl: BASE, page: 1, genre: "أكشن" });
  assert.equal(rg.length, 1); // cat=1 branch exercised (stubbed same body)
}
// search
{
  const r = E.search({ baseUrl: BASE, query: "naruto", page: 1 });
  assert.deepEqual(JSON.parse(JSON.stringify(r)), [{ id: "naruto_", slug: "naruto_", title: "Naruto" }]);
}
// detail
{
  const r = E.detail({ baseUrl: BASE, slug: "a" });
  assert.equal(r.title, "A Test");
  assert.equal(r.authorName, "Foo");
  assert.equal(r.artistName, "Bar");
  assert.equal(r.status, "مستمرة");
  assert.equal(r.type, "manhua");
  assert.deepEqual(JSON.parse(JSON.stringify(r.genres)), ["كوميدي", "رومنسي"]);
  assert.equal(r.views, "12345");
  assert.equal(r.coverUrl, "https://www.onma.top/uploads/manga/a/cover/cover_250x350.jpg");
  assert.equal(r.chapters.length, 2);
  assert.equal(r.chapters[0].number, 44);
  assert.equal(r.chapters[0].url, BASE + "/manga/a/44");
}
// pages (data-src preference + trim + dedupe)
{
  const r = E.pages({ baseUrl: BASE, chapterUrl: "https://manonga.online/manga/a-dimwitted-monk-fell-from-heaven/44" });
  assert.equal(r.length, 1);
  assert.equal(r[0].url, "https://www.onma.top/uploads/manga/a/chapters/44/01.png");
  assert.equal(r[0].headers.Referer, "https://manonga.online/manga/a-dimwitted-monk-fell-from-heaven/44");
}
// genres (dedupe)
{
  const r = E.genres({ baseUrl: BASE });
  assert.deepEqual(JSON.parse(JSON.stringify(r)), ["أكشن", "دراما"]);
}
console.log("canary harness: all entry shapes OK");
