(ns osm-pss-integration.job.staze
  "Jobs that read from / write to ~/projects/pss-map-v1 (the live map's
  repo) - producing and publishing its trails.geojson / trail-list.html,
  and comparing the current extract against the last released snapshot."
  (:use
   clj-common.clojure)
  (:require
   [clojure.string :as string]

   [hiccup.core :as hiccup]

   [clj-common.as :as as]
   [clj-common.context :as context]
   [clj-common.edn :as edn]
   [clj-common.io :as io]
   [clj-common.json :as json]
   [clj-common.localfs :as fs]
   [clj-common.path :as path]
   [clj-common.pipeline :as pipeline]

   [clj-geo.dot.store.humandot :as humandot]
   [clj-geo.import.geojson :as geojson]
   [clj-geo.import.gpx :as gpx]
   [clj-geo.math.core :as math]
   [clj-geo.visualization.map :as map]

   [clj-scheduler.core :as core]

   [osm-pss-integration.job.pss :as pss]
   [osm-pss-integration.job.report :as report]))

(def pss-map-v1-path ["Users" "vanja" "projects" "pss-map-v1"])

(def trails-new-path (path/child pss-map-v1-path "dataset" "trails.geojson"))

(def trails-history-path (path/child pss-map-v1-path "history"))

(defn trails-production-path
  "Picks newest trails.<date>.geojson snapshot from pss-map-v1/history, this
  is the production state saved right before trails.geojson was last
  (re)written by extract-geojson-with-trails"
  []
  (last
   (filter
    #(re-matches #"trails\.\d+\.geojson" (path/name %))
    (fs/list trails-history-path))))

(def relation-mapping-path ["Users" "vanja" "projects" "osm-pss-integration"
                            "dataset" "relation-mapping.tsv"])
(def gpx-root-path ["Users" "vanja" "projects" "osm-pss-integration" "dataset"
                    "pss.rs" "routes"])

