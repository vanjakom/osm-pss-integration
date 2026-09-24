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
;; todo temporary fix production to be able to verify changes
(def trails-production-path ["Users" "vanja" "projects" "pss-map-v1"
                             "history" "trails.20240603.geojson"
                             ;;"history" "trails.temp.geojson"                             
                             ;;"dataset" "trails.geojson"
                             ])
(def relation-mapping-path ["Users" "vanja" "projects" "osm-pss-integration"
                            "dataset" "relation-mapping.tsv"])
(def gpx-root-path ["Users" "vanja" "projects" "osm-pss-integration" "dataset"
                    "pss.rs" "routes"])

;; todo reset on each iteration, put pss ref in
(def ignore
  #{})

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

  (let [production (with-open [is (fs/input-stream trails-production-path)]
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
                    geom-changed (not (= production-geom new-geom))]
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
                    (swap! report conj {:type (if geom-changed :modified-both :modified-properties)
                                        :ref ref :id osm-relation-id
                                        :name (get new-properties :name) :changes (vec changes)
                                        :website (get new-properties :website)})))
                (when geom-changed
                  (context/trace context (str "[MODIFIED_GEOM] \"" ref "\" ;; " osm-relation-id))
                  (when-not properties-changed
                    (swap! report conj {:type :modified-geom :ref ref :id osm-relation-id
                                        :name (get new-properties :name)
                                        :website (get new-properties :website)}))
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
                    (#{:modified-properties :modified-both} (:type entry))
                    [:div {:class "changes"}
                     (for [{:keys [key new-value old-value]} (:changes entry)]
                       [:div (str (name key) ": " old-value " -> " new-value)])]
                    (= (:type entry) :modified-geom)
                    (str "\"" (:ref entry) "\" ;; " (:id entry)))]
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
