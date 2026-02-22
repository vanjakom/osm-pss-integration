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

      report (atom [])
      
      ;; todo reset on each iteration
      ignore
      #{
        "E7-10-11" ;; 17610623 E7-10-11 -> E7-10
        "E7-10" ;; 17610623 E7-10-11 -> E7-10

        ;; nove staze
        "4-7-2" ;; 18954650
        "4-7-3" ;; 18949714
        "4-9-1" ;; 18944634
        "4-9-2" ;; 18940339
        "4-9-3" ;; 18934881
        "2-16-4" ;; 18971114
        "3-5-1" ;; 18963918
        "3-5-2" ;; 18959137
        
        ;; verifikovane izmene
        "1-1-2" ;; 12150508  - завршено мапирање стазе, OSM измене се поклопиле са трагом
        "1-13-2" ;; 11314365 - прегледана стаза и поправљено мапирање
        "2-15-1" ;; 11066834 - поправљена стаза
        "2-15-2" ;; 11069677 - поправљена стаза
        "2-8-1" ;; 14989345 - исправљен редослед
        "3-14-4" ;; 12456767 - пријављена неправилност
        "3-14-5" ;; 10886455 - исправљен редослед
        "3-14-6" ;; 12464587 - враћена траса назад, changeset 178599315
        "3-3-1" ;; 11129769 - поправљена стаза
        "3-3-3" ;; 11128849 - мапирана стаза до краја
        "4-26-1" ;; 11095303 - сређен почетак стазе
        "4-27-1" ;; 11102761 - мапирана стаза до краја
        "4-27-8" ;; 12899375 - исправљен редослед
        "4-31-1" ;; 11072572 - исправљен редослед
        "4-31-13" ;; 12902283 - исправљен редослед
        "4-31-14" ;; 12902454 - исправљен редослед
        "4-33-5" ;; 11313879 - исправљен редослед
        "4-37-1" ;; 11105694 - исправљен редослед
        "4-4-5" ;; 11515769 - поправљена стаза
        "4-49-3" ;; 11132794 - поправљена стаза
        "4-53-1" ;; 11092714 - исправљен редослед
        
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
        "2-3-6" ;; 12107165
        "2-3-7" ;; 12111398
        "2-3-8" ;; 12111419
        "3-13-1" ;; 14274559
        "3-13-2" ;; 14274591
        "3-14-1" ;; 12452310
        "3-14-2" ;; 12452381
        "3-14-3" ;; 12456657
        "3-14-7" ;; 12481140
        "3-14-8" ;; 14041560
        "3-18-1" ;; 14274639
        "3-20-1" ;; 12525280
        "3-20-11" ;; 12726586
        "3-20-3" ;; 12726079
        "3-20-5" ;; 14124085
        "3-20-7" ;; 12525333
        "3-20-8" ;; 12721371
        "3-20-9" ;; 12721535
        "3-22-1" ;; 11046762
        "3-22-2" ;; 14280882
        "3-22-3" ;; 14280939
        "3-22-4" ;; 14280954
        "3-22-5" ;; 14281022
        "3-28-4" ;; 11189458
        "3-3-2" ;; 15005069
        "3-32-1" ;; 12918979
        "3-34-1" ;; 14041583
        "3-7-1" ;; 11059141
        "3-8-2" ;; 11049687
        "3-8-3" ;; 11046859
        "4-27-2" ;; 11097820
        "4-27-6" ;; 11098411
        "4-27-7" ;; 11097107
        "4-27-9" ;; 13922554
        "4-30-1" ;; 14281136
        "4-31-11" ;; 11313552
        "4-31-16" ;; 14405400
        "4-31-2" ;; 11232073
        "4-31-4" ;; 11313503
        "4-31-5" ;; 11232158
        "4-31-8" ;; 11192825
        "4-33-1" ;; 11260598
        "4-33-2" ;; 11313703
        "4-33-3" ;; 11305864
        "4-33-4" ;; 11310324
        "4-33-9" ;; 13190234
        "4-36-2" ;; 11169649
        "4-37-3" ;; 13190270
        "4-39-4" ;; 11263559
        "4-39-5" ;; 11286263
        "4-39-6" ;; 11286225
        "4-39-7" ;; 12115780
        "4-4-3" ;; 11038377
        "4-4-6" ;; 11515913
        "4-4-7" ;; 11515931	
        "4-42-1" ;; 11164146
        "4-42-2" ;; 11166660
        "4-47-1" ;; 11297467
        "4-47-2" ;; 11294577
        "4-47-7" ;; 11289209
        "4-48-1" ;; 11038409
        "4-48-2" ;; 11038554
        "4-48-3" ;; 11038427
        "4-48-4" ;; 11038526
        "4-48-5" ;; 11518648
        "4-49-1" ;; 11038474
        "4-49-2" ;; 10808656
        "4-49-4" ;; 11518753
        "4-86-6" ;; 11518703	

        
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
            (println "[REMOVED]" ref (str "(r" osm-relation-id ")"))
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
              (println "[ADDED]" ref (str "(r" osm-relation-id ")"))
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

            (cond
              (not (= production-properties new-properties))
              (do
                (println "[MODIFIED_PROPERTIES]" ref (str "(r" osm-relation-id ")"))
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
                    (println "\t" key new-value "->" old-value))
                  (swap! report conj {:type :modified-properties :ref ref :id osm-relation-id
                                      :name (get new-properties :name) :changes (vec changes)
                                      :website (get new-properties :website)})))
              (not (= production-geom new-geom))
              (do
                (println (str "[MODIFIED_GEOM] \"" ref "\" ;; " osm-relation-id))
                (swap! report conj {:type :modified-geom :ref ref :id osm-relation-id
                                    :name (get new-properties :name)
                                    :website (get new-properties :website)})
                (let [segment-midpoint-markers
                      (fn [segments color-hex]
                        (let [points (keep-indexed
                                     (fn [idx segment]
                                       (let [coords (vec segment)
                                             cnt (count coords)]
                                         (when (pos? cnt)
                                           (let [[lon lat] (nth coords (int (/ cnt 2)))]
                                             (geojson/point
                                              lon lat
                                              {:marker-div (str "<div style='text-align:center;line-height:24px;font-size:12px;width:24px;height:24px;border-radius:50%;background-color:" color-hex ";color:white;font-weight:bold;'>" (inc idx) "</div>")})))))
                                     segments)]
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
                        (when source-geojson
                          (binding [geojson/*style-stroke-color* geojson/color-blue
                                    geojson/*style-stroke-width* 4]
                            (map/geojson-layer "source gpx" source-geojson true true)))
                        (let [production-points (into #{} production-geom)
                              changed-lines (keep
                                             (fn [segment]
                                               (let [changed (filter
                                                              #(not (contains? production-points %))
                                                              segment)]
                                                 (when (>= (count changed) 2)
                                                   changed)))
                                             (:coordinates (:geometry new-trail)))]
                          (when (seq changed-lines)
                            (binding [geojson/*style-stroke-color* "#FFA500"
                                      geojson/*style-stroke-width* 10]
                              (map/geojson-layer
                               "changed"
                               {:type "Feature"
                                :properties {}
                                :geometry {:type "MultiLineString"
                                           :coordinates (vec changed-lines)}}
                               true true))))]
                       (filter
                        some?
                        [(when-let [markers (segment-midpoint-markers
                                              (:coordinates (:geometry new-trail))
                                              "#FF0000")]
                           (map/geojson-style-extended-layer "new markers" markers true true))
                         (when-let [markers (when source-track-seq
                                              (sample-markers
                                               (map
                                                (fn [loc] [(:longitude loc) (:latitude loc)])
                                                (apply concat source-track-seq))
                                               "#0000FF" 10))]
                           (map/geojson-style-extended-layer "source markers" markers true true))])))))))))))))
  (when (seq @report)
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
           [:tr [:th "Ref"] [:th "ID"] [:th "Name"] [:th "Type"] [:th "Details"] [:th "Links"]]
           (for [entry (sort-by :ref @report)]
             [:tr
              [:td (:ref entry)]
              [:td (:id entry)]
              [:td (or (:name entry) "")]
              [:td (name (:type entry))]
              [:td
               (cond
                 (= (:type entry) :modified-properties)
                 [:div {:class "changes"}
                  (for [{:keys [key new-value old-value]} (:changes entry)]
                    [:div (str (name key) ": " old-value " -> " new-value)])]
                 (= (:type entry) :modified-geom)
                 (str "\"" (:ref entry) "\" ;; " (:id entry)))]
              [:td
               (when (= (:type entry) :modified-geom)
                 [:a {:href (str (:ref entry) ".html") :target "_blank"} "diff"])
               " "
               [:a {:href (str "http://localhost:7077/route/edit/" (:id entry)) :target "_blank"} "edit"]
               " "
               (when (:website entry)
                 [:a {:href (:website entry) :target "_blank"} "pss"])
               " "
               [:a {:href (str "https://osm.org/relation/" (:id entry)) :target "_blank"} "osm"]
               " "
               [:a {:href (str "http://localhost:7077/view/osm/history/relation/" (:id entry)) :target "_blank"} "history"]]])]]]))))
  (println "[DONE]"))