;; todo reset on each iteration, put pss ref in
(def ignore
  #{})

(defn extract-geojson-with-trails
  "Creates GeoJSON containing each trail as single feature for each trail, containing all trail geometry. If trail is
  not complete multiple features will be extracted. Writes directly to
  pss-map-v1/dataset/trails.geojson, publishing on every run."
  [job-context]
  (let [configuration (core/context-configuration job-context)
        context (core/context-pipeline-adapter job-context)
        channel-provider (pipeline/create-channels-provider)
        resource-controller (pipeline/create-trace-resource-controller context)
        pss-map-v1-path (:pss-map-v1-path configuration)
        osm-pss-extract-path (:osm-pss-extract-path configuration)
        timestamp (System/currentTimeMillis)
        trails-path (path/child pss-map-v1-path "dataset" "trails.geojson")]
    (let [dataset (pss/load-pss-extract-as-dataset job-context)]
      (core/context-report
       job-context
       "dataset loaded, writing trails")
      (with-open [os (fs/output-stream trails-path)]
        (json/write-pretty-print
         (geojson/geojson
          (doall
           (map
            (fn [relation]
              (let [ref (get-in relation [:tags "ref"])]
                (core/context-report
                 job-context
                 (str "processing: " ref " (" (get relation :id) ")"))
                (binding [geojson/*style-stroke-color* "#FF0000"
                          geojson/*style-stroke-width* (cond
                                                         (.startsWith ref "E")
                                                         6
                                                         (.startsWith ref "T")
                                                         4
                                                         :else
                                                         2)]
                  (geojson/multi-line-string
                   {
                    "ref" ref
                    "osm-relation-id" (get relation :id)
                    "name" (get-in relation [:tags "name"])
                    "operator" (get-in relation [:tags "operator"])
                    "website" (get-in relation [:tags "website"])
                    "network" (get-in relation [:tags "network"])
                    "distance" (get-in relation [:tags "distance"])}
                   (filter
                    some?
                    (map
                     (fn [member]
                       (cond
                         (= (:type member) :way)
                         (map
                          (fn [id]
                            (get-in dataset [:node id]))
                          (:nodes (get-in dataset [:way (:id member)])))
                         :else
                         nil))
                     (:members relation)))))))
            (vals (:relation dataset)))))
         (io/output-stream->writer os))))))

(defn create-trail-list-html
  "Reads pss-dataset.edn and trails.geojson and writes trail-list.html, a plain
  list of trails split into three sections: mapped ( present in
  trails.geojson - id, club, name, links to the live map and to pss.rs ),
  not mapped but with a gpx file on disk ( id, club, name, link to pss.rs ),
  and not mapped without a gpx file"
  [job-context]
  (let [configuration (context/configuration job-context)
        osm-pss-integration-path (:osm-pss-integration-path configuration)
        pss-map-v1-path (:pss-map-v1-path configuration)
        trail-seq (with-open [is (fs/input-stream
                                   (path/child osm-pss-integration-path "pss-dataset.edn"))]
                    (vals (edn/read is)))
        mapped-refs (with-open [is (fs/input-stream
                                     (path/child pss-map-v1-path "dataset" "trails.geojson"))]
                      (into
                       #{}
                       (map #(get-in % [:properties :ref]) (:features (json/read-keyworded is)))))
        has-gpx? (fn [trail] (and (some? (:gpx-path trail)) (fs/exists? (:gpx-path trail))))
        mapped (filter #(contains? mapped-refs (:id %)) trail-seq)
        unmapped (remove #(contains? mapped-refs (:id %)) trail-seq)
        with-gpx (filter has-gpx? unmapped)
        without-gpx (remove has-gpx? unmapped)
        sorted (fn [trails] (sort-by #(report/id->sort-key (:id %)) trails))
        render-trail (fn [trail show-map?]
                       [:div
                        (:id trail) " - " (or (:drustvo trail) "") " - " (:title trail) " "
                        (when show-map?
                          (list
                           [:a {:href (str "https://staze.pss.rs/#trail=" (:id trail))
                                :target "_blank"}
                            "[map]"]
                           " "))
                        (when (:link trail)
                          [:a {:href (:link trail) :target "_blank"} "[pss]"])])]
    (with-open [os (fs/output-stream (path/child pss-map-v1-path "trail-list.html"))]
      (io/write-string
       os
       (hiccup/html
        [:html
         [:head [:meta {:charset "utf-8"}] [:title "Регистар стаза"]]
         [:body
          [:h2 (str "Мапиране стазе (" (count mapped) ")")]
          (map #(render-trail % true) (sorted mapped))
          [:h2 (str "Немапиране стазе са GPX траком (" (count with-gpx) ")")]
          (map #(render-trail % false) (sorted with-gpx))
          [:h2 (str "Немапиране стазе без GPX трака (" (count without-gpx) ")")]
          (map #(render-trail % false) (sorted without-gpx))]])))
    (context/trace
     job-context
     (str
      "trail-list.html created, " (count mapped) " mapped, " (count with-gpx) " with gpx, "
      (count without-gpx) " without gpx"))))

(defn create-trail-list-club-html
  "Reads pss-dataset.edn and trails.geojson and writes trail-list-club.html,
  trails grouped by club ( :drustvo ); mapped trails get [map] and [pss]
  links, unmapped trails get only a [pss] link plus a [gpx] marker if a gpx
  file exists on disk"
  [job-context]
  (let [configuration (context/configuration job-context)
        osm-pss-integration-path (:osm-pss-integration-path configuration)
        pss-map-v1-path (:pss-map-v1-path configuration)
        trail-seq (with-open [is (fs/input-stream
                                   (path/child osm-pss-integration-path "pss-dataset.edn"))]
                    (vals (edn/read is)))
        mapped-refs (with-open [is (fs/input-stream
                                     (path/child pss-map-v1-path "dataset" "trails.geojson"))]
                      (into
                       #{}
                       (map #(get-in % [:properties :ref]) (:features (json/read-keyworded is)))))
        has-gpx? (fn [trail] (and (some? (:gpx-path trail)) (fs/exists? (:gpx-path trail))))
        mapped? (fn [trail] (contains? mapped-refs (:id trail)))
        no-club-label "Остало"
        club-groups (group-by #(or (:drustvo %) no-club-label) trail-seq)
        club->anchor (fn [club]
                       (if (= club no-club-label)
                         "ostalo"
                         (-> club
                             string/lower-case
                             (string/replace "č" "c")
                             (string/replace "ć" "c")
                             (string/replace "š" "s")
                             (string/replace "ž" "z")
                             (string/replace "đ" "dj")
                             (string/replace #"[^a-z]" ""))))
        render-trail (fn [trail]
                       [:div
                        (:id trail) " - " (:title trail) " "
                        (if (mapped? trail)
                          (list
                           [:a {:href (str "https://staze.pss.rs/#trail=" (:id trail))
                                :target "_blank"}
                            "[map]"]
                           " ")
                          (when (has-gpx? trail)
                            (list "[gpx] ")))
                        (when (:link trail)
                          [:a {:href (:link trail) :target "_blank"} "[pss]"])])]
    (with-open [os (fs/output-stream (path/child pss-map-v1-path "trail-list-club.html"))]
      (io/write-string
       os
       (hiccup/html
        [:html
         [:head [:meta {:charset "utf-8"}] [:title "Регистар стаза по клубовима"]]
         [:body
          (map
           (fn [[club trails]]
             (let [anchor (club->anchor club)]
               (list
                [:h2 {:id anchor}
                 club " (" (count trails) ") "
                 [:a {:href (str "#" anchor)} "[share]"] " "
                 [:a {:href (str "index.html?club=" anchor) :target "_blank"} "[map]"]]
                (map render-trail (sort-by #(report/id->sort-key (:id %)) trails)))))
           (sort-by
            (fn [[club _]] [(if (= club no-club-label) 1 0) club])
            club-groups))]])))
    (context/trace
     job-context
     (str
      "trail-list-club.html created, " (count club-groups) " clubs, "
      (count trail-seq) " trails"))))

(defn prepare-nepravilnosti-geojson
  "Reads dataset/nepravilnosti.dot and writes
  pss-map-v1/dataset/nepravilnosti.geojson, one marker per location with its
  tags joined into a marker-body popup, to be loaded by pss-map-v1/index.html
  as the nepravilnosti layer. Marker rendering copied from
  clj-geo.visualization.map/tile-overlay-dot-layer, only line wrap width
  differs ( 40 instead of 80 chars, to keep popups readable at map scale )."
  [job-context]
  (let [configuration (context/configuration job-context)
        osm-pss-integration-path (:osm-pss-integration-path configuration)
        pss-map-v1-path (:pss-map-v1-path configuration)
        wrap-line-fn
        (fn [text width]
          (reduce
           (fn [lines word]
             (if (empty? lines)
               [word]
               (let [current-line (peek lines)]
                 (if (<= (+ (count current-line) 1 (count word)) width)
                   (conj (pop lines) (str current-line " " word))
                   (conj lines word)))))
           []
           (string/split text #" ")))
        link-line-fn
        (fn [line]
          (string/replace
           line
           #"https?://\S+"
           (fn [url] (str "<a href='" url "' target='_blank'>" url "</a>"))))
        url-tag-fn
        (fn [tag]
          (when (.startsWith tag "|url|")
            (let [splits (.split tag "\\|")]
              (str "<a href='" (nth splits 3) "' target='_blank'>" (nth splits 2) "</a>"))))
        prepare-body-fn
        (fn [tags]
          (string/join
           "<br/>"
           (mapcat
            (fn [tag]
              (if-let [url-line (url-tag-fn tag)]
                [url-line]
                (map
                 link-line-fn
                 (if (> (count tag) 40)
                   (wrap-line-fn tag 40)
                   [tag]))))
            tags)))
        dot-seq (with-open [is (fs/input-stream
                                (path/child osm-pss-integration-path "nepravilnosti.dot"))]
                  (doall (humandot/read is)))]
    (with-open [os (fs/output-stream
                    (path/child pss-map-v1-path "dataset" "nepravilnosti.geojson"))]
      (json/write-pretty-print
       (geojson/geojson
        (doall
         (map
          (fn [location]
            (geojson/marker
             (:longitude location)
             (:latitude location)
             (prepare-body-fn (:tags location))))
          dot-seq)))
       (io/output-stream->writer os)))
    (context/trace
     job-context
     (str "nepravilnosti.geojson created, " (count dot-seq) " markers"))))

(defn compare-trails [context]
  ;; verify ref from relation-mapping.tsv matches one from trails.geojson
  (let [trails-map (reduce
                    (fn [state trail]
                      (let [id (get-in trail [:properties :osm-relation-id])
                            ref (get-in trail [:properties :ref])]
                        (when-let [old-id (get state ref)]
                          (println "[ERROR] duplicate trails" ref old-id id))
                        (assoc state ref id)))
                    {}
                    (:features
                     (with-open [is (fs/input-stream trails-new-path)]
                       (json/read-keyworded is))))
        osm-relation-map (reduce
                          (fn [state [ref id]]
                            (when-let [old-id (get state ref)]
                              (println "[ERROR] duplicate mapping" ref old-id id))
                            (assoc state ref id))
                          {}
                          (map
                           #(.split % "\t")
                           (with-open [is (fs/input-stream relation-mapping-path)]
                             (rest
                              (doall (io/input-stream->line-seq is)))))
                          )]
    (context/trace context (str "count in trails: " (count trails-map)))
    (context/trace context (str "count in relation mapping: " (count osm-relation-map)))
    (doseq [[ref id] osm-relation-map]
      (cond
        (nil? (get trails-map ref))
        (context/trace context (str "[MISSING]" ref id))

        (not (= id (get osm-relation-map ref)))
        (context/trace context (str "[DIFFERENT" ref id (get osm-relation-map ref))))))

  ;; compare current with latest production
  ;; concept, run diff, commit what is ok, what is not resolve, iterate
  ;; todo impement as job
  ;; todo remove ignore list in next iteration

  (let [production (with-open [is (fs/input-stream (trails-production-path))]
                     (json/read-keyworded is))
        new (with-open [is (fs/input-stream trails-new-path)]
              (json/read-keyworded is))
        production-ref-seq (map #(get-in % [:properties :ref]) (:features production))
        new-ref-seq (map #(get-in % [:properties :ref]) (:features new))
        report (atom [])]

    ;; delete old report
    (doseq [file (fs/list ["Users" "vanja" "projects" "osm-pss-integration" "dataset" "staze-pss-rs-diff"])]
      (fs/delete file))

    (context/trace context (str "original refs:" (count production-ref-seq)))
    (context/trace context (str "new refs:" (count new-ref-seq)))

    (let [new-ref-set (into #{} new-ref-seq)]
      (doseq [production-trail (sort-by
                                #(get-in % [:properties :ref])
                                (:features production))]
        (let [osm-relation-id (get-in
                               production-trail
                               [:properties :osm-relation-id])
              ref (get-in
                   production-trail
                   [:properties :ref])]
          (when (not (contains? ignore ref))
            (when (not (contains? new-ref-set ref))
              (context/trace context (str "[REMOVED]" ref (str "(r" osm-relation-id ")")))
              (swap! report conj {:type :removed :ref ref :id osm-relation-id
                                  :website (get-in production-trail [:properties :website])}))))))
    (let [production-ref-set (into #{} production-ref-seq)]
      (doseq [new-trail (sort-by
                         #(get-in % [:properties :ref])
                         (:features new))]
        (let [osm-relation-id (get-in new-trail [:properties :osm-relation-id])
              ref (get-in new-trail [:properties :ref])]
          (when (not (contains? ignore ref))
            (when (not (contains? production-ref-set ref))
              (context/trace context (str "[ADDED]" ref (str "(r" osm-relation-id ")")))
              (swap! report conj {:type :added :ref ref :id osm-relation-id
                                  :website (get-in new-trail [:properties :website])}))))))

    (doseq [new-trail (sort-by
                       #(get-in % [:properties :ref])
                       (:features new))]
      (let [ref (get-in new-trail [:properties :ref])]
        (when (not (contains? ignore ref))
          (when-let [production-trail (first (filter
                                              #(= (get-in % [:properties :ref]) ref)
                                              (:features production)))]
            (let [production-properties (:properties production-trail)
                  new-properties (:properties new-trail)
                  simplify-geom (fn [feature]
                                  (first
                                   (reduce
                                    (fn [[coordinates end] sequence]
                                      (if (= end (first sequence))
                                        [(concat coordinates (drop 1 sequence)) (last sequence)]
                                        [(concat coordinates sequence) (last sequence)]))
                                    [[] nil]
                                    (:coordinates (:geometry feature)))))
                  production-geom (simplify-geom production-trail)
                  new-geom (simplify-geom new-trail)
                  osm-relation-id (get production-properties :osm-relation-id)
                  gpx-path (path/child gpx-root-path (str ref ".gpx"))
                  source-track-seq (when (fs/exists? gpx-path)
                                     (with-open [is (fs/input-stream gpx-path)]
                                       (:track-seq (gpx/read-gpx is))))
                  source-geojson (when source-track-seq
                                   (geojson/feature-collection
                                    (map geojson/line-string source-track-seq)))]

              (let [properties-changed (not (= production-properties new-properties))
                    geom-changed (not (= production-geom new-geom))
                    coords->length (fn [coordinate-seq]
                                     (reduce
                                      +
                                      0
                                      (map
                                       (fn [[[lon-a lat-a] [lon-b lat-b]]]
                                         (math/distance
                                          {:longitude lon-a :latitude lat-a}
                                          {:longitude lon-b :latitude lat-b}))
                                       (partition 2 1 coordinate-seq))))
                    reversed? (and geom-changed (= production-geom (vec (reverse new-geom))))
                    production-points (into #{} production-geom)
                    ;; group ways by actual shared-endpoint connectivity
                    ;; ( unlike simplify-geom, which concatenates every way
                    ;; end to end regardless of whether it really shares an
                    ;; endpoint with the next one ) before partitioning into
                    ;; same/changed runs - this keeps a real out-and-back
                    ;; spur made of many small connected ways as one run
                    ;; ( so a single new node at a way boundary still counts
                    ;; toward changed length ), while still refusing to
                    ;; splice together ways that aren't actually connected.
                    ;; a way can be stored in either direction relative to
                    ;; its neighbors, and can connect to either end of the
                    ;; group built up so far ( same quirk clj-geo.import.osm/
                    ;; check-connected? handles for node-id based
                    ;; connectivity ) - e.g. a whole chain can connect
                    ;; "backwards" onto the group's start, not just its end,
                    ;; so all four append/prepend x forward/reversed cases
                    ;; need checking
                    connected-way-groups (loop [ways (:coordinates (:geometry new-trail))
                                                current nil
                                                groups []]
                                           (if-let [way (first ways)]
                                             (cond
                                               (nil? current)
                                               (recur (rest ways) (vec way) groups)

                                               (= (last current) (first way))
                                               (recur (rest ways) (into current (rest way)) groups)

                                               (= (last current) (last way))
                                               (recur (rest ways) (into current (rest (reverse way))) groups)

                                               (= (first current) (last way))
                                               (recur (rest ways) (into (vec (butlast way)) current) groups)

                                               (= (first current) (first way))
                                               (recur (rest ways) (into (vec (butlast (reverse way))) current) groups)

                                               :else
                                               (recur (rest ways) (vec way) (conj groups current)))
                                             (if current (conj groups current) groups)))
                    way-runs-seq (map
                                  (fn [group]
                                    (let [runs (partition-by (fn [point] (contains? production-points point)) group)]
                                      ;; a closed loop's linear point array has
                                      ;; an arbitrary seam ( index 0 == last
                                      ;; index ); if a changed stretch happens
                                      ;; to straddle it, partition-by sees it
                                      ;; as two separate runs ( a tail and a
                                      ;; head ) instead of one continuous run -
                                      ;; stitch them back together
                                      (if (and (> (count runs) 1)
                                               (= (first group) (last group))
                                               (not (contains? production-points (first (first runs))))
                                               (not (contains? production-points (first (last runs)))))
                                        (cons
                                         (into (vec (last runs)) (rest (first runs)))
                                         (rest (butlast runs)))
                                        runs)))
                                  connected-way-groups)
                    changed-lines (mapcat
                                   (fn [runs]
                                     (filter
                                      (fn [run]
                                        (and
                                         (not (contains? production-points (first run)))
                                         (>= (count run) 2)))
                                      runs))
                                   way-runs-seq)
                    changed-length-m (reduce + 0 (map coords->length changed-lines))
                    total-length-m (coords->length new-geom)
                    percent-changed (if (pos? total-length-m)
                                       (* 100.0 (/ changed-length-m total-length-m))
                                       0.0)
                    ;; surface between old and new alignment for each changed
                    ;; run: close a ring by walking the new points forward
                    ;; then the corresponding old points ( found via the
                    ;; unchanged anchor points right before/after the run )
                    ;; backward, then shoelace-area it on a flat local
                    ;; projection ( good enough at trail scale )
                    production-geom-vec (vec production-geom)
                    ;; keep every occurrence of a point ( trails can cross
                    ;; themselves / retrace ), so an anchor doesn't get
                    ;; resolved to a distant unrelated repeat of the same
                    ;; coordinate
                    production-index (reduce
                                       (fn [m [idx point]] (update m point (fnil conj []) idx))
                                       {}
                                       (map-indexed vector production-geom-vec))
                    ;; of all occurrence combinations, pick the closest pair -
                    ;; that's the locally plausible correspondence for a run
                    ;; bounded by these two anchors
                    closest-pair (fn [idxs-a idxs-b]
                                   (when (and (seq idxs-a) (seq idxs-b))
                                     (apply min-key
                                            (fn [[a b]] (Math/abs (- a b)))
                                            (for [a idxs-a b idxs-b] [a b]))))
                    ring->area-m2 (fn [ring]
                                    (let [lat0 (/ (reduce + (map second ring)) (count ring))
                                          m-per-deg-lon (* 111320.0 (Math/cos (Math/toRadians lat0)))
                                          m-per-deg-lat 110540.0
                                          points (map
                                                  (fn [[lon lat]] [(* lon m-per-deg-lon) (* lat m-per-deg-lat)])
                                                  ring)
                                          closed (concat points [(first points)])]
                                      (Math/abs
                                       (/ (reduce
                                           +
                                           (map
                                            (fn [[[x1 y1] [x2 y2]]] (- (* x1 y2) (* x2 y1)))
                                            (partition 2 1 closed)))
                                          2))))
                    changed-area-m2 (reduce
                                     +
                                     0
                                     (mapcat
                                      (fn [runs]
                                        (keep
                                         (fn [[prev cur nxt]]
                                           (when (and cur prev nxt (not (contains? production-points (first cur))))
                                             (let [[idx-before idx-after]
                                                   (closest-pair
                                                    (get production-index (last prev))
                                                    (get production-index (first nxt)))]
                                               (when (and idx-before idx-after (not= idx-before idx-after))
                                                 (let [old-path (if (< idx-before idx-after)
                                                                  (subvec production-geom-vec idx-before (inc idx-after))
                                                                  (vec (reverse (subvec production-geom-vec idx-after (inc idx-before)))))
                                                       old-between (butlast (rest old-path))
                                                       anchor-gap-m (coords->length [(last prev) (first nxt)])
                                                       cur-length-m (coords->length cur)]
                                                   ;; guard against anchor
                                                   ;; mismatches on loop / self-
                                                   ;; crossing trails - a
                                                   ;; legitimate local reroute's
                                                   ;; boundary anchors should be
                                                   ;; physically close together;
                                                   ;; a large gap means even the
                                                   ;; closest index-wise match is
                                                   ;; a topologically unrelated
                                                   ;; distant part of the route
                                                   (when (<= anchor-gap-m (max 200 (* 10 cur-length-m)))
                                                     (let [ring (concat [(last prev)] cur [(first nxt)] (reverse old-between))]
                                                       (when (>= (count ring) 3)
                                                         (ring->area-m2 ring)))))))))
                                         (partition 3 1 (concat [nil] runs [nil]))))
                                      way-runs-seq))
                    diff-info (when geom-changed
                                {:reversed reversed?
                                 :changed-length-m changed-length-m
                                 :percent-changed percent-changed
                                 :changed-area-m2 changed-area-m2})]
                (when properties-changed
                  (context/trace context (str "[MODIFIED_PROPERTIES] " ref " (r" osm-relation-id ")"))
                  (let [changes (concat
                                 (keep (fn [[key value]]
                                         (when (not (= value (get production-properties key)))
                                           {:key key :new-value value :old-value (get production-properties key)}))
                                       new-properties)
                                 (keep (fn [[key value]]
                                         (when (nil? (get new-properties key))
                                           {:key key :new-value nil :old-value value}))
                                       production-properties))]
                    (doseq [{:keys [key new-value old-value]} changes]
                      (context/trace context (str "\t" (name key) " " old-value " -> " new-value)))
                    (swap! report conj (merge
                                        {:type (if geom-changed :modified-both :modified-properties)
                                         :ref ref :id osm-relation-id
                                         :name (get new-properties :name) :changes (vec changes)
                                         :website (get new-properties :website)}
                                        diff-info))))
                (when geom-changed
                  (context/trace
                   context
                   (str
                    "[MODIFIED_GEOM] \"" ref "\" ;; " osm-relation-id
                    (when reversed? " (reversed)")
                    " " (int changed-length-m) "m "
                    (format "%.1f" percent-changed) "% "
                    (int changed-area-m2) "m2"))
                  (when-not properties-changed
                    (swap! report conj (merge
                                        {:type :modified-geom :ref ref :id osm-relation-id
                                         :name (get new-properties :name)
                                         :website (get new-properties :website)}
                                        diff-info)))
                  (let [segment-midpoint-markers
                        (fn [segments color-hex]
                          (let [segment-indices (reduce
                                                 (fn [m [idx segment]]
                                                   (update m (vec segment) (fnil conj []) (inc idx)))
                                                 {}
                                                 (map-indexed vector segments))
                                points (keep
                                        (fn [[segment indices]]
                                          (let [coords (vec segment)
                                                cnt (count coords)]
                                            (when (pos? cnt)
                                              (let [[lon lat] (nth coords (int (/ cnt 2)))
                                                    label (apply str (interpose "," indices))]
                                                (geojson/point
                                                 lon lat
                                                 {:marker-div (str "<div style='text-align:center;line-height:24px;font-size:12px;width:auto;min-width:24px;height:24px;padding:0 4px;border-radius:50%;background-color:" color-hex ";color:white;font-weight:bold;'>" label "</div>")})))))
                                        segment-indices)]
                            (when (seq points)
                              (geojson/feature-collection points))))
                        sample-markers
                        (fn [coordinates color-hex n]
                          (let [coords (vec coordinates)
                                cnt (count coords)]
                            (when (pos? cnt)
                              (let [step (max 1 (int (/ cnt n)))]
                                (geojson/feature-collection
                                 (map-indexed
                                  (fn [idx i]
                                    (let [[lon lat] (nth coords (min i (dec cnt)))]
                                      (geojson/point
                                       lon lat
                                       {:marker-div (str "<div style='text-align:center;line-height:24px;font-size:12px;width:24px;height:24px;border-radius:50%;background-color:" color-hex ";color:white;font-weight:bold;'>" (inc idx) "</div>")})))
                                  (range 0 (* step n) step)))))))]
                    (with-open [os (fs/output-stream
                                    (path/child
                                     ["Users" "vanja" "projects" "osm-pss-integration" "dataset" "staze-pss-rs-diff" (str ref ".html")]))]
                      (io/write-string
                       os
                       (map/render-raw
                        {}
                        (into
                         [
                          (map/tile-layer-osm true)
                          (map/tile-layer-bing-satellite false)
                          (binding [geojson/*style-stroke-color* geojson/color-green
                                    geojson/*style-stroke-width* 16]
                            (map/geojson-layer "original" production-trail true true))
                          (binding [geojson/*style-stroke-color* geojson/color-red
                                    geojson/*style-stroke-width* 8]
                            (map/geojson-layer "new" new-trail true true))
                          (when (seq changed-lines)
                            (binding [geojson/*style-stroke-color* "#FFA500"
                                      geojson/*style-stroke-width* 8]
                              (map/geojson-layer
                               "changed"
                               {:type "Feature"
                                :properties {}
                                :geometry {:type "MultiLineString"
                                           :coordinates (vec changed-lines)}}
                               true true)))
                          (when source-geojson
                            (binding [geojson/*style-stroke-color* geojson/color-blue
                                      geojson/*style-stroke-width* 4]
                              (map/geojson-layer "source gpx" source-geojson false false)))]
                         (filter
                          some?
                          [(when-let [markers (segment-midpoint-markers
                                               (:coordinates (:geometry new-trail))
                                               "#FF0000")]
                             (map/geojson-style-extended-layer "new markers" markers false false))
                           (when-let [markers (when source-track-seq
                                                (sample-markers
                                                 (map
                                                  (fn [loc] [(:longitude loc) (:latitude loc)])
                                                  (apply concat source-track-seq))
                                                 "#0000FF" 10))]
                             (map/geojson-style-extended-layer "source markers" markers false false))])))))))))))))
    (with-open [os (fs/output-stream
                    (path/child
                     ["Users" "vanja" "projects" "osm-pss-integration" "dataset" "staze-pss-rs-diff" "index.html"]))]
      (io/write-string
       os
       (hiccup/html
           [:html
            [:head
             [:meta {:charset "utf-8"}]
             [:title "Production Comparison Report"]
             [:style "body{font-family:sans-serif;margin:20px} table{border-collapse:collapse;width:100%} th,td{border:1px solid #ddd;padding:8px;text-align:left} th{background-color:#4CAF50;color:white} tr:nth-child(even){background-color:#f2f2f2} a{color:#1a73e8} .changes{font-size:0.9em;color:#666}"]]
            [:body
             [:h1 "Production Comparison Report"]
             [:table
              [:tr [:th "Ref"] [:th "ID"] [:th "Name"] [:th "Type"] [:th "Details"] [:th "Diff"] [:th "Links"]]
              (for [entry (sort-by :ref @report)]
                [:tr
                 [:td (:ref entry)]
                 [:td (:id entry)]
                 [:td (or (:name entry) "")]
                 [:td (name (:type entry))]
                 [:td
                  (cond
                    (#{:modified-properties :modified-both} (:type entry))
                    [:div {:class "changes"}
                     (for [{:keys [key new-value old-value]} (:changes entry)]
                       [:div (str (name key) ": " old-value " -> " new-value)])]
                    (= (:type entry) :modified-geom)
                    (str "\"" (:ref entry) "\" ;; " (:id entry)))]
                 [:td
                  (when (:percent-changed entry)
                    (str
                     (int (:changed-length-m entry)) "m ("
                     (format "%.1f" (:percent-changed entry)) "%), "
                     (int (:changed-area-m2 entry)) "m²"
                     (when (:reversed entry) " reversed")))]
                 [:td
                  (when (#{:modified-geom :modified-both} (:type entry))
                    [:a {:href (str (:ref entry) ".html") :target "_blank"} "diff"])
                  " "
                  [:a {:href (str "http://localhost:7077/route/edit/" (:id entry)) :target "_blank"} "edit"]
                  " "
                  (when (:website entry)
                    [:a {:href (:website entry) :target "_blank"} "pss"])
                  " "
                  [:a {:href (str "https://osm.org/relation/" (:id entry)) :target "_blank"} "osm"]
                  " "
                  [:a {:href (str "http://localhost:7077/view/osm/history/relation/" (:id entry)) :target "_blank"} "history"]
                  " "
                  [:a {:href (str "http://level0.osmz.ru/?url=relation/" (:id entry)) :target "_blank"} "level0"]
                  " "
                  [:a {:href (str "https://vanjakom.github.io/trek-mate-osme/editor.html?id=r" (:id entry)) :target "_blank"} "tmosme"]]])]]])))
    (context/trace context "[DONE]")
    (context/trace context "take a look at file:///Users/vanja/projects/osm-pss-integration/dataset/staze-pss-rs-diff/index.html")))
