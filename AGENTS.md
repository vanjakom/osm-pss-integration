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
   `osm relation id`, header row present).
4. `extract-pss-osm` / `load-pss-osm` / `load-pss-extract-as-dataset` — pulls
   the matched OSM relations/ways/nodes into an in-memory dataset structure
   `{:node {id -> node} :way {id -> way} :relation {id -> relation}}` (see
   `clj-geo.osm.dataset` — **keys are singular**, `:node`/`:way`/`:relation`,
   never `:nodes`/`:ways`/`:relations`; a `:nodes` typo silently resolved to
   `nil` and `as/as-double` on `nil` silently returns `0.0` rather than
   erroring — this exact bug previously zeroed every coordinate in
   `dataset/trails/*.geojson`, fixed in `job/pss.clj` around line 1606).
5. `extract-geojson-combined-map`, `extract-geojson-per-trail` (writes
   `dataset/trails/<ref>.geojson`, one file per trail), `extract-geojson-with-trails`
   (writes combined `dataset/trails.geojson`), `create-trails-image`,
   `extract-pss-stats` — various exports from the OSM dataset.

`job/qa.clj` (`compare-trails`) compares the freshly extracted
`dataset/trails.geojson` ("new") against `~/projects/pss-map-v1/dataset/trails.geojson`
("production") to catch regressions before publishing, and can render an HTML
diff report (`dataset/staze-pss-rs-diff/`).

## Repo layout

- `dataset/pss-dataset.edn` — master trail metadata map, keyed by PSS ref.
- `dataset/pss.rs/routes/` — raw per-trail scrape output (json/html/gpx).
- `dataset/trails/<ref>.geojson` — per-trail geometry, one file per ref.
- `dataset/trails.geojson` — combined geometry for all trails.
- `dataset/relation-mapping.tsv` — PSS ref → OSM relation id.
- `dataset/klubovi/` — per-club HTML maps and markdown reports (see below).
- `dataset/maps/`, `dataset/staze-pss-rs-diff/` — other rendered outputs.
- `dataset/registar.md`, `dataset/wiki-status.md`, `dataset/nepravilnosti.dot`,
  `dataset/osm-notes.dot` — manually curated notes fed into map overlays.
- `src/osm_pss_integration/job/pss.clj` — main extraction/export pipeline
  (see Data flow above).
- `src/osm_pss_integration/job/qa.clj` — comparison of new vs. production
  trail data, HTML diff report generation.
- `src/osm_pss_integration/job/map.clj`, `job/history.clj` — misc map/history
  rendering helpers.
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
- **`job/pss.clj`, `job/qa.clj`, `job/map.clj` functions**: designed to be
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
- `clj-geo.visualization.map/geojson-style-extended-layer` only styles Point
  features (custom markers); it ignores `stroke`/`stroke-width` properties on
  Line/MultiLine features because it sets `useSimpleStyle: false`. Use
  `geojson-style-layer` (`useSimpleStyle: true`) instead when coloring lines,
  and either bind `clj-geo.import.geojson/*style-stroke-color*` around eager
  (`doall`'d) construction via `geojson/line-string`, or merge a literal
  `"stroke"` key into pre-built feature properties — a lazy seq built inside
  a `binding` block reverts to the default color once the binding scope
  exits before the seq is forced.
