# AGENTS.md

Ground truth summary of how this repository works. Consult this file first when
discussing the repo; update it whenever a change here would make it stale.

## Purpose

Integrates the Mountaineering Association of Serbia (PSS, pss.rs) hiking trail
dataset with OpenStreetMap. Data extracted from OSM is ODbL; data scraped from
pss.rs is private and restricted to this integration purpose (see README.md).

## Data flow

Crawled/scraped and OSM-extracted data pass through a pipeline of jobs in
`src/osm_pss_integration/job/pss.clj`, run in this order:

1. `crawl-website` — scrapes pss.rs, produces per-trail
   `dataset/pss.rs/routes/<ref>.json` / `.html` / `.gpx`.
2. `prepare-pss-dataset` — merges the crawl into `dataset/pss-dataset.edn`, a
   map keyed by PSS ref (e.g. `"4-31-4"`) with `:info-path`, `:gpx-path`,
   `:title`, `:region`, `:link`, `:id`, `:uredjenost`, `:planina`, `:location`,
   `:drustvo` (owning club). This file has no length/start/end/distance
   fields — those must be derived from geometry if needed.
3. `extract-pss-ref-osm-relation-id-mapping` — matches PSS refs to OSM
   relations, writes `dataset/relation-mapping.tsv` (`pss ref` \t
   `osm relation id`, header row present). **Not chained automatically** —
   in the `uberjvm` scheduler preset (`sf_macbook.clj`) this is wired as a
   manual trigger (`pss-3-relation-mapping`), not `on-state-change` off
   `geofabrik-serbia-split`; step 4 (`pss-4-extract`) depends directly on the
   geofabrik split, not on this step completing. Must be triggered by hand
   when new/renamed PSS refs need (re)matching to OSM relations.
4. `extract-pss-osm` / `load-pss-osm` / `load-pss-extract-as-dataset` — pulls
   the matched OSM relations/ways/nodes into an in-memory dataset structure
   `{:node {id -> node} :way {id -> way} :relation {id -> relation}}` (see
   `clj-geo.osm.dataset` — **keys are singular**, `:node`/`:way`/`:relation`,
   never `:nodes`/`:ways`/`:relations`; a `:nodes` typo silently resolved to
   `nil` and `as/as-double` on `nil` silently returns `0.0` rather than
   erroring — this exact bug previously zeroed every coordinate in
   `dataset/trails/*.geojson`, fixed in `job/pss.clj` around line 1606).
5. `extract-geojson-combined-map`, `extract-geojson-per-trail` (writes
   `dataset/trails/<ref>.geojson`, one file per trail), `create-trails-image`,
   `extract-pss-stats` — various exports from the OSM dataset.
   `job/staze.clj` (see below) holds the exports that write into
   `pss-map-v1` instead.

