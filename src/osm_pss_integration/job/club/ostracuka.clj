(ns osm-pss-integration.job.club.ostracuka
  (:use
   clj-common.clojure)
  (:require
   [clj-common.as :as as]
   [clj-common.edn :as edn]
   [clj-common.io :as io]
   [clj-common.json :as json]
   [clj-common.http :as http]
   [clj-common.localfs :as fs]
   [clj-common.path :as path]
   [clj-geo.import.gpx :as gpx]
   [clj-geo.import.location :as location]
   [clj-geo.import.geojson :as geojson]
   [clj-geo.import.osm :as osm]
   [clj-geo.import.osmapi :as osmapi]
   [clj-geo.math.core :as math]
   [clj-geo.osm.dataset :as dataset]
   [clj-geo.visualization.map :as map]
   [clojure.string :as string]
   [osm-pss-integration.job.pss :as pss]))


(def root-path ["Users" "vanja" "projects" "osm-pss-integration"])

(def trail-seq
  (with-open [is (fs/input-stream (path/child root-path "dataset" "pss-dataset.edn"))]
    (filter
     #(= (:drustvo %) "Oštra čuka PD")
     (vals (edn/read is)))))

(with-open [os (fs/output-stream (path/child root-path "dataset" "klubovi" "ostracuka.html"))]
  (io/write-string
   os
   (map/render-raw
    {}
    (concat
     [
      (map/tile-layer-osm true)
      (map/tile-layer-bing-satellite false)
      (map/tile-layer-osm-rs false)
      (map/tile-layer-opentopomap false)
      (map/tile-overlay-waymarked-hiking false)
      (map/tile-overlay-bounds false)

      (map/geojson-style-layer
       "gpx"
       (geojson/geojson
        (binding [geojson/*style-stroke-color* geojson/color-blue]
          (doall
           (mapcat
            (fn [trail]
              (if (and (some? (:gpx-path trail))
                       (fs/exists? (:gpx-path trail)))
                (with-open [is (fs/input-stream (:gpx-path trail))]
                  (map
                   geojson/line-string
                   (:track-seq (gpx/read-gpx is))))
                []))
            trail-seq))))
       true
       true)]

     (keep
      (fn [trail]
        (let [trail-path (path/child root-path "dataset" "trails" (str (:id trail) ".geojson"))]
          (when (fs/exists? trail-path)
            (map/geojson-style-layer
             (str (:id trail) " " (:title trail))
             (update
              (with-open [is (fs/input-stream trail-path)]
                (json/read-keyworded is))
              :features
              (fn [features]
                (map
                 #(update % :properties merge {"stroke" geojson/color-red "stroke-width" geojson/*style-stroke-width*})
                 features)))))))
      trail-seq)))))

;; report

(with-open [os (fs/output-stream (path/child root-path "dataset" "klubovi" "ostracuka.md"))]
  (let [relation-mapping (with-open [is (fs/input-stream
                                         (path/child root-path "dataset" "relation-mapping.tsv"))]
                            (into
                             {}
                             (map
                              (fn [line]
                                (let [[ref osm-relation-id] (string/split line #"\t")]
                                  [ref osm-relation-id]))
                              (rest (io/input-stream->line-seq is)))))

        has-gpx? (fn [trail]
                   (and (some? (:gpx-path trail)) (fs/exists? (:gpx-path trail))))

        mapped-in-osm? (fn [trail] (some? (get relation-mapping (:id trail))))

        ;; computes trail length in meters, preferring dataset/trails/<id>.geojson,
        ;; falling back to gpx track, returns nil if neither is available
        trail->length (fn [trail]
                         (let [location-seq->length
                               (fn [location-seq]
                                 (reduce
                                  +
                                  0
                                  (map
                                   (fn [[location-a location-b]] (math/distance location-a location-b))
                                   (partition 2 1 location-seq))))
                               coordinate-seq->length
                               (fn [coordinate-seq]
                                 (location-seq->length
                                  (map
                                   (fn [[longitude latitude]] {:longitude longitude :latitude latitude})
                                   coordinate-seq)))
                               trail-path (path/child
                                           root-path "dataset" "trails" (str (:id trail) ".geojson"))]
                           (cond
                             (fs/exists? trail-path)
                             (reduce
                              +
                              0
                              (mapcat
                               (fn [feature]
                                 (let [geometry (:geometry feature)]
                                   (case (:type geometry)
                                     "LineString" [(coordinate-seq->length (:coordinates geometry))]
                                     "MultiLineString" (map coordinate-seq->length (:coordinates geometry))
                                     [0])))
                               (:features (with-open [is (fs/input-stream trail-path)]
                                            (json/read-keyworded is)))))

                             (and (some? (:gpx-path trail)) (fs/exists? (:gpx-path trail)))
                             (reduce
                              +
                              0
                              (map
                               location-seq->length
                               (:track-seq (with-open [is (fs/input-stream (:gpx-path trail))]
                                             (gpx/read-gpx is)))))

                             :else nil)))

        trail->report-section (fn [trail]
                                 (let [length (trail->length trail)
                                       osm-relation-id (get relation-mapping (:id trail))]
                                   (string/join
                                    "\n"
                                    [
                                     (str "## " (:id trail) " " (:title trail))
                                     (str "- length: " (if length (math/distance->human-string length) "unknown"))
                                     (str "- pss: " (:link trail))
                                     (str
                                      "- osm: "
                                      (if osm-relation-id
                                        (str "https://osm.org/relation/" osm-relation-id)
                                        "not mapped"))
                                     (str "- gpx: " (if (has-gpx? trail) "yes" "no"))])))

        introduction (str
                      "total trails: " (count trail-seq) "\n\n"
                      "mapped in osm: " (count (filter mapped-in-osm? trail-seq)) "\n\n"
                      "not mapped, have gpx (could be mapped): "
                      (count
                       (filter
                        #(and (not (mapped-in-osm? %)) (has-gpx? %))
                        trail-seq))
                      "\n\n"
                      "missing gpx: " (count (remove has-gpx? trail-seq)))]
    (io/write-string
     os
     (string/join
      "\n\n"
      (concat
       ["# Oštra čuka PD"
        introduction]
       (map trail->report-section (sort pss/id-compare trail-seq)))))))
