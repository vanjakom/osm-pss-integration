(ns osm-pss-integration.job.map
  (:require
   [clj-common.context :as context]
   [clj-common.io :as io]
   [clj-common.localfs :as fs]
   [clj-common.path :as path]
   [clj-geo.dot.store.humandot :as humandot]
   [clj-geo.visualization.map :as map]))

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
          {}
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

#_(create-map (context/create-stdout-context))
