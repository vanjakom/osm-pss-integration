(ns osm-pss-integration.repl
  (:require
   [clj-common.edn :as edn]
   [clj-common.io :as io]
   [clj-common.json :as json]
   [clj-common.localfs :as fs]
   [clj-common.path :as path]

   [clj-scheduler.core :as core]

   [osm-pss-integration.job.history :as history]))

(def root-path ["Users" "vanja" "projects" "osm-pss-integration"])

(defn submit-relation-history-job [relation-id]
  (core/job-sumbit
   (core/job-create
    (str "relation-history-" relation-id)
    {:relation-id relation-id}
    (var history/debug-relation-history-job))))

(throw (new Exception "Prevent execution of debug"))


;; 20260920 debug
;; https://openstreetmap.org/relation/11305864
(submit-relation-history-job 11305864)

#_(history/debug-relation-history-repl 11098411)

#_(with-open [is (fs/input-stream (path/child root-path "dataset" "pss-dataset.edn"))]
    (let [dataset (edn/read is)]
      (doseq [[club trails] (sort-by first (group-by :drustvo (vals dataset)))]
        (println club)
        (doseq [trail trails]
          (println "\t" (:id trail) (:title trail))))))

;; extracts .histset + renders .html with one colored layer per geometry
;; version to dataset-local/osm-pss-debug/<relation-id>.{histset,html}
#_(history/debug-relation-history-repl 11098411)
