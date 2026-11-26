(ns osm-pss-integration.job.qa
  "QA jobs - consistency checks across the different data sources this repo
  pulls together (website scrape / baznikamp api / osm extract). Not part
  of the main extraction pipeline, run manually when something looks off.
  Convention: every trace line reporting a single problem found (an item
  requiring attention, not a plain info/count line) must start with
  \"[ISSUE] \", followed by a short [SHOUTING CASE] tag naming the specific
  problem (e.g. \"[LINK MISMATCH]\"), so issues are easy to spot and grep in
  job output. Apply this to new checks added to this namespace too."
  (:use
   clj-common.clojure)
  (:require
   [clj-common.as :as as]
   [clj-common.context :as context]
   [clj-common.edn :as edn]
   [clj-common.io :as io]
   [clj-common.localfs :as fs]
   [clj-common.path :as path]
   [clj-common.view :as view]

   [osm-pss-integration.job.pss :as pss]))

(def tmosme-commit-message
  "work on osm.wiki/Serbia/Projekti/Održavanje_pešačkih_staza_Srbije")

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
                    (str "[ISSUE] [NO WEBSITE TAG] "
                         "<a href=\"" dataset-link "\" target=\"_blank\">" ref "</a>"
                         " "
                         "<a href=\"" "https://osm.org/relation/" (:id relation)
                         "\" target=\"_blank\">" (:id relation) "</a>"
                         "<br/>&nbsp;&nbsp;&nbsp;&nbsp;<a href=\""
                         "https://vanjakom.github.io/trek-mate-osme/editor.html?id=r"
                         (:id relation) "&website=" (url-encode dataset-link)
                         "&commit=" (url-encode tmosme-commit-message)
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
                    (str "[ISSUE] [LINK MISMATCH] "
                         "<a href=\"" dataset-link "\" target=\"_blank\">" ref "</a>"
                         " "
                         "<a href=\"" "https://osm.org/relation/" (:id relation)
                         "\" target=\"_blank\">" (:id relation) "</a>"
                         "<br/>&nbsp;&nbsp;&nbsp;&nbsp;dataset: " dataset-link
                         "<br/>&nbsp;&nbsp;&nbsp;&nbsp;osm: " osm-link
                         "<br/>&nbsp;&nbsp;&nbsp;&nbsp;<a href=\""
                         "https://vanjakom.github.io/trek-mate-osme/editor.html?id=r"
                         (:id relation) "&website=" (url-encode dataset-link)
                         "&commit=" (url-encode tmosme-commit-message)
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

(defn missing-trails
  "Compares dataset/relation-mapping.tsv (ref -> OSM relation id) against
   osm-pss-extract/relation.edn (loaded via load-pss-osm, the same extract
   both extract-pss-osm and extract-pss-osm-api produce). Reports any
   mapped ref whose relation id isn't actually present in the extract -
   can happen when relation-mapping.tsv was updated with a new/renamed
   relation id after the last extract run, or a mapped relation was
   deleted/merged away on OSM since."
  [job-context]
  (let [configuration (context/configuration job-context)
        osm-pss-integration-path (:osm-pss-integration-path configuration)
        mapping (with-open [is (fs/input-stream
                                (path/child osm-pss-integration-path "relation-mapping.tsv"))]
                  (into
                   []
                   (map
                    (fn [line]
                      (let [[ref relation-id] (clojure.string/split line #"\t")]
                        [ref (as/as-long relation-id)]))
                    (rest (io/input-stream->line-seq is)))))
        osm-dataset (pss/load-pss-osm job-context)
        relation-set (into #{} (keys (:relation osm-dataset)))]
    (context/trace job-context (str "Number of entries in relation-mapping.tsv: " (count mapping)))
    (context/trace job-context (str "Number of relations in osm-pss-extract: " (count relation-set)))
    (let [missing (remove
                   (fn [[ref relation-id]] (contains? relation-set relation-id))
                   mapping)]
      (doseq [[ref relation-id] (sort-by
                                 first
                                 (fn [a b] (pss/id-compare {:id a} {:id b}))
                                 missing)]
        (context/trace
         job-context
         (str "[ISSUE] [MISSING] " ref " "
              "<a href=\"" "https://osm.org/relation/" relation-id
              "\" target=\"_blank\">" relation-id "</a>")))
      (context/trace
       job-context
       (str "done - total: " (count mapping) ", missing: " (count missing))))))

(defn additional-trails
  "Scans the full geofabrik-serbia-split relation.edn (not the already
   filtered osm-pss-extract) for relations that look PSS-related -
   tag source=pss_staze, or a website tag on the pss.rs domain - but
   aren't in dataset/relation-mapping.tsv yet. Surfaces trails mapped on
   OSM that this repo doesn't know about: a new relation, or an existing
   one that extract-pss-ref-osm-relation-id-mapping's ref-based matching
   missed (e.g. ref tag missing/different from the pss.rs ref). Reads
   the whole Serbia split (46k+ relations) instead of the already-
   extracted osm-pss-extract, but fast enough in practice to run as part
   of run-checks."
  [job-context]
  (let [configuration (context/configuration job-context)
        osm-pss-integration-path (:osm-pss-integration-path configuration)
        geofabrik-serbia-split-path (:geofabrik-serbia-split-path configuration)
        mapped-relation-set (with-open [is (fs/input-stream
                                            (path/child osm-pss-integration-path "relation-mapping.tsv"))]
                              (into
                               #{}
                               (map
                                (comp as/as-long second #(.split % "\t"))
                                (rest (io/input-stream->line-seq is)))))
        candidate-seq (with-open [is (fs/input-stream
                                      (path/child geofabrik-serbia-split-path "relation.edn"))]
                        (doall
                         (filter
                          (fn [relation]
                            (or
                             (= (get-in relation [:tags "source"]) "pss_staze")
                             (when-let [website (get-in relation [:tags "website"])]
                               (clojure.string/includes? website "pss.rs"))))
                          (edn/input-stream->seq is))))]
    (context/trace job-context (str "Number of candidate relations found: " (count candidate-seq)))
    (let [additional (remove #(contains? mapped-relation-set (:id %)) candidate-seq)]
      (doseq [relation (sort-by :id additional)]
        (context/trace
         job-context
         (str "[ISSUE] [ADDITIONAL] " (get-in relation [:tags "ref"]) " "
              "<a href=\"" "https://osm.org/relation/" (:id relation)
              "\" target=\"_blank\">" (:id relation) "</a>"
              "<br/>&nbsp;&nbsp;&nbsp;&nbsp;ref: " (get-in relation [:tags "ref"])
              "<br/>&nbsp;&nbsp;&nbsp;&nbsp;website: " (get-in relation [:tags "website"])
              "<br/>&nbsp;&nbsp;&nbsp;&nbsp;operator: " (get-in relation [:tags "operator"])
              "<br/>&nbsp;&nbsp;&nbsp;&nbsp;source: " (get-in relation [:tags "source"]))))
      (context/trace
       job-context
       (str "done - candidates: " (count candidate-seq) ", additional: " (count additional))))))

(defn run-checks
  "Runs all qa checks in this namespace against the same job-context/
   configuration, one after another."
  [job-context]
  (compare-link-with-osm-extract job-context)
  (missing-trails job-context)
  (additional-trails job-context))
