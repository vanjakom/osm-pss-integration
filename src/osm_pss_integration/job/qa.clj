(ns osm-pss-integration.job.qa
  "QA jobs - consistency checks across the different data sources this repo
  pulls together (website scrape / baznikamp api / osm extract). Not part
  of the main extraction pipeline, run manually when something looks off."
  (:use
   clj-common.clojure)
  (:require
   [clj-common.context :as context]
   [clj-common.edn :as edn]
   [clj-common.localfs :as fs]
   [clj-common.path :as path]
   [clj-common.view :as view]

   [osm-pss-integration.job.pss :as pss]))

(defn compare-link-with-osm-extract
  "Compares :link in dataset/pss.rs/dataset.edn against each matched OSM
   relation's website tag (loaded via osm-pss-extract, same as
   extract-pss-stats). Reports, per ref: no matching OSM relation yet
   (not mapped), OSM relation has no website tag, or the two links
   disagree (site slug renamed/trashed since the relation mapping tsv was
   last refreshed, or vice versa)."
  [job-context]
  (let [configuration (context/configuration job-context)
        osm-pss-integration-path (:osm-pss-integration-path configuration)
        routes (with-open [is (fs/input-stream
                                (path/child osm-pss-integration-path "pss.rs" "dataset.edn"))]
                 (edn/read-object is))
        osm-dataset (pss/load-pss-osm job-context)
        relation-seq (vals (:relation osm-dataset))
        relation-by-ref (view/seq->map #(get-in % [:tags "ref"]) relation-seq)]
    (context/trace job-context (str "Number of routes in dataset.edn: " (count routes)))
    (context/trace job-context (str "Number of OSM relations: " (count relation-seq)))
    (let [[not-mapped no-website mismatch ok]
          (reduce
           (fn [[not-mapped no-website mismatch ok] [ref route]]
             (let [relation (get relation-by-ref ref)
                   osm-link (get-in relation [:tags "website"])
                   dataset-link (:link route)]
               (cond
                 (nil? relation)
                 ;; not mapped is the expected/common case, counted but
                 ;; not worth a trace line per ref
                 [(inc not-mapped) no-website mismatch ok]

                 (nil? osm-link)
                 (do
                   (context/trace
                    job-context
                    (str "[NO WEBSITE TAG] "
                         "<a href=\"" dataset-link "\" target=\"_blank\">" ref "</a>"
                         " "
                         "<a href=\"" "https://osm.org/relation/" (:id relation)
                         "\" target=\"_blank\">" (:id relation) "</a>"
                         "<br/>&nbsp;&nbsp;&nbsp;&nbsp;<a href=\""
                         "https://vanjakom.github.io/trek-mate-osme/editor.html?id=r"
                         (:id relation) "&website=" (url-encode dataset-link)
                         "\" target=\"_blank\">trek-mate osme fix</a>"))
                   [not-mapped (inc no-website) mismatch ok])

                 (not= osm-link dataset-link)
                 (do
                   ;; job page renders trace lines raw into [:div line]
                   ;; via hiccup with no escaping - a literal \n/\t would
                   ;; just collapse to a space in the browser (default
                   ;; html whitespace handling), so use <br/> instead
                   (context/trace
                    job-context
                    (str "[LINK MISMATCH] "
                         "<a href=\"" dataset-link "\" target=\"_blank\">" ref "</a>"
                         " "
                         "<a href=\"" "https://osm.org/relation/" (:id relation)
                         "\" target=\"_blank\">" (:id relation) "</a>"
                         "<br/>&nbsp;&nbsp;&nbsp;&nbsp;dataset: " dataset-link
                         "<br/>&nbsp;&nbsp;&nbsp;&nbsp;osm: " osm-link
                         "<br/>&nbsp;&nbsp;&nbsp;&nbsp;<a href=\""
                         "https://vanjakom.github.io/trek-mate-osme/editor.html?id=r"
                         (:id relation) "&website=" (url-encode dataset-link)
                         "\" target=\"_blank\">trek-mate osme fix</a>"))
                   [not-mapped no-website (inc mismatch) ok])

                 :else
                 [not-mapped no-website mismatch (inc ok)])))
           [0 0 0 0]
           ;; id-compare expects route-like maps with :id, not bare ref
           ;; strings, so wrap each side for the comparator
           (sort-by first (fn [a b] (pss/id-compare {:id a} {:id b})) routes))]
      (context/trace
       job-context
       (str
        "done - ok: " ok
        ", not mapped: " not-mapped
        ", no website tag: " no-website
        ", link mismatch: " mismatch)))))
