(ns osm-pss-integration.job.pssqa
  (:use
   clj-common.clojure)
  (:require
   [clojure.core.async :as async]
   [hiccup.core :as hiccup]

   [clj-common.2d :as draw]
   [clj-common.as :as as]
   [clj-common.context :as context]
   [clj-common.http :as http]
   [clj-common.edn :as edn]
   [clj-common.io :as io]
   [clj-common.json :as json]
   [clj-common.localfs :as fs]
   [clj-common.path :as path]
   [clj-common.pipeline :as pipeline]
   [clj-common.text :as text]
   [clj-common.view :as view]

   [clj-geo.import.geojson :as geojson]
   [clj-geo.import.gpx :as gpx]
   [clj-geo.import.osm :as osm]
   [clj-geo.math.tile :as tile-math]
   [clj-geo.osm.dataset :as dataset]
   [clj-geo.visualization.map :as map]
   
   [clj-scheduler.core :as core]
   [clj-scheduler.env :as env]))

(def trails-new-path ["Users" "vanja" "projects" "osm-pss-integration" "dataset"
                      "trails.geojson"])
(def trails-production-path ["Users" "vanja" "projects" "pss-map-v1" "dataset"
                             "trails.geojson"])

;; todo impement as job
;; todo remove ignore list in next iteration
;; concept, run diff, commit what is ok, what is not resolve, iterate

(let [production (with-open [is (fs/input-stream trails-production-path)]
                 (json/read-keyworded is))
      new (with-open [is (fs/input-stream trails-new-path)]
            (json/read-keyworded is))
      ;; todo reset on each iteration
      ignore #{}]
  (let [production-ref-seq (map #(get-in % [:properties :ref]) (:features production))
        new-ref-seq (map #(get-in % [:properties :ref]) (:features new))]
    (println "original refs:" (count production-ref-seq))
    (println "new refs:" (count new-ref-seq))
    (let [new-ref-set (into #{} new-ref-seq)]
      (doseq [ref production-ref-seq]
        (when (not (contains? new-ref-set ref))
          (println "[REMOVED]" ref))))
    (let [production-ref-set (into #{} production-ref-seq)]
      (doseq [ref new-ref-seq]
        (when (not (contains? production-ref-set ref))
          (println "[ADDED]" ref))))
    ;; delete old report
    (doseq [file (fs/list ["Users" "vanja" "projects" "osm-pss-integration" "dataset" "staze-pss-rs-diff"])]
      (fs/delete file))

    
    (doseq [new-trail (:features new)]
      (let [ref (get-in new-trail [:properties :ref])]
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
                source-geojson (json/read-keyworded (http/get-as-stream
                                                     (str "http://localhost:7077/route/source/"
                                                          osm-relation-id)))]
            (when (and
                   (or
                    (not (= production-properties new-properties))
                    (not (= production-geom new-geom)))
                   (not (contains? ignore ref)))
              (println "[MODIFIED]" ref (str "(r" osm-relation-id ")"))
              (with-open [os (fs/output-stream
                              (path/child
                               ["Users" "vanja" "projects" "osm-pss-integration" "dataset" "staze-pss-rs-diff" (str ref ".html")]))]
                (io/write-string
                 os
                 (map/render-raw
                  {}
                  [
                   (map/tile-layer-osm true)
                   (map/tile-layer-bing-satellite false)
                   (binding [geojson/*style-stroke-color* geojson/color-green
                             geojson/*style-stroke-width* 16]
                     (map/geojson-layer "original" production-trail true true))
                   (binding [geojson/*style-stroke-color* geojson/color-red
                             geojson/*style-stroke-width* 8]
                     (map/geojson-layer "new" new-trail true true))
                   (binding [geojson/*style-stroke-color* geojson/color-blue
                             geojson/*style-stroke-width* 4]
                     (map/geojson-layer "source gpx" source-geojson true true))])))
              )))))
    (println "[DONE]")))

