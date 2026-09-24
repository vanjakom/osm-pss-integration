(ns osm-pss-integration.job.report
  (:use
   clj-common.clojure)
  (:require
   [clojure.string :as string]

   [clj-common.as :as as]
   [clj-common.context :as context]
   [clj-common.edn :as edn]
   [clj-common.io :as io]
   [clj-common.localfs :as fs]
   [clj-common.path :as path]))

(defn- id->category
  "0 normal trails, 1 transversals ( T- prefix ), 2 E paths, matches
  registar.md / trail-lst.md sort convention"
  [id]
  (cond
    (.startsWith id "T-") 1
    (re-matches #"E\d.*" id) 2
    :else 0))

(defn- id->numeric-key
  "Parses dash separated segments of id ( after stripping leading letters )
  into [number suffix] pairs so trails sort numerically, not
  lexicographically ( \"4-4-1\" before \"4-27-1\", \"E7-12\" before
  \"E7-12a\" )"
  [id]
  (let [rest (string/replace id #"^[A-Za-z]*-?" "")]
    (vec
     (map
      (fn [part]
        (let [[_ digits suffix] (re-matches #"(\d*)(.*)" part)]
          [(if (empty? digits) 0 (as/as-long digits)) suffix]))
      (string/split rest #"-")))))

(defn- id->sort-key [id]
  [(id->category id) (id->numeric-key id)])

(defn create-trail-list
  "Reads pss-dataset.edn and writes trail-lst.md, a space aligned id /
  planina / naziv listing, sorted normal trails first, then transversals,
  then E paths"
  [job-context]
  (let [configuration (context/configuration job-context)
        osm-pss-integration-path (:osm-pss-integration-path configuration)
        trail-seq (with-open [is (fs/input-stream
                                   (path/child osm-pss-integration-path "pss-dataset.edn"))]
                    (sort-by
                     #(id->sort-key (:id %))
                     (vals (edn/read is))))
        id-width (+ 2 (apply max (map #(count (:id %)) trail-seq)))
        planina-width (+ 2 (apply max (map #(count (or (:planina %) "")) trail-seq)))
        row-format (str "%-" id-width "s%-" planina-width "s%s")]
    (with-open [os (fs/output-stream (path/child osm-pss-integration-path "trail-lst.md"))]
      (io/write-string
       os
       (str
        "# PSS trail list\n\n"
        "```\n"
        (format row-format "id" "planina" "naziv") "\n"
        (apply str (repeat (dec id-width) "-")) " "
        (apply str (repeat (dec planina-width) "-")) " -----\n"
        (string/join
         "\n"
         (map
          #(format row-format (:id %) (or (:planina %) "") (:title %))
          trail-seq))
        "\n```\n")))
    (context/trace job-context (str "trail-lst.md created, " (count trail-seq) " trails"))))
