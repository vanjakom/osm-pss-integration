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

   [clj-geo.import.osm :as osm]
   [clj-geo.osm.dataset :as dataset]
   [clj-geo.osm.histset :as histset]))

(def active-pipeline nil)
(def relation-histset nil)

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
   11098411
   (channel-provider :node-in)
   (channel-provider :way-in)
   (channel-provider :relation-in)
   (channel-provider :capture))
    
  (pipeline/capture-var-go
   (context/wrap-scope context "capture")
   (channel-provider :capture)
   (var relation-histset))
  (alter-var-root #'active-pipeline (constantly (channel-provider))))

#_(alter-var-root #'relation-histset (constantly (first relation-histset)))



(defn relation-geometry [dataset relation-id]
  (let [relation (get-in dataset [:relation relation-id])]
    (map
     (fn [way-id]
       (let [way (get-in dataset [:way way-id])
             location-seq (map
                           (fn [node-id]
                             (let [node (get-in dataset [:node node-id])]
                               [(:longitude node) (:latitude node)]))
                           (:nodes way))]
         {
          :id way-id
          :locations location-seq}))
     (map :id (filter #(= (:type %) :way)(:members relation))))))

(relation-geometry
 (dataset/dataset-at-t relation-histset 1589456615000)
 11098411)

(get-in
 (dataset/dataset-at-t relation-histset 1589456615000)
 [:relation 11098411])

(get-in
 (dataset/dataset-at-t relation-histset 1755384833000)
 [:relation 11098411])

(mapcat
 (fn [way]
   (mapcat
    #(:nodes %)
    way))
 (map
  #(get-in relation-histset [:way %])
  [1266454584]))

(let [relation-id 11098411
      histset relation-histset
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
             (println "geometry change at:" timestamp "changeset:" changeset)
             (conj
              version-seq
              {
               :timestamp timestamp
               :changeset changeset
               :geometry geometry}))
           version-seq)
         ))
     )
   []
   changeset-seq))

;; * geometry change at: 1589456615000 changeset: 85199612
;; * geometry change at: 1589456680000 changeset: 85199672
;; geometry change at: 1589457025000 changeset: 85200044
;; geometry change at: 1601498598000 changeset: 91788058
;; geometry change at: 1601895164000 changeset: 91979833
;; geometry change at: 1614340431000 changeset: 100048150
;; geometry change at: 1625088892000 changeset: 107229537
;; * geometry change at: 1656056874000 changeset: 122788903
;; geometry change at: 1687963583000 changeset: 137882922
;; geometry change at: 1688053743000 changeset: 137924430
;; geometry change at: 1706272566000 changeset: 146708459
;; geometry change at: 1706275780000 changeset: 146710235
;; geometry change at: 1706275880000 changeset: 146710283
;; * geometry change at: 1706276113000 changeset: 146710433
;; geometry change at: 1706834897000 changeset: 146966343
;; * geometry change at: 1711451098000 changeset: 149171216
;; geometry change at: 1715609144000 changeset: 151269061
;; * geometry change at: 1716669784000 changeset: 151827874
;; * geometry change at: 1755384833000 changeset: 170544123


(first (second (first (:relation relation-histset))))
(first (second (first (:way relation-histset))))

(histset/debug-histset relation-histset)

(doseq [version (get-in relation-histset [:relation 11098411])]
  (println (:version version) (:timestamp version)))

;; 1 1589456615000
;; 2 1589456680000
;; 3 1656056874000
;; 4 1706276113000
;; 5 1711451098000
;; 6 1716669784000
;; 7 1755384833000

(run!
 println
 (sort
  (into
   #{}
   (mapcat
    (fn [version-seq]
      (map
       #(:timestamp %)
       version-seq))
    (vals (:way relation-histset))))))

;; 1572886370000
;; 1589447746000

;; 1589457025000
;; 1601498598000
;; 1601895164000
;; 1625088892000

;; 1687963583000
;; 1688053743000
;; ->1706276113000
;; 1706834897000
;; ->1711451098000
;; 1715609144000
;; 1755384833000

(run!
 println
 (sort
  (into
   #{}
   (mapcat
    (fn [version-seq]
      (map
       #(str (:timestamp %) " " (:changeset %))
       version-seq))
    (vals (:node relation-histset))))))

1559590172000
*1572886370000
*1589447746000
*1589457025000
1614340431000
*1687963583000
*1688053743000
1706272566000
1706275780000
1706275880000
*1706276113000
*1711451098000
*1715609144000
*1755384833000
