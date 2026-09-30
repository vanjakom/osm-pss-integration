(ns osm-pss-integration.job.report
  (:use
   clj-common.clojure)
  (:require
   [clojure.string :as string]

   [clj-common.as :as as]
   [clj-common.context :as context]
   [clj-common.edn :as edn]
   [clj-common.io :as io]
   [clj-common.json :as json]
   [clj-common.localfs :as fs]
   [clj-common.notemd :as notemd]
   [clj-common.path :as path]

   [clj-geo.dot.store.humandot :as humandot]
   [clj-geo.visualization.map :as map]))

(defn id->category
  "0 normal trails, 1 transversals ( T- prefix ), 2 E paths, matches
  registar.md / trail-lst.md sort convention"
  [id]
  (cond
    (.startsWith id "T-") 1
    (re-matches #"E\d.*" id) 2
    :else 0))

(defn id->numeric-key
  "Parses dash separated segments of id ( after stripping leading letters )
  into [number suffix] pairs so trails sort numerically, not
  lexicographically ( \"4-4-1\" before \"4-27-1\", \"E7-12\" before
  \"E7-12a\" )"
  [id]
  (let [rest (string/replace id #"^[A-Za-z]*-?" "")]
    (vec
     (map
      (fn [part]
        (let [[_ digits suffix] (re-matches #"(\d*)(.*)" part)]
          [(if (empty? digits) 0 (as/as-long digits)) suffix]))
      (string/split rest #"-")))))

(defn id->sort-key [id]
  [(id->category id) (id->numeric-key id)])

(defn create-trail-list
  "Reads pss-dataset.edn and writes trail-lst.md, a space aligned id /
  planina / naziv listing, sorted normal trails first, then transversals,
  then E paths"
  [job-context]
  (let [configuration (context/configuration job-context)
        osm-pss-integration-path (:osm-pss-integration-path configuration)
        trail-seq (with-open [is (fs/input-stream
                                   (path/child osm-pss-integration-path "pss-dataset.edn"))]
                    (sort-by
                     #(id->sort-key (:id %))
                     (vals (edn/read is))))
        id-width (+ 2 (apply max (map #(count (:id %)) trail-seq)))
        planina-width (+ 2 (apply max (map #(count (or (:planina %) "")) trail-seq)))
        row-format (str "%-" id-width "s%-" planina-width "s%s")]
    (with-open [os (fs/output-stream (path/child osm-pss-integration-path "trail-lst.md"))]
      (io/write-string
       os
       (str
        "# PSS trail list\n\n"
        "```\n"
        (format row-format "id" "planina" "naziv") "\n"
        (apply str (repeat (dec id-width) "-")) " "
        (apply str (repeat (dec planina-width) "-")) " -----\n"
        (string/join
         "\n"
         (map
          #(format row-format (:id %) (or (:planina %) "") (:title %))
          trail-seq))
        "\n```\n")))
    (context/trace job-context (str "trail-lst.md created, " (count trail-seq) " trails"))))

(defn create-registar-html
  "Reads registar.md and writes registar.html, a single self contained page
  rendered via clj-common.notemd/notes->html, one #notemd note per entry,
  in file order"
  [job-context]
  (let [configuration (context/configuration job-context)
        osm-pss-integration-path (:osm-pss-integration-path configuration)
        note-seq (with-open [is (fs/input-stream
                                  (path/child osm-pss-integration-path "registar.md"))]
                   (doall (notemd/read-notes is #{})))]
    (with-open [os (fs/output-stream (path/child osm-pss-integration-path "registar.html"))]
      (io/write-string os (notemd/notes->html {} note-seq)))
    (context/trace job-context (str "registar.html created, " (count note-seq) " notes"))))

;; same default view pss-map-v1/index.html uses, fits all of Serbia on an
;; average laptop screen
(def serbia-view-configuration
  {:center-longitude 21.08276
   :center-latitude 44.41599
   :center-zoom 8})

(defn create-map [context]
  (let [configuration (context/configuration context)
        dataset-root-path (get configuration :dataset-root-path)
        export-path (get configuration :export-path)]
    (context/trace context (str "creating map for: pss"))
    (with-open [os (fs/output-stream export-path)
                nepravilnosti-is (fs/input-stream
                                  (path/child dataset-root-path
                                              "nepravilnosti.dot"))
                osm-notes-is (fs/input-stream
                              (path/child dataset-root-path
                                          "osm-notes.dot"))]
      (let [nepravilnosti-seq (humandot/read nepravilnosti-is)
            osm-notes-seq (humandot/read osm-notes-is)]
        (io/write-string
         os
         (map/render-raw
          serbia-view-configuration
          [
           (map/tile-layer-osm true)
           (map/tile-layer-bing-satellite false)
           (map/tile-layer-google-satellite false)

           (map/tile-overlay-dot-layer "неправилности" nepravilnosti-seq)
           (map/tile-overlay-dot-layer "osm notes" osm-notes-seq)]))))
    (context/trace
     context
     (str
      "map created, view <a href='file://"
      (path/path->string export-path)
      "'>map</a>"))))