`job/staze.clj` groups everything that reads from / writes to
`~/projects/pss-map-v1` (the live map's repo), as opposed to `job/pss.clj`
which only ever touches this repo's own `dataset/`:

- `extract-geojson-with-trails` (job `pss-5-staze-trails-geojson`) writes the
  combined trails geometry straight into the live map's
  `~/projects/pss-map-v1/dataset/trails.geojson`, overwriting it on every run
  — there's no separate new-vs-production file anymore, and the job can be
  (re)run many times over several days before anything is actually released.
  It does **not** snapshot history itself.
- `create-trail-list-html` (job `pss-6-staze-trail-list-html`) writes
  `~/projects/pss-map-v1/trail-list.html`, using
  `osm-pss-integration.job.report/id->sort-key` (public, shared with
  `job/report.clj`'s own `create-trail-list`) for sort order.
- `create-trail-list-club-html` (job `pss-6-staze-trail-list-club-html`)
  writes `~/projects/pss-map-v1/trail-list-club.html`, same data grouped by
  club (`:drustvo`) instead of mapped/unmapped tiers; each club heading gets
  an anchor (lowercase, diacritics transliterated, everything else stripped
  — same rule `pss-map-v1/index.html`'s `simplifyClubFn` uses for its
  `?club=` filter, see below) plus a `[share]` permalink and a `[map]` link
  to `index.html?club=<anchor>`.
- `prepare-nepravilnosti-geojson` (job `pss-6-staze-nepravilnosti-geojson`) —
  see `dataset/nepravilnosti.dot` in Repo layout below.
- `compare-trails` (job `pss-6-staze-compare-trails-geojson`) compares the
  current `pss-map-v1/dataset/trails.geojson` ("new") against the newest
  `trails.<date>.geojson` snapshot in `pss-map-v1/history/` ("production",
  picked dynamically by `trails-production-path`, not hardcoded — it used to
  point at a stale `trails.20240603.geojson` snapshot, tagged
  `;; todo temporary fix` in the code), and can render an HTML diff report
  (`dataset/staze-pss-rs-diff/`). Its rendering (div markers per changed
  segment, orange overlay for only the actually-changed points, GPX-sourced
  markers, multiple indices when a segment repeats in-route, combined
  MODIFIED_GEOM+MODIFIED_PROPERTIES reporting, links to pss/osm/history/
  level0/tmosme) was built up from a series of one-off requests — see
  History below.

Both `pss-6-staze-*` triggers are wired off state `["pss" "staze-trails-geojson"]`
— the state-done-node set specifically by `extract-geojson-with-trails` — not
the broader `["pss" "extract"]` state that all `pss-5-*` export jobs fan out
from; wiring off `["pss" "extract"]` would race them against `trails.geojson`
actually being (re)written.

**Releasing**: a `trails.<yyyyMMdd>.geojson` snapshot is written to
`pss-map-v1/history/` **at release/commit time**, not by the extract job —
that's the point at which `pss-map-v1/dataset/trails.geojson`'s current
state is actually promoted to "production" for future `compare-trails`
diffs. (This release step isn't automated yet.)

## History: compare-trails feature requests (all implemented)

`compare-trails`'/`job/staze.clj`'s diff rendering was built up incrementally
from a numbered backlog (originally kept in this repo's `CLAUDE.md`, merged
here since all of it now reflects shipped behavior — kept as a record of why
the function looks the way it does, not as pending work):

1. Render source-geojson and new-trail using
   `clj-geo.visualization.map/geojson-style-extended-layer` with div markers
   showing a number, 10 per trail.
2. Use GPX files for source geojson instead of
   `http://localhost:7077/route/source/`.
3. Use the middle point of each segment inside a trail (`MultiLineString`) as
   its marker — new trail only, source unchanged.
4. Add an overlay containing only the changed parts of the new trail vs.
   production, drawn in orange, at point-level precision (only actually
   different points, not whole segments).
5. Render an HTML report (`staze-pss-rs-diff/index.html`) mirroring the
   console output, with a link to each `<ref>.html` diff (new tab) and to
   `http://localhost:7077/route/edit/<id>`; report ref, id, name, and what
   changed.
6. Add links to pss (the relation's `website` tag), osm
   (`https://osm.org/relation/<id>`), and history
   (`http://localhost:7077/view/osm/history/relation/<id>`), all opening in a
   new tab.
7. Show more than one index for a new-trail marker when a segment repeats
   inside the route — new trail only.
8. Report when both `MODIFIED_GEOM` and `MODIFIED_PROPERTIES` occur on the
   same trail.

## Repo layout

- `dataset/pss-dataset.edn` — master trail metadata map, keyed by PSS ref.
- `dataset/pss.rs/routes/` — raw per-trail scrape output (json/html/gpx).
- `dataset/trails/<ref>.geojson` — per-trail geometry, one file per ref.
  (Combined geometry for all trails is no longer kept in this repo — see
  `~/projects/pss-map-v1/dataset/trails.geojson` in Data flow above.)
- `dataset/relation-mapping.tsv` — PSS ref → OSM relation id.
- `dataset/klubovi/` — per-club HTML maps and markdown reports (see below).
- `dataset/maps/`, `dataset/staze-pss-rs-diff/` — other rendered outputs.
- `dataset/registar.md` — notemd-format notes, one per trail, first tag in
  each note's header is `#<ref>`. Read by `job/pss.clj`'s `load-note-map`
  (parsed via `clj-common.notemd`) to back the `note-map` lookup used in
  `extract-pss-stats` reports (TSV/wiki/HTML) — this replaced an inline
  `note-map` def, see Conventions below for required sort order.
- `dataset/wiki-status.md`, `dataset/nepravilnosti.dot`,
  `dataset/osm-notes.dot` — manually curated notes fed into map overlays.
  `nepravilnosti.dot` ( humandot format, see `clj-geo.dot.store.humandot` ) is
  editable via [dote](https://github.com/vanjakom/dote), a browser humandot
  editor:
  - locally, writable, through `uberjvm.desktop.dote`'s server (served at
    `http://localhost:7078/dote/...`, registered in
    `~/dataset-git/dotrepo.json` under namespace `pss` id `nepravilnosti`):
    `http://localhost:7078/dote/editor?repository=http://localhost:7078/dote/&url=http://localhost:7078/dote/data/pss/nepravilnosti.dot&writable=true`
  - publicly, read-only, off GitHub Pages (no write route there, so no
    `writable=true`):
    `https://vanjakom.github.io/dote/index.html?url=https://vanjakom.github.io/osm-pss-integration/dataset/nepravilnosti.dot`
  `job/staze.clj`'s `prepare-nepravilnosti-geojson` (job
  `pss-6-staze-nepravilnosti-geojson`) converts it to
  `pss-map-v1/dataset/nepravilnosti.geojson` for the map's "Неправилности"
  layer.
- `src/osm_pss_integration/job/pss.clj` — main extraction/export pipeline,
  only ever touches this repo's own `dataset/` (see Data flow above).
- `src/osm_pss_integration/job/staze.clj` — everything that reads from /
  writes to `~/projects/pss-map-v1` instead: publishing `trails.geojson` and
  `trail-list.html`, and the new-vs-production comparison / HTML diff report
  (see Data flow above).
- `src/osm_pss_integration/job/report.clj` — `create-trail-list` (writes
  this repo's own `dataset/trail-lst.md`) plus the shared, public
  `id->category`/`id->numeric-key`/`id->sort-key` sort-order helpers reused
  by `job/staze.clj`'s `create-trail-list-html`; also `create-trail-planine`
  (**not** scheduler-wired, call manually), writes `dataset/trail-planine.md`
  — groups `pss-dataset.edn` trails by the first two dash-separated segments
  of their ref (`"5-11-4"` -> `"5-11"`, same prefix `id->numeric-key` sorts
  by), skipping transversals/E-paths (they legitimately span multiple
  `:planina` by nature), and writes one tsv row per prefix (`<prefix>\t
  <planina>`, ` / `-joined when a prefix has more than one distinct
  `:planina` value), followed by a plain text `Inconsistencies:` section
  listing exactly those multi-value prefixes — for a normal (non-
  transversal) prefix more than one `:planina` value is a real data
  inconsistency, not an expected multi-mountain route; also `create-map` (job
  `pss-5-nepravilnosti-map`), misc map rendering merged in from the former
  `job/map.clj`, renders `dataset/maps/nepravilnosti.html` from
  `nepravilnosti.dot`/`osm-notes.dot`, passing `serbia-view-configuration`
  (`:center-longitude`/`:center-latitude`/`:center-zoom`) to
  `clj-geo.visualization.map/render-raw` so the map opens framed on all of
  Serbia instead of `render-raw`'s hardcoded Belgrade/zoom-10 fallback;
  also `create-registar-html` (job `pss-5-registar-html`), renders
  `dataset/registar.html` from `registar.md` via
  `clj-common.notemd/notes->html` (one styled, self contained page, one
  `<div class="note">` per `#notemd` entry, in file order); each note's
  header line doubles the ref as plain text right after the `#<ref>` tag
  (`# #2-16-5 2-16-5: Vera`) so the rendered `<h1>` title keeps the ref
  prefix — `notemd`'s tag-stripping only removes `#`/`@`-prefixed tokens,
  a plain-text duplicate survives; also `create-html-from-markdown` (job
  `pss-5-index-html`, generic — `:md-path`/`:html-path` configuration),
  reuses `notemd/read-notes` + `notes->html` for any plain `# Heading` +
  paragraph file, not just actual `#notemd` docs — `read-notes` doesn't
  require the `[notemd]`/tag conventions, anything before the first `"# "`
  line is just an ignorable preamble. Used to turn `README.md` into
  `index.html`. Inherits notemd's one real limitation: only single `"# "`
  lines are section boundaries, `"##"`/`"###"` etc render as literal
  paragraph text, not sub-headings.
- `src/osm_pss_integration/job/history.clj` — OSM relation history debugging.
  `debug-relation-history` (called from `repl.clj`, not scheduler-wired)
  reads `~/dataset-local/geofabrik-serbia-history/serbia-internal.osh.pbf`
  three times in sequence (relations, then ways, then nodes — required
  because `osm/relation-histset-go` drains those three channels strictly in
  that order in one `go` block, so pushing all three from a single pbf pass
  would deadlock on an unbuffered channel no one's reading yet), builds a
  histset via `osm/relation-histset-go` + `pipeline/wait-pipeline-output`,
  writes it to `~/dataset-local/osm-pss-debug/<relation-id>.histset`, then
  renders one colored layer per geometry version (`map/geojson-layer`, which
  reads `*style-stroke-color*` at render time — unlike
  `geojson-style-extended-layer`/`geojson-style-layer`, see below) to
  `<relation-id>.html` in the same dir, plus the matching PSS gpx track if
  the relation carries a `ref` tag with a gpx file on disk. The rest of the
  file below a `(throw ...)` guard is `#_`-commented ad-hoc debug snippets —
  add new top-level code above that throw or it won't load.
  `debug-relation-history-job` wraps it as a plain `clj-common.context`
  job-fn (`context/configuration` / `context/trace`) — `job/history.clj`
  deliberately has no `clj-scheduler` dependency; submitting it as a
  scheduler job is `repl.clj`'s `submit-relation-history-job`'s job, which
  does depend on `clj-scheduler.core`.
- `src/osm_pss_integration/job/club/*.clj` — one file per club (e.g.
  `suncevica.clj`, `vrsackakula.clj`, `ostracuka.clj`): standalone scripts
  (no `-main`, no scheduler `context`) that, on namespace load, filter
  `pss-dataset.edn` by `:drustvo`, render a Leaflet HTML map to
  `dataset/klubovi/<name>.html`, and (for `ostracuka.clj`) also write a
  markdown report to `dataset/klubovi/<name>.md`. Copy this pattern for new
  clubs rather than inventing a new structure.
- `src/osm_pss_integration/repl.clj` — scratch REPL helpers.

## How jobs run

There is no `-main`. Two execution modes:

- **`job/club/*.clj` scripts**: side-effecting at namespace load time (wrapped
  in top-level `with-open`). Run by loading/requiring the namespace (e.g. in a
  REPL) — evaluating the file *is* running the job.
- **`job/pss.clj`, `job/staze.clj`, `job/report.clj` functions**: designed to be
  invoked as `clj-scheduler` jobs/triggers, wired up externally in
  `uberjvm`'s `src/uberjvm/preset/sf_macbook.clj` (a separate project). Each
  trigger there wraps its job function in `(var ns/fn)` rather than a bare
  symbol — required so redefining the function and re-evaluating it takes
  effect without restarting the scheduler.

## Dependencies

`project.clj` depends on released `com.mungolab/clj-common` and
`com.mungolab/clj-geo`, but `checkouts/clj-common` and `checkouts/clj-geo`
symlink to local sibling checkouts (`~/projects/clj-common`,
`~/projects/clj-geo`) so lein picks up local source changes to those shared
libs during development.

## Conventions / gotchas

- Trail ids/refs look like `"4-31-4"` (region-mountain-number) or `"E7-6"` /
  `"T-1-3"` (E-roads / transversals). Use `pss/id-compare` (`job/pss.clj`,
  ~line 102) to sort by ref — plain string sort misorders numeric segments
  (e.g. `"4-4-1"` would sort after `"4-27-1"`).
- `:drustvo` in `pss-dataset.edn` holds the owning club name, e.g.
  `"Oštra čuka PD"` — used to select a club's trail subset.
- `dataset/klubovi/*.html` naming has no strict rule yet (`psdvrsackakula.html`,
  `pskbalkan.html`, `supsdsuncevica.html`, `ostracuka.html`) — pick something
  readable derived from the club name.
- `dataset/registar.md` note order: normal trails first (sorted by ref, e.g.
  `region-mountain-number`), then transversals (`T-*`), then E-paths (`E*`)
  last. Within each group, sort numerically by the ref's dash-separated
  segments (not lexicographically — `"4-4-1"` sorts before `"4-27-1"`).
  Re-sort after adding/editing notes.
- `clj-geo.visualization.map/geojson-style-extended-layer` only styles Point
  features (custom markers); it ignores `stroke`/`stroke-width` properties on
  Line/MultiLine features because it sets `useSimpleStyle: false`. Use
  `geojson-style-layer` (`useSimpleStyle: true`) instead when coloring lines,
  and either bind `clj-geo.import.geojson/*style-stroke-color*` around eager
  (`doall`'d) construction via `geojson/line-string`, or merge a literal
  `"stroke"` key into pre-built feature properties — a lazy seq built inside
  a `binding` block reverts to the default color once the binding scope
  exits before the seq is forced.
