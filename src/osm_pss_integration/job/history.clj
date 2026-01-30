(ns osm-pss-integration.job.history
  (:use
   clj-common.clojure)
  (:require
   [clojure.core.async :as async]
   [hiccup.core :as hiccup]

   [clj-common.as :as as]
   [clj-common.context :as context]
   [clj-common.edn :as edn]
   [clj-common.io :as io]
   [clj-common.localfs :as fs]
   [clj-common.path :as path]
   [clj-common.pipeline :as pipeline]

   [clj-geo.import.geojson :as geojson]
   [clj-geo.import.osm :as osm]
   [clj-geo.osm.dataset :as dataset]
   [clj-geo.osm.histset :as histset]
   [clj-geo.visualization.map :as map]))

(defn relation-geometry [dataset relation-id]
  (let [relation (get-in dataset [:relation relation-id])]
    (geojson/feature-collection
     (map
      (fn [way-id]
        (let [way (get-in dataset [:way way-id])
              location-seq (map
                            (fn [node-id]
                              (let [node (get-in dataset [:node node-id])]
                                (select-keys node [:longitude :latitude])))
                            (:nodes way))]
          (geojson/line-string {"way" way-id} location-seq)))
      (map :id (filter #(= (:type %) :way)(:members relation)))))))

(defn way-geometry [dataset way-id]
  (let [way (get-in dataset [:way way-id])
        location-seq (map
                      (fn [node-id]
                        (let [node (get-in dataset [:node node-id])]
                          (select-keys node [:longitude :latitude])))
                      (:nodes way))]
    (geojson/line-string {"way" way-id} location-seq)))

(def active-pipeline nil)
(def relation-histset nil)

;; todo extract to job / function
;; looks like major use case is to prepare dataset for relation
;; and later use it for investigation ( repl )


#_(alter-var-root #'relation-histset (constantly (first relation-histset)))

#_(relation-geometry
   (dataset/dataset-at-t relation-histset 1589456615000)
   11098411)

#_(get-in
 (dataset/dataset-at-t relation-histset 1589456615000)
 [:relation 11098411])

(defn relation-geometry-history
  "Creates seq of version. Each version contains timestamp, changesetid and
  geojson for relation. Each way in relation is represented as LineString
  and way id kept in properties.
  logic:
  collect all nodes and ways that are part of relation, go over their history
  collect timestamp, changeset id pairs, sort by timestamp
  go over timestamps, recreate geometry at each timestamp, if geometry is
  changed create version"
  

  [histset relation-id]
  (let [
        way-set (into
                 #{}
                 (mapcat
                  (fn [version]
                    (map
                     :id
                     (filter
                      #(= (:type %) :way)
                      (:members version))))
                  (get-in histset [:relation relation-id])))
        node-set (into
                  #{}
                  (mapcat
                   (fn [way]
                     (mapcat #(:nodes %) way))
                   (map
                    #(get-in histset [:way %])
                    way-set)))
        changeset-seq (sort
                       (into
                        #{}
                        (concat
                         (map
                          #(vector (:timestamp %) (:changeset %))
                          (get-in histset [:relation relation-id]))
                         (mapcat
                          (fn [way]
                            (map
                             #(vector (:timestamp %) (:changeset %))
                             way))
                          (map
                           #(get-in histset [:way %])
                           way-set))
                         (mapcat
                          (fn [node]
                            (map
                             #(vector (:timestamp %) (:changeset %))
                             node))
                          (map
                           #(get-in histset [:node %])
                           node-set)))))
        start-timestamp (get-in histset [:relation relation-id 0 :timestamp])]
    (println "relation:" relation-id) 
    (println "count ways:" (count way-set))
    (println "count nodes:" (count node-set))
    (println "all changesets ( timestamp, changeset pair ):")
    (doseq [[timestamp changeset] changeset-seq]
      (println "\t" timestamp changeset))
    (println "start timestamp:" start-timestamp)
    (reduce
     (fn [version-seq [timestamp changeset]]
       (if (< timestamp start-timestamp)
         version-seq
         (let [dataset (dataset/dataset-at-t histset timestamp)
               geometry (relation-geometry dataset relation-id)]
           (if (not (= (:geometry (:last version-seq)) geometry))
             (do
               (println
                "geometry change at:" timestamp "changeset:"
                changeset)
               (conj
                version-seq
                {
                 :timestamp timestamp
                 :changeset changeset
                 :geometry geometry}))
             (do
               (println "no change at: " changeset)
               version-seq)))))
     []
     changeset-seq)))

(defn way-geometry-history
  "Creates seq of version. Each version contains timestamp, changesetid and
  geojson for way. Way is represented with LineString, way id kept in properties.
  logic:
  collect all nodes that are part of way, go over their history
  collect timestamp, changeset id pairs, sort by timestamp
  go over timestamps, recreate geometry at each timestamp, if geometry is
  changed create version"
  
  [histset way-id]
  (let [
        node-set (into
                  #{}
                  (mapcat #(:nodes %) (get-in histset [:way way-id])))
        changeset-seq (sort
                       (into
                        #{}
                        (mapcat
                         (fn [node]
                           (map
                            #(vector (:timestamp %) (:changeset %))
                            node))
                         (map
                          #(get-in histset [:node %])
                          node-set))))
        start-timestamp (get-in histset [:way way-id 0 :timestamp])]
    (println "way:" way-id)
    (println "count nodes:" (count node-set))
    (println "all changesets ( timestamp, changeset pair ):")
    (doseq [[timestamp changeset] changeset-seq]
      (println "\t" timestamp changeset))
    (println "start timestamp:" start-timestamp)
    (reduce
     (fn [version-seq [timestamp changeset]]
       (if (< timestamp start-timestamp)
         version-seq
         (let [dataset (dataset/dataset-at-t histset timestamp)
               geometry (way-geometry dataset way-id)]
           (if (not (= (:geometry (:last version-seq)) geometry))
             (do
               (println
                "geometry change at:" timestamp "changeset:"
                changeset)
               (conj
                version-seq
                {
                 :timestamp timestamp
                 :changeset changeset
                 :geometry geometry}))
             (do
               (println "no change at: " changeset)
               version-seq)))))
     []
     changeset-seq)))

(throw (new Exception "Prevent execution of debug"))

;; 20260106 debug E4-12
(let [color-set [
                   geojson/color-red
                   geojson/color-green
                   geojson/color-blue
                   geojson/color-orange
                   geojson/color-yellow]
        version-seq (relation-geometry-history relation-histset 14405401)]
    (with-open [os (fs/output-stream ["tmp" "r14405401.html"])]
      (io/write-string
       os
       (map/render-raw
        {}
        (concat
         [
          (map/tile-layer-osm)
          (map/tile-layer-bing-satellite false)
          (with-open [is (fs/input-stream
                          (path/string->path
                           "/Users/vanja/projects/osm-pss-integration/dataset/pss.rs/routes/E4-12.gpx"))]
            (binding [geojson/*style-stroke-color* geojson/color-purple]
              (map/geojson-gpx-layer "E4-12" is true true)))]
         (map-indexed
          (fn [index version]
            (binding [geojson/*style-stroke-color*
                      (nth color-set (mod index (count color-set)))]
              (map/geojson-layer
               (str (:changeset version))
               (:geometry version)
               false
               false))
            )
          version-seq))))))

(let [relation-id 14405401]
  (let [context (context/create-state-context)
        context-thread (pipeline/create-state-context-reporting-finite-thread context 5000)        
        channel-provider (pipeline/create-channels-provider)

        history-pbf ["Users" "vanja" "dataset-local" "geofabrik-serbia-history" "serbia-internal.osh.pbf"]]

    ;; read three times, once for relations, then for ways and at the end for nodes
    ;; ways and nodes will block until filter sets are provided
  
    (osm/read-osm-pbf-go
     (context/wrap-scope context "read-relation")
     history-pbf
     nil
     nil
     (channel-provider :relation-in))

    (osm/read-osm-pbf-go
     (context/wrap-scope context "read-way")
     history-pbf
     nil
     (channel-provider :way-in)
     nil)

    (osm/read-osm-pbf-go
     (context/wrap-scope context "read-node")
     history-pbf
     (channel-provider :node-in)
     nil
     nil)

    (osm/relation-histset-go
     (context/wrap-scope context "histset-create")
     relation-id
     (channel-provider :node-in)
     (channel-provider :way-in)
     (channel-provider :relation-in)
     (channel-provider :capture))
    
    (pipeline/capture-var-go
     (context/wrap-scope context "capture")
     (channel-provider :capture)
     (var relation-histset))
    (alter-var-root #'active-pipeline (constantly (channel-provider)))))


;; 20251221 debug for 3-14-4
(let [pss-ref "3-14-4"
      way-id 729081927
      color-set [
                 geojson/color-red
                 geojson/color-green
                 geojson/color-blue
                 geojson/color-orange
                 geojson/color-yellow]
      version-seq (way-geometry-history relation-histset way-id)]
  (with-open [os (fs/output-stream ["tmp" (str "w" way-id ".html")])]
    (io/write-string
     os
     (map/render-raw
      {}
      (concat
       [
        (map/tile-layer-osm)
        (map/tile-layer-bing-satellite false)
        (with-open [is (fs/input-stream
                        (path/string->path
                         (str
                          "/Users/vanja/projects/osm-pss-integration/dataset/pss.rs/routes/"
                          pss-ref ".gpx")))]
          (binding [geojson/*style-stroke-color* geojson/color-purple]
            (map/geojson-gpx-layer pss-ref is true true)))]
       (map-indexed
        (fn [index version]
          (binding [geojson/*style-stroke-color*
                    (nth color-set (mod index (count color-set)))]
            (map/geojson-layer
             (str (:changeset version))
             (:geometry version)
             false
             false))
          )
        version-seq)))))
  (println (str "open: file:///tmp/w" way-id ".html")))

(let [relation-id 12456767
      color-set [
                 geojson/color-red
                 geojson/color-green
                 geojson/color-blue
                 geojson/color-orange
                 geojson/color-yellow]
      version-seq (relation-geometry-history relation-histset relation-id)
      pss-id (get-in relation-histset [:relation relation-id 0 :tags "ref"])]
  (println "rendering history for" relation-id "(" pss-id ")")
  (with-open [os (fs/output-stream ["tmp" (str "r" relation-id ".html")])]
    (io/write-string
     os
     (map/render-raw
      {}
      (concat
       [
        (map/tile-layer-osm)
        (map/tile-layer-bing-satellite false)
        (with-open [is (fs/input-stream
                        (path/string->path
                         (str "/Users/vanja/projects/osm-pss-integration/dataset/pss.rs/routes/" pss-id ".gpx")))]
          (binding [geojson/*style-stroke-color* geojson/color-purple]
            (map/geojson-gpx-layer pss-id is true true)))]
       (map-indexed
        (fn [index version]
          (binding [geojson/*style-stroke-color*
                    (nth color-set (mod index (count color-set)))]
            (map/geojson-layer
             (str (:changeset version))
             (:geometry version)
             false
             false))
          )
        version-seq)))))
  (println (str "open file:///tmp/r" relation-id ".html")))

(let [relation-id 12456767]
  (let [context (context/create-state-context)
        context-thread (pipeline/create-state-context-reporting-finite-thread context 5000)        
        channel-provider (pipeline/create-channels-provider)

        history-pbf ["Users" "vanja" "dataset-local" "geofabrik-serbia-history" "serbia-internal.osh.pbf"]]

    ;; read three times, once for relations, then for ways and at the end for nodes
    ;; ways and nodes will block until filter sets are provided
  
    (osm/read-osm-pbf-go
     (context/wrap-scope context "read-relation")
     history-pbf
     nil
     nil
     (channel-provider :relation-in))

    (osm/read-osm-pbf-go
     (context/wrap-scope context "read-way")
     history-pbf
     nil
     (channel-provider :way-in)
     nil)

    (osm/read-osm-pbf-go
     (context/wrap-scope context "read-node")
     history-pbf
     (channel-provider :node-in)
     nil
     nil)

    (osm/relation-histset-go
     (context/wrap-scope context "histset-create")
     relation-id
     (channel-provider :node-in)
     (channel-provider :way-in)
     (channel-provider :relation-in)
     (channel-provider :capture))
    
    (pipeline/capture-var-go
     (context/wrap-scope context "capture")
     (channel-provider :capture)
     (var relation-histset))
    (alter-var-root #'active-pipeline (constantly (channel-provider)))))



;; 20251019 continued debug for 4-27-6
#_(let [color-set [
                   geojson/color-red
                   geojson/color-green
                   geojson/color-blue
                   geojson/color-orange
                   geojson/color-yellow]
        version-seq (way-geometry-history relation-histset 803641843)]
    (with-open [os (fs/output-stream ["tmp" "w803641843.html"])]
      (io/write-string
       os
       (map/render-raw
        {}
        (concat
         [
          (map/tile-layer-osm)
          (map/tile-layer-bing-satellite false)
          (with-open [is (fs/input-stream
                          (path/string->path
                           "/Users/vanja/projects/osm-pss-integration/dataset/pss.rs/routes/4-27-6.gpx"))]
            (binding [geojson/*style-stroke-color* geojson/color-purple]
              (map/geojson-gpx-layer "4-27-6" is true true)))]
         (map-indexed
          (fn [index version]
            (binding [geojson/*style-stroke-color*
                      (nth color-set (mod index (count color-set)))]
              (map/geojson-layer
               (str (:changeset version))
               (:geometry version)
               false
               false))
            )
          version-seq))))))

;; 20251018 debug for 4-27-6
#_(let [color-set [
                   geojson/color-red
                   geojson/color-green
                   geojson/color-blue
                   geojson/color-orange
                   geojson/color-yellow]
        version-seq (relation-geometry-history relation-histset 11098411)]
    (with-open [os (fs/output-stream ["tmp" "r11098411.html"])]
      (io/write-string
       os
       (map/render-raw
        {}
        (concat
         [
          (map/tile-layer-osm)
          (map/tile-layer-bing-satellite false)
          (with-open [is (fs/input-stream
                          (path/string->path
                           "/Users/vanja/projects/osm-pss-integration/dataset/pss.rs/routes/4-27-6.gpx"))]
            (binding [geojson/*style-stroke-color* geojson/color-purple]
              (map/geojson-gpx-layer "4-27-6" is true true)))]
         (map-indexed
          (fn [index version]
            (binding [geojson/*style-stroke-color*
                      (nth color-set (mod index (count color-set)))]
              (map/geojson-layer
               (str (:changeset version))
               (:geometry version)
               false
               false))
            )
          version-seq))))))

#_(let [relation-id 11098411]
    (let [context (context/create-state-context)
          context-thread (pipeline/create-state-context-reporting-finite-thread context 5000)        
          channel-provider (pipeline/create-channels-provider)

          history-pbf ["Users" "vanja" "dataset-local" "geofabrik-serbia-history" "serbia-internal.osh.pbf"]]

      ;; read three times, once for relations, then for ways and at the end for nodes
      ;; ways and nodes will block until filter sets are provided
  
      (osm/read-osm-pbf-go
       (context/wrap-scope context "read-relation")
       history-pbf
       nil
       nil
       (channel-provider :relation-in))

      (osm/read-osm-pbf-go
       (context/wrap-scope context "read-way")
       history-pbf
       nil
       (channel-provider :way-in)
       nil)

      (osm/read-osm-pbf-go
       (context/wrap-scope context "read-node")
       history-pbf
       (channel-provider :node-in)
       nil
       nil)

      (osm/relation-histset-go
       (context/wrap-scope context "histset-create")
       relation-id
       (channel-provider :node-in)
       (channel-provider :way-in)
       (channel-provider :relation-in)
       (channel-provider :capture))
    
      (pipeline/capture-var-go
       (context/wrap-scope context "capture")
       (channel-provider :capture)
       (var relation-histset))
      (alter-var-root #'active-pipeline (constantly (channel-provider)))))


#_(histset/debug-histset relation-histset)

#_(doseq [version (get-in relation-histset [:relation 11098411])]
  (println (:version version) (:timestamp version)))
