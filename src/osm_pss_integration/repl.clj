(ns osm-pss-integration.repl
  (:require
   [clj-common.edn :as edn]
   [clj-common.io :as io]
   [clj-common.json :as json]
   [clj-common.localfs :as fs]
   [clj-common.path :as path]))

(def root-path ["Users" "vanja" "projects" "osm-pss-integration"])

(with-open [is (fs/input-stream (path/child root-path "dataset" "pss-dataset.edn"))]
  (let [dataset (edn/read is)]
    (doseq [[club trails] (sort-by first (group-by :drustvo (vals dataset)))]
      (println club)
      (doseq [trail trails]
        (println "\t" (:id trail) (:title trail))))))
