(ns osm-pss-integration.job.qa
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
(def relation-mapping-path ["Users" "vanja" "projects" "osm-pss-integration"
                            "dataset" "relation-mapping.tsv"])
(def gpx-root-path ["Users" "vanja" "projects" "osm-pss-integration" "dataset"
                     "pss.rs" "routes"])

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
  (println "count in trails: " (count trails-map))
  (println "count in relation mapping: " (count osm-relation-map))
  (doseq [[ref id] osm-relation-map]
    (cond
      (nil? (get trails-map ref))
      (println "[MISSING]" ref id)

      (not (= id (get osm-relation-map ref)))
      (println "[DIFFERENT" ref id (get osm-relation-map ref)))))



;; compare current with latest production
;; concept, run diff, commit what is ok, what is not resolve, iterate
;; todo impement as job
;; todo remove ignore list in next iteration

(let [production (with-open [is (fs/input-stream trails-production-path)]
                   (json/read-keyworded is))
      new (with-open [is (fs/input-stream trails-new-path)]
            (json/read-keyworded is))
      production-ref-seq (map #(get-in % [:properties :ref]) (:features production))
      new-ref-seq (map #(get-in % [:properties :ref]) (:features new))
      
      ;; todo reset on each iteration
      ignore
      #{
        "E7-10-11" ;; 17610623 E7-10-11 -> E7-10
        "E7-10" ;; 17610623 E7-10-11 -> E7-10

        ;; verifikovane izmen
        "1-1-2" ;; 12150508  - завршено мапирање стазе, OSM измене се поклопиле са трагом
        "1-13-2" ;; 11314365 - прегледана стаза и поправљено мапирање
        "2-15-1" ;; 11066834 - поправљена стаза
        "2-15-2" ;; 11069677 - поправљена стаза

        ;; staze kod kojih je malo izmenjena geografija
        "1-14-1" ;; 14288192
        "1-15-1" ;; 11334200
        "1-2-1" ;; 13906712
        "1-2-2" ;; 13913437 
        "1-2-3" ;; 13916548
        "1-3-1" ;; "11317382"
        "1-4-1" ;; "14906749"
        "1-4-3" ;; "14912124"
        "1-4-4" ;; "14916943"
        "1-4-5" ;; "14921298"
        "2-13-1" ;; "11076236"
        "2-14-19" ;; 11073423
        "2-14-21" ;; 12434519
        "2-16-1" ;; 15002235
        "2-16-2" ;; 15002235
        "2-16-3" ;; 16857128
        "2-3-1" ;; 12086876
        "2-3-2" ;; 12091435
        "2-3-3" ;; 12094994
        "2-3-4" ;; 12098640
        "2-3-5" ;; 12102740
        

        
        ;; correct edits
        "2-5-6" ;;"11043543"
        "4-36-1" ;; "11182558"
        }]
  
  ;; delete old report
  (doseq [file (fs/list ["Users" "vanja" "projects" "osm-pss-integration" "dataset" "staze-pss-rs-diff"])]
    (fs/delete file))

  (println "original refs:" (count production-ref-seq))
  (println "new refs:" (count new-ref-seq))
  
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
            (println "[REMOVED]" ref (str "(r" osm-relation-id ")")))))))
  (let [production-ref-set (into #{} production-ref-seq)]
    (doseq [new-trail (sort-by
                       #(get-in % [:properties :ref])
                       (:features new))]
      (let [osm-relation-id (get-in new-trail [:properties :osm-relation-id])
            ref (get-in new-trail [:properties :ref])]
        (when (not (contains? ignore ref))
            (when (not (contains? production-ref-set ref))
              (println "[ADDED]" ref (str "(r" osm-relation-id ")")))))))
  
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

            (cond
              (not (= production-properties new-properties))
              (do
                (println "[MODIFIED_PROPERTIES]" ref (str "(r" osm-relation-id ")"))
                (doseq [[key value] new-properties]
                  (when (not (= value (get production-properties key)))
                    (println "\t" key value "->" (get production-properties key))))
                (doseq [[key value] production-properties]
                  (when (nil? (get new-properties key))
                    (println "\t" key value "->" nil))))
              (not (= production-geom new-geom))
              (do
                (println (str "[MODIFIED_GEOM] \"" ref "\" ;; " osm-relation-id))
                (let [sample-markers (fn [coordinates color-hex n]
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
                        (when source-geojson
                          (binding [geojson/*style-stroke-color* geojson/color-blue
                                    geojson/*style-stroke-width* 4]
                            (map/geojson-layer "source gpx" source-geojson true true)))]
                       (filter
                        some?
                        [(when-let [markers (sample-markers new-geom "#FF0000" 10)]
                           (map/geojson-style-extended-layer "new markers" markers true true))
                         (when-let [markers (when source-track-seq
                                              (sample-markers
                                               (map
                                                (fn [loc] [(:longitude loc) (:latitude loc)])
                                                (apply concat source-track-seq))
                                               "#0000FF" 10))]
                           (map/geojson-style-extended-layer "source markers" markers true true))])))))))))))))
  (println "[DONE]"))

