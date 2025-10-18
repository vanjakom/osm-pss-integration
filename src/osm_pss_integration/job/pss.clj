(ns osm-pss-integration.job.pss
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

;; concept
;; when new geofabrik serbia dump is downloaded and split run
;; pss extract
;; stats on pss extract
;; validation on pss extract

;; once validation is passed extract of maps will be triggered
;; ( map generate jobs assumes that extracted dataset is valid )
;; if validation could not pass for some of trails add it ot ignore
;; list which will be respected with map generate jobs

;; legacy from old trek-mate times
(def active-pipeline nil)

;; additional notes, not related to osm integration
;; to be discussed with pss working group

(def note-map
  {
   ;; trekovi ponovo postavljeni <20221210
   ;; "4-48-3" "20221026 gpx link postoji ali ne moze da se skine"
   ;; "4-49-3" "20221026 gpx link postoji ali ne moze da se skine"
   ;; "4-48-2" "20221026 gpx link postoji ali ne moze da se skine"
   ;; "4-4-2" "20221026 gpx link postoji ali ne moze da se skine"

   ;; earlier notes, go over, see what is not in osm, push to osm or up
   
   "3-3-2" "malo poklapanja sa unešenim putevima, snimci i tragovi ne pomazu"
   ;; staza nema gpx
   ;; "2-8-2" "rudnik, prosli deo ture do Velikog Sturca, postoje dva puta direktno na Veliki i preko Malog i Srednjeg, malo problematicno u pocetku"
   "4-45-3" "gpx je problematičan, deluje da je kružna staza"
   "4-47-3" "malo poklapanja sa putevima i tragovima, dugo nije markirana"
   "4-40-1" "kretanje železničkom prugom kroz tunele?"
   "4-31-9" "gpx problematičan, dosta odstupanja"
   
   ;; "2-16-1" "dosta odstupanje, staza nije markirana 20200722, srednjeno 20221213"


   "E7-10-11" "20250323 mislim da je stara spojena verzija 10 i 11. preskocio u mapiranju, cimao slobodana"
   "E7-10-4" "20250323 segment E7-10, pitao slobodana, preskocio u mapiranju"
   "E7-10-5" "20250323 segment E7-10, pitao slobodana, preskocio u mapiranju"
   "E7-10-6" "20250323 segment E7-10, pitao slobodana, preskocio u mapiranju"

   "E7-11-1" "20250323 segment E7-11, preskocio u mapiranju za sada, proveriti sa slobodanom"
   "E7-11-2" "20250323 segment E7-11, preskocio u mapiranju za sada, proveriti sa slobodanom"
   "E7-11-3" "20250323 segment E7-11, preskocio u mapiranju za sada, proveriti sa slobodanom"
   "E7-11-4" "20250323 segment E7-11, preskocio u mapiranju za sada, proveriti sa slobodanom"
   "E7-11-5" "20250323 segment E7-11, preskocio u mapiranju za sada, proveriti sa slobodanom"

   "E7-12-1" "20250323 segment E7-12, preskocio u mapiranju za sada, proveriti sa slobodanom"
   "E7-12-2" "20250323 segment E7-12, preskocio u mapiranju za sada, proveriti sa slobodanom"
   "E7-12-3" "20250323 segment E7-12, preskocio u mapiranju za sada, proveriti sa slobodanom"
   "E7-12-4" "20250323 segment E7-12, preskocio u mapiranju za sada, proveriti sa slobodanom"
   "E7-12-5" "20250323 segment E7-12, preskocio u mapiranju za sada, proveriti sa slobodanom"
   "E7-12-6" "20250323 segment E7-12, preskocio u mapiranju za sada, proveriti sa slobodanom"
   })


(defn id->region
  [id]
  (let [[region club number] (.split id "-")]
    (cond
      (= region "1") "Vojvodina"
      (= region "2") "Šumadija"
      (= region "3") "Zapadna Srbija"
      (= region "4") "Istočna Srbija"
      (= region "5") "Jugozapadna Srbija"
      (= region "6") "Kopaoničko-Toplička regija"
      (= region "7") "Jugoistočna Srbija"
      :else "nepoznat")))

(defn id-compare
  [route1 route2]
  ;; support for E paths, example: E7-6
  (let [id1 (:id route1)
        id2 (:id route2)]
    (try
      (cond
        (and (.startsWith id1 "E") (.startsWith id2 "E"))
        ;; hotfix for E7-12a
        (let [[road1 segment1] (.split (.replace (.substring id1 1) "a" "") "-")
              [road2 segment2] (.split (.replace (.substring id2 1) "a" "") "-")]
          (compare
           (+ (* (as/as-long road1) 100) (as/as-long segment1))
           (+ (* (as/as-long road2) 100) (as/as-long segment2))))

        (.startsWith id1 "E")
        -1

        (.startsWith id2 "E")
        1

        :else
        (let [[region1 mountain1 number1] (.split id1 "-")
              [region2 mountain2 number2] (.split id2 "-")
              ;; hotfix for transversals, example: T-3-13
              region1 (if (= "T" region1) 0 region1)
              region2 (if (= "T" region2) 0 region2)]
          (compare
           (+ (* (as/as-long region1) 10000) (* (as/as-long mountain1) 100) (as/as-long number1))
           (+ (* (as/as-long region2) 10000) (* (as/as-long mountain2) 100) (as/as-long number2)))))
      (catch Exception e
        (println "[EXCEPTION] Unable to compare: " id1 " with " id2)
        (throw (ex-info "Id compare problem" {:route1 route1 :route2 route2} e))))))

#_(id-compare {:id "5-3-23"} {:id "5-24-1"}) ;; -1
#_(id-compare {:id "T-3-13"} {:id "5-24-1"});; -1

(defn render-route
  "prepares hiccup html for route"
  [route relation note]
  (let [id (:id route)
        ;; todo
        note (or
              (get-in relation [:tags "note"])
              note)]
    [:tr
     [:td {:style "border: 1px solid black; padding: 5px; width: 50px;"}
      id]
     [:td {:style "border: 1px solid black; padding: 5px; width: 150px;"}
      (id->region id)]
     [:td {:style "border: 1px solid black; padding: 5px; width: 150px;"}
      (:planina route)]
     [:td {:style "border: 1px solid black; padding: 5px; width: 100px; text-align: center;"}
      (:drustvo route)]
     [:td {:style "border: 1px solid black; padding: 5px; width: 100px; text-align: center;"}
      (:uredjenost route)]
     [:td {:style "border: 1px solid black; padding: 5px; width: 600px;"}
      (:title route )
      [:br]
      (if-let [osm-id (:id relation)]
        (get-in relation [:tags "name"])
        (str
         "type = route<br>"
         "route = hiking<br>"
         (cond
           (.startsWith id "E")
           "network = iwn<br>"
           (.startsWith id "T")
           "network = nwn<br>"
           :else
           "network = rwn<br>")
         "source = pss_staze<br>"
         "operator = " (:drustvo route) "<br>"
         "ref = " id "<br>"
         "osmc:symbol = red:red_round::" (last (.split id "-")) ":white<br>"
         "name = " (text/latin->cyrillic (:title route))"<br>"
         "name:sr = " (text/latin->cyrillic (:title route)) "<br>"
         "name:sr-Latn = " (:title route) "<br>"
         "website = " (:link route) "<br>"
         "ascent = <br>"
         "descent = <br>"
         "distance =  km<br>"
         "roundtrip = <br>"))]
     
     [:td {:style "border: 1px solid black; padding: 5px; width: 40px; text-align: center;"}
      [:a {:href (:link route) :target "_blank"} "pss"]]
     [:td {:style "border: 1px solid black; padding: 5px; width: 80px; text-align: center;"}
      (if-let [osm-id (:id relation)]
        (list
          [:a {
               :href (str "https://openstreetmap.org/relation/" osm-id)
               :target "_blank"} "osm"]
          [:br]
          [:a {
               :href (str "http://localhost:7077/view/osm/history/relation/" osm-id)
               :target "_blank"} "history"]
          [:br]
          [:a {
               :href (str "http://localhost:7077/route/edit/" osm-id)
               :target "_blank"} "order edit"]          
          [:br]
          [:a {
               :href (str "http://localhost:7077/projects/pss/compare/" (get-in relation [:tags "ref"]))
               :target "_blank"} "compare"]          
          [:br]
          [:a {
               :href (str
                      "https://www.openstreetmap.org/edit?editor=id"
                      "&relation=" osm-id
                      "&#gpx=https%3A%2F%2Fraw.githubusercontent.com%2Fvanjakom%2Fosm-pss-integration%2Fmaster%2Fdataset%2Fpss.rs%2Froutes%2F"
                      id ".geojson")

               ;; looks like editor is not working with file:// urls
               #_(str
                "https://www.openstreetmap.org/edit?editor=id"
                "&relation=" osm-id
                "&#gpx=" (url-encode (str
                                      "file://"
                                      (path/path->string
                                       (path/child
                                        dataset-path
                                        "routes"
                                        (str id ".gpx"))))))

               
               :target "_blank"} "iD edit"]
          [:br]
          [:a {
               :href (str "http://level0.osmz.ru/?url=relation/" osm-id)
               :target "_blank"} "level0"]
          [:br]          
          osm-id)
        [:a {
             :href (str
                    "https://www.openstreetmap.org/edit?editor=id"
                    "&#gpx=https%3A%2F%2Fraw.githubusercontent.com%2Fvanjakom%2Fosm-pss-integration%2Fmaster%2Fdataset%2Fpss.rs%2Froutes%2F"
                    id ".geojson")
             :target "_blank"} "iD edit"])]
     [:td {:style "border: 1px solid black; padding: 5px; width: 100px;"}
      note]]))

;; todo
;; extract jobs from download routines
(def dataset-path (path/child env/projects-path "osm-pss-integration" "dataset" "pss.rs"))

(defn crawl-website [context]
  ;; process https://pss.rs/planinarski-objekti-i-tereni/tereni/?tip=planinarski-putevi
  ;; download routes list and supporting files
  (context/trace context "downloading trails and supporting files")
  (with-open [is (http/get-as-stream "https://pss.rs/planinarski-objekti-i-tereni/tereni/?tip=planinarski-putevi")]
    (let [terrains-obj (json/read-keyworded
                        (.replace
                         (.trim
                          (first
                           (filter
                            #(.contains % "var terrainsObj =")
                            (io/input-stream->line-seq is))))
                         "var terrainsObj = " ""))
          georegions-geojson-url (:geojsonPath terrains-obj)
          georegions (:geoRegions terrains-obj)
          map-european-path-url (:pss_evropski_pesacki_putevi_mapa terrains-obj)
          map-european-path-serbia-url (:pss_evropski_pesacki_putevi_srbija_mapa terrains-obj)
          types (:types terrains-obj)
          terrains (:terrains terrains-obj)
          posts (:posts terrains-obj)]
      
      ;; write regions geojson
      (with-open [is (http/get-as-stream georegions-geojson-url)
                  os (fs/output-stream (path/child dataset-path "regions.geojson"))]
        (io/copy-input-to-output-stream is os))

      ;; write region description json
      (with-open [os (fs/output-stream (path/child dataset-path "regions.json"))]
        (json/write-to-stream georegions os))

      ;; write european paths map
      (with-open [is (http/get-as-stream map-european-path-url)
                  os (fs/output-stream (path/child dataset-path "mapa-evropski-pesacki-putevi.jpg"))]
        (io/copy-input-to-output-stream is os))

      ;; write european paths serbia map
      (with-open [is (http/get-as-stream map-european-path-serbia-url)
                  os (fs/output-stream (path/child dataset-path "mapa-evropski-pesacki-putevi-u-srbiji.jpg"))]
        (io/copy-input-to-output-stream is os))

      ;; write objects
      (with-open [os (fs/output-stream (path/child dataset-path "types.json"))]
        (json/write-pretty-print types (io/output-stream->writer os)))
      (with-open [os (fs/output-stream (path/child dataset-path "terrains.json"))]
        (json/write-pretty-print terrains (io/output-stream->writer os)))
      (with-open [os (fs/output-stream (path/child dataset-path "posts.json"))]
        (json/write-pretty-print posts (io/output-stream->writer os)))))
  (context/trace context "trails list downloaded from pss website")
  
  ;; process https://pss.rs/planinarski-objekti-i-tereni/tereni/?tip=planinarske-transverzale
  ;; download routes list only
  (context/trace context "downloading transversals")
  (with-open [is (http/get-as-stream "https://pss.rs/planinarski-objekti-i-tereni/tereni/?tip=planinarske-transverzale")]
    (let [terrains-obj (json/read-keyworded
                        (.replace
                         (.trim
                          (first
                           (filter
                            #(.contains % "var terrainsObj =")
                            (io/input-stream->line-seq is))))
                         "var terrainsObj = " ""))
          posts (:posts terrains-obj)]

      (with-open [os (fs/output-stream (path/child dataset-path "posts-transversal.json"))]
        (json/write-pretty-print posts (io/output-stream->writer os)))))
  (context/trace context "transversals list downloaded from pss website")

  ;; process https://pss.rs/planinarski-objekti-i-tereni/tereni/?tip=evropski-pesacki-putevi-u-srbiji
  ;; download routes list only
  (context/trace context "download e paths")
  (with-open [is (http/get-as-stream "https://pss.rs/planinarski-objekti-i-tereni/tereni/?tip=evropski-pesacki-putevi-u-srbiji")]
    (let [terrains-obj (json/read-keyworded
                        (.replace
                         (.trim
                          (first
                           (filter
                            #(.contains % "var terrainsObj =")
                            (io/input-stream->line-seq is))))
                         "var terrainsObj = " ""))
          posts (:posts terrains-obj)]

      (with-open [os (fs/output-stream (path/child dataset-path "posts-e-paths.json"))]
        (json/write-pretty-print posts (io/output-stream->writer os)))))
  (context/trace context "E paths list downloaded from pss website")

  ;; download route info and gpx if exists, supports restart
  (context/trace context "incrementa downloading route info and gpx if exists")
  (let [posts (concat
               (with-open [is (fs/input-stream (path/child dataset-path "posts.json"))]
                 (json/read-keyworded is))
               (with-open [is (fs/input-stream (path/child dataset-path "posts-transversal.json"))]
                 (json/read-keyworded is))
               (with-open [is (fs/input-stream (path/child dataset-path "posts-e-paths.json"))]
                 (json/read-keyworded is)))]
    (doseq [post posts]
      (let [post (update-in post [:postmeta] #(view/seq->map :label %))
            postid (:ID post)
            title (:title post)
            link (:permalink post)
            oznaka (get-in post [:postmeta "Oznaka" :value])
            info-path (path/child dataset-path "routes" (str oznaka ".json"))
            content-path (path/child dataset-path "routes" (str oznaka ".html"))
            gpx-path (path/child dataset-path "routes" (str oznaka ".gpx"))]
        (context/trace context (str "processing " oznaka "-" title))
        #_(println "\t" postid)
        #_(println "\t" link)
        ;; depending on use case either try all without gpx or info file
        ;; in case of gpx most htmls will change because of news
        (if (not (empty? oznaka))
          (if (not
               (fs/exists? gpx-path)
               ;; (fs/exists? info-path)
               )
            (do
              (context/trace context "\tdownloading post ...")
              (let [content (io/input-stream->string (http/get-as-stream link))
                    gpx (if-let [gpx (second
                                      (re-find
                                       #"<tr><th>GPX</th><td><a href=\"(.+?)\""
                                       content))]
                          (.trim gpx))
                    region (when-let [region (second
                                              (re-find
                                               #"<tr><th>Region</th><td>(.+?)</td>"
                                               content))]
                             (.trim region))
                    uredjenost (when-let [uredjenost (second
                                                      (re-find
                                                       #"<tr><th>Uređenost</th><td>(.+?)</td>"
                                                       content))]
                                 (.trim uredjenost))
                    planina (or
                             (when-let [planina (second
                                                 (re-find
                                                  #"<tr><th>Planina/predeo</th><td>(.+?)</td>"
                                                  content))]
                               (.trim planina))
                             (when-let [planine (second
                                                 (re-find
                                                  #"<tr><th>Planine/predeli</th><td>(.+?)</td>"
                                                  content))]
                               (.trim planine)))
                    info {
                          :id oznaka
                          :gpx gpx
                          :region region
                          :title title
                          :uredjenost uredjenost
                          :planina planina
                          :link link}]
                (with-open [os (fs/output-stream info-path)]
                  (json/write-pretty-print info (io/output-stream->writer os)))
                (with-open [os (fs/output-stream content-path)]
                  (io/write-string os content))
                (when (not (empty? gpx))
                  (if (not (fs/exists? gpx-path))
                    (do
                      (context/trace context "\tdownloading gpx ...")
                      (if-let [is (http/get-as-stream gpx)]
                        (with-open [os (fs/output-stream gpx-path)]
                          (io/copy-input-to-output-stream is os))
                        (context/trace context "\tdownload failed ...")))
                    (context/trace context "\tallready downloaded ..."))))
              
              ;; old version, before 20201222
              #_(let [pattern (java.util.regex.Pattern/compile "var terrainsObj = (\\{.+?(?=\\};)\\})")
                      matcher (.matcher
                               pattern
                               (io/input-stream->string (http/get-as-stream link)))]
                  (.find matcher)
                  (let [entry (update-in
                               (json/read-keyworded (.group matcher 1))
                               [:post :postmeta]
                               #(view/seq->map :label %))]
                    (with-open [os (fs/output-stream info-path)]
                      (json/write-to-stream entry os))
                    (let [gpx-link (get-in entry [:post :postmeta "GPX" :value])]
                      (when (not (empty? gpx-link))
                        (println "\tdownloading gpx ...")
                        (with-open [os (fs/output-stream gpx-path)]
                          (io/copy-input-to-output-stream
                           (http/get-as-stream gpx-link)
                           os))))))
              (Thread/sleep 3000))
            (context/trace context "\tpost already downloaded ..."))
          (context/trace context (str "[ERROR] ref not extracted for:" link)))))
    (context/trace context "info and gpx download finished")))

;; todo various stats, integrate into prepare
#_(first posts)
#_(get clubs "T-3-2")
#_(filter
 (fn [post]
   (let [post (update-in post [:postmeta] #(view/seq->map :label %))]
     (= (get-in post [:postmeta "Oznaka" :value])  "T-3-2")))
 posts)

;; find references to E7 and E4
#_(doseq [post posts]
  (let [post (update-in post [:postmeta] #(view/seq->map :label %))
        postid (:ID post)
        title (:title post)
        link (:permalink post)
        oznaka (get-in post [:postmeta "Oznaka" :value])
        info-path (path/child dataset-path "routes" (str oznaka ".json"))
        content-path (path/child dataset-path "routes" (str oznaka ".html"))
        gpx-path (path/child dataset-path "routes" (str oznaka ".gpx"))]
    (if (fs/exists? content-path)
      (let [content (with-open [is (fs/input-stream content-path)]
                      (io/input-stream->string is))]
        (if
            (or
             (.contains content "E-7")
             (.contains content "E7"))
          (do
            (println oznaka "-" title)
            (println "\t" link)))))))

;; find references to zapis
#_(doseq [post posts]
  (let [post (update-in post [:postmeta] #(view/seq->map :label %))
        postid (:ID post)
        title (:title post)
        link (:permalink post)
        oznaka (get-in post [:postmeta "Oznaka" :value])
        info-path (path/child dataset-path "routes" (str oznaka ".json"))
        content-path (path/child dataset-path "routes" (str oznaka ".html"))
        gpx-path (path/child dataset-path "routes" (str oznaka ".gpx"))]
    (if (fs/exists? content-path)
      (let [content (.toLowerCase
                     (with-open [is (fs/input-stream content-path)]
                       (io/input-stream->string is)))]
        (if
            (or
             (.contains content "zapis ")
             (.contains content " zapis"))
          (do
            (println oznaka "-" title)
            (println "\t" link)))))))

;; per route stats
#_(doseq [post posts]
  (let [post (update-in post [:postmeta] #(view/seq->map :label %))
        title (:title post)
        oznaka (get-in post [:postmeta "Oznaka" :value])
        info-path (path/child dataset-path "routes" (str oznaka ".json"))
        gpx-path (path/child dataset-path "routes" (str oznaka ".gpx"))]
    (println (if (fs/exists? gpx-path) "Y" "N") "\t" oznaka "\t" title)))

#_(count
 (into
  #{}
  (map
   #(get-in % [:postmeta "Oznaka" :value])
   (map
    (fn [post]
      (update-in post [:postmeta] #(view/seq->map :label %)))
    posts)))) ; 198

;; 4 posts have same marks

;; null club routes
#_(filter
 (fn [post]
   (let [post (update-in post [:postmeta] #(view/seq->map :label %))]
     (nil? (get-in post [:postmeta "Društvo/klub" :value 0 :post_title])))
   )
 posts)


;; stats per club does it has track
#_(doseq [[club [sum y n]] (reverse
                      (sort-by
                       (fn [[club [sum y n]]] sum)
                       (reduce
                        (fn [state post]
                          (let [post (update-in post [:postmeta] #(view/seq->map :label %))
                                title (:title post)
                                club (get-in post [:postmeta "Društvo/klub" :value 0 :post_title])
                                oznaka (get-in post [:postmeta "Oznaka" :value])
                                info-path (path/child dataset-path "routes" (str oznaka ".json"))
                                gpx-path (path/child dataset-path "routes" (str oznaka ".gpx"))]
                            (let [[sum y n] (get state club [0 0 0])]
                              (if (fs/exists? gpx-path)
                                (assoc state club [(inc sum) (inc y) n])
                                (assoc state club [(inc sum) y (inc n)])))))
                        {}
                        posts)))]
  (println (reduce str club (repeatedly (- 30 (count club)) (constantly " "))) sum "\t" y "\t" n))

;; Oštra čuka PD                  30 	 27 	 3
;; Ljukten PSD                    25 	 0 	 25
;; Kukavica PSK                   14 	 0 	 14
;; Železničar PK Niš              14 	 14 	 0
;; Pobeda PK                      9 	 0 	 9
;; Železničar PSK Kraljevo        8 	 0 	 8
;; Kraljevo PAK                   7 	 0 	 7
;; Gornjak PD                     6 	 6 	 0
;; Železničar 2006 PK Vranje      6 	 0 	 6
;; Bukulja PD                     6 	 1 	 5
;; Golija PD                      6 	 0 	 6
;; Brđanka PSK                    6 	 4 	 2
;; Vršačka kula PSD               5 	 0 	 5
;; Suva Planina PD                4 	 0 	 4
;; Preslap PD                     4 	 4 	 0
;; Mosor PAK                      4 	 4 	 0
;; Cer PSD                        4 	 0 	 4
;; Vukan PK                       4 	 4 	 0
;; Avala PSK                      4 	 4 	 0
;; Gučevo PK                      3 	 3 	 0
;; Dragan Radosavljević OPSD      3 	 3 	 0
;; Vilina vodica PD               3 	 3 	 0
;; Vrbica PK                      3 	 0 	 3
;; Gora PEK                       2 	 2 	 0
;; Ozren PK                       2 	 0 	 2
;; Žeželj PD                      2 	 1 	 1
;; Ljuba Nešić PSD                2 	 2 	 0
;;                                2 	 1 	 1
;; Železničar PD Beograd          1 	 1 	 0
;; Sirig PSK                      1 	 0 	 1
;; Kopaonik PSD                   1 	 0 	 1
;; Magleš PSD                     1 	 1 	 0
;; PS Vojvodine                   1 	 0 	 1
;; Javorak  PK                    1 	 1 	 0
;; Dr. Laza Marković PD           1 	 0 	 1
;; PTT POSK                       1 	 0 	 1
;; Železničar Indjija PK          1 	 0 	 1
;; Čivija PAK                     1 	 0 	 1
;; Spartak PSK                    1 	 0 	 1
;; Zubrova PD                     1 	 1 	 0
;; Vlasina SPK                    1 	 1 	 0
;; Jastrebac PSK                  1 	 1 	 0


;; count afer extraction to check extraction
#_(reduce
 (fn [count [club [sum y n]]]
   (+ count sum))
 0
 (reverse
  (sort-by
   (fn [[club [sum y n]]] sum)
   (reduce
    (fn [state post]
      (let [post (update-in post [:postmeta] #(view/seq->map :label %))
            title (:title post)
            club (get-in post [:postmeta "Društvo/klub" :value 0 :post_title])
            oznaka (get-in post [:postmeta "Oznaka" :value])
            info-path (path/child dataset-path "routes" (str oznaka ".json"))
            gpx-path (path/child dataset-path "routes" (str oznaka ".gpx"))]
        (let [[sum y n] (get state club [0 0 0])]
          (if (fs/exists? gpx-path)
            (assoc state club [(inc sum) (inc y) n])
            (assoc state club [(inc sum) y (inc n)])))))
    {}
    posts))))


;; usefull for single post
#_(let [pattern (java.util.regex.Pattern/compile "var terrainsObj = (\\{.+?(?=\\};)\\})")
      matcher (.matcher
               pattern
               (io/input-stream->string
                (http/get-as-stream
                 "https://pss.rs/terenipp/banja-badanja-banja-crniljevo/")))]
  (.find matcher)
  (def post (let [entry (json/read-keyworded (.group matcher 1))]
              (update-in
               entry
               [:post :postmeta]
               #(view/seq->map :label %)))))

#_(do
  (require 'clj-common.debug)
  (clj-common.debug/run-debug-server))

(defn prepare-pss-dataset
  [job-context]
  (let [configuration (core/context-configuration job-context)
        dataset-path (:pss-dataset-path configuration)
        osm-pss-integration-path (:osm-pss-integration-path configuration)
        posts (concat
               (with-open [is (fs/input-stream (path/child dataset-path "posts.json"))]
                 (json/read-keyworded is))
               (with-open [is (fs/input-stream (path/child dataset-path "posts-transversal.json"))]
                 (json/read-keyworded is))
               (with-open [is (fs/input-stream (path/child dataset-path "posts-e-paths.json"))]
                 (json/read-keyworded is)))
        ;; 20220509 - club was not extracted into info path initially
        ;; create lookup from posts
        clubs (reduce
               (fn [clubs post]
                 (let [post (update-in post [:postmeta] #(view/seq->map :label %))]
                   (assoc
                    clubs
                    (get-in post [:postmeta "Oznaka" :value])
                    (or
                     (get-in post [:postmeta "Društvo/klub" :value 0 :post_title])
                     ;; support transverzals
                     (get-in post [:postmeta "Društvo" :value 0 :post_title])))))
               {}
               posts)]

    ;; 20250330
    ;; Number of posts: 413
    ;; From: 413 has gpx: 293 and: 120 doesn't have
    
    #_(count posts)
    ;; 323 on 20221026
    ;; 322 on 20220731
    ;; 321 on 20220620
    ;; 311 on 20220517, e paths added
    ;; 280 on 20220410
    ;; 278 on 20220321, transversals added
    ;; 260 on 20220308
    ;; 252 on 20210908
    ;; 251 on 20210629
    ;; 242 on 20210311
    ;; 233 on 20201223
    
    #_(count (into #{} (vals clubs)))
    ;; 52 20220620
    ;; 52 20220517

    (core/context-report job-context (str "Number of posts: " (count posts)))
    (core/context-report job-context (str "Number of clubs: " (count (into #{} (vals clubs)))))
    (let [[count has-gpx no-gpx] (reduce
                                  (fn [[sum y n] post]
                                    (let [post (update-in post [:postmeta] #(view/seq->map :label %))
                                          title (:title post)
                                          oznaka (get-in post [:postmeta "Oznaka" :value])
                                          info-path (path/child dataset-path "routes" (str oznaka ".json"))
                                          gpx-path (path/child dataset-path "routes" (str oznaka ".gpx"))]
                                      (if (fs/exists? gpx-path)
                                        [(inc sum) (inc y) n]
                                        [(inc sum) y (inc n)])))
                                  [0 0 0]
                                  posts)]
      (core/context-report job-context (str "From: " count " has gpx: " has-gpx " and: " no-gpx " doesn't have")))
    ;; number of posts, have gpx, do not have gpx
    ;; 20200720 [214 102 112]
    ;; 20200422 [202 89 113]

    (core/context-report job-context "preparing routes")
    (let [routes (reduce
                  (fn [routes info-path]
                    (core/context-report
                     job-context
                     (str "processing" (path/path->string info-path)))
                    (let [gpx-path (let [gpx-path (path/child
                                                   (path/parent info-path)
                                                   (.replace (last info-path) ".json" ".gpx"))]
                                     (when (fs/exists? gpx-path)
                                       gpx-path))
                          track (when gpx-path
                                  (with-open [is (fs/input-stream gpx-path)] (gpx/read-track-gpx is)))
                          location-seq (when track
                                         (apply concat (:track-seq track)))
                          first-location (when track
                                           (first location-seq))
                          info (with-open [is (fs/input-stream info-path)] (json/read-keyworded is))]
                      (assoc
                       routes
                       (:id info)
                       {
                        :id (:id info)
                        :gpx-path gpx-path
                        :info-path info-path
                        :title (:title info)
                        :link (:link info)
                        :location first-location
                        :uredjenost (:uredjenost info)
                        :region (:region info)
                        :planina (:planina info)
                        ;; 20220509 - club was not extracted into info path initially
                        :drustvo (get clubs (:id info))}))
                    )
                  {}
                  (filter
                   #(.endsWith (last %) ".json")
                   (fs/list (path/child dataset-path "routes"))))]
      (core/context-report job-context "routes prepared")
      (core/context-report job-context (str "Number of routes: " (count routes)))

      ;; 20250330
      ;; write geojson files per each route
      (doseq [route (vals routes)]
        (println "processing" (:id route))
        (when (:gpx-path route)
          (let [gpx-path (:gpx-path route)
                geojson-path (path/child
                              (path/parent gpx-path)
                              (.replace (path/name gpx-path) ".gpx" ".geojson"))
                track (when gpx-path
                        (with-open [is (fs/input-stream gpx-path)] (gpx/read-track-gpx is)))
                location-seq (when track
                               (apply concat (:track-seq track)))
                wpt-seq (:wpt-seq track)]
            (println "processing " geojson-path)
            (with-open [os (fs/output-stream geojson-path)]
              (json/write-to-stream
               (geojson/geojson
                (concat
                 [(geojson/line-string location-seq)]
                 (map
                  geojson/location->point
                  wpt-seq)
                 (map-indexed
                  (fn [index location]
                    (update-in
                     (geojson/location->point location)
                     [:properties :name]
                     (constantly (str index))))
                  (take-nth 100 location-seq))))
               os)))))
      
      ;; todo store on disk to decouple jobs
      (with-open [os (fs/output-stream (path/child osm-pss-integration-path "pss-dataset.edn"))]
        (edn/write-pprint-object os routes)))))

;; load single route info
#_(def a
  (with-open [is (fs/input-stream (path/child dataset-path "routes" "4-4-3.json"))]
    (json/read-keyworded is)))

(defn extract-pss-ref-osm-relation-id-mapping
  "Uses serbia split to go over relations and extract pairs. File is kept in git.
  Could be manually altered if needed in future, currently relies on source=pss_staze"
  [job-context]
  (let [configuration (core/context-configuration job-context)
        context (core/context-pipeline-adapter job-context)
        channel-provider (pipeline/create-channels-provider)
        resource-controller (pipeline/create-trace-resource-controller context)
        serbia-extract-path (:geofabrik-serbia-split-path configuration)
        osm-pss-integration-path (:osm-pss-integration-path configuration)
        timestamp (System/currentTimeMillis)
        relation-seq (atom '())]
    (pipeline/read-edn-go
     (context/wrap-scope context "read-relation")
     (path/child serbia-extract-path "relation.edn")
     (channel-provider :filter-hiking))

    (pipeline/transducer-stream-go
     (context/wrap-scope context "filter-hiking")
     (channel-provider :filter-hiking)
     (filter
      (fn [relation]
        (and
         (= (get-in relation [:tags "type"]) "route")
         (= (get-in relation [:tags "route"]) "hiking"))))
     (channel-provider :filter-pss))

    (pipeline/transducer-stream-go
     (context/wrap-scope context "filter-pss")
     (channel-provider :filter-pss)
     (filter
      (fn [relation]
        (and
         (= (get-in relation [:tags "source"]) "pss_staze")
         (some? (get-in relation [:tags "ref"])))))
     (channel-provider :capture))
    
    (pipeline/capture-atom-seq-go
     (context/wrap-scope context "capture")
     (channel-provider :capture)
     relation-seq)
    (alter-var-root #'active-pipeline (constantly (channel-provider)))
    (core/wait-pipeline-job context)

    (with-open [os (fs/output-stream (path/child osm-pss-integration-path
                                                 "relation-mapping.tsv"))]
      (io/write-line os (str "pss ref\tosm relation id"))
      (doseq [relation (sort-by
                        #(get-in % [:tags "ref"])
                        (deref relation-seq))]
        (io/write-line os (str (get-in relation [:tags "ref"]) "\t" (:id relation)))))))

#_(core/job-sumbit
   (core/job-create
    "extract-pss-ref-osm-relation-id-mapping"
    {
     :osm-pss-integration-path ["Users" "vanja" "projects" "osm-pss-integration"]
     :geofabrik-serbia-split-path ["Users" "vanja" "dataset-local" "geofabrik-serbia-split"]}
    extract-pss-ref-osm-relation-id-mapping))

(defn extract-pss-osm
  "Uses relation-mapping.tsv to extract relations, ways and nodes needed"
  [job-context]
  (let [configuration (core/context-configuration job-context)
        context (core/context-pipeline-adapter job-context)
        channel-provider (pipeline/create-channels-provider)
        resource-controller (pipeline/create-trace-resource-controller context)
        osm-pss-integration-path (:osm-pss-integration-path configuration)
        serbia-extract-path (:geofabrik-serbia-split-path configuration)
        osm-pss-extract-path (:osm-pss-extract-path configuration)
        timestamp (System/currentTimeMillis)]
    (core/context-report job-context "loading relations to extract")
    (let [relation-set (with-open [is (fs/input-stream
                                       (path/child
                                        osm-pss-integration-path
                                        "relation-mapping.tsv"))]
                         (into #{}
                               (map
                                (comp
                                 as/as-long
                                 second
                                 #(.split % "\t"))
                                (drop 1
                                      (io/input-stream->line-seq is)))))]
      (core/context-report job-context (str "loaded " (count relation-set) " relations"))
      (core/context-report job-context "running pipeline")
      (pipeline/read-edn-go
       (context/wrap-scope context "read-node")
       resource-controller
       (path/child serbia-extract-path "node.edn")
       (channel-provider :node-in))

      (pipeline/read-edn-go
       (context/wrap-scope context "read-way")
       resource-controller
       (path/child serbia-extract-path "way.edn")
       (channel-provider :way-in))

      (pipeline/read-edn-go
       (context/wrap-scope context "read-relation")
       resource-controller
       (path/child serbia-extract-path "relation.edn")
       (channel-provider :relation-in))

      (osm/extract-recursive-from-split
       (context/wrap-scope context "extract")
       #{}
       #{}
       relation-set
       (channel-provider :node-in)
       (channel-provider :way-in)
       (channel-provider :relation-in)

       (channel-provider :node-out)
       (channel-provider :way-out)
       (channel-provider :relation-out))
      
      (pipeline/write-edn-go
       (context/wrap-scope context "write-node")
       resource-controller
       (path/child osm-pss-extract-path "node.edn")
       (channel-provider :node-out))

      (pipeline/write-edn-go
       (context/wrap-scope context "write-way")
       resource-controller
       (path/child osm-pss-extract-path "way.edn")
       (channel-provider :way-out))

      (pipeline/write-edn-go
       (context/wrap-scope context "write-relation")
       resource-controller
       (path/child osm-pss-extract-path "relation.edn")
       (channel-provider :relation-out))
      
      (alter-var-root #'active-pipeline (constantly (channel-provider)))

      (core/wait-pipeline-job context))))

#_(core/job-sumbit
   (core/job-create
    "extract-pss-osm"
    {
     :osm-pss-integration-path ["Users" "vanja" "projects" "osm-pss-integration"]
     :geofabrik-serbia-split-path ["Users" "vanja" "dataset-local" "geofabrik-serbia-split"]
     :osm-pss-extract-path ["Users" "vanja" "dataset-local" "osm-pss-extract"]}
    extract-pss-osm))

(defn load-pss-osm
  "Loads previosly extracted osm-pss-extract"
  [job-context]
  (let [configuration (core/context-configuration job-context)
        context (core/context-pipeline-adapter job-context)
        channel-provider (pipeline/create-channels-provider)
        resource-controller (pipeline/create-trace-resource-controller context)
        osm-pss-extract-path (:osm-pss-extract-path configuration)]
  (pipeline/read-edn-go
   (context/wrap-scope context "read-node")
   resource-controller
   (path/child osm-pss-extract-path "node.edn")
   (channel-provider :node))

  (pipeline/read-edn-go
   (context/wrap-scope context "read-way")
   resource-controller
   (path/child osm-pss-extract-path "way.edn")
   (channel-provider :way))

  (pipeline/read-edn-go
   (context/wrap-scope context "read-relation")
   resource-controller
   (path/child osm-pss-extract-path "relation.edn")
   (channel-provider :relation))

  (pipeline/reducing-go
   (context/wrap-scope context "node-dataset")
   (channel-provider :node)
   (fn
     ([] {})
     ([state node]
      (dataset/dataset-append-node state node))
     ([state] state))
   (channel-provider :node-dataset))
  
  (pipeline/pass-last-go
   (context/wrap-scope context "wait-last-node")
   (channel-provider :node-dataset)
   (channel-provider :node-dataset-final))
  
  (pipeline/reducing-go
   (context/wrap-scope context "way-dataset")
   (channel-provider :way)
   (fn
     ([] {})
     ([state way]
      (dataset/dataset-append-way state way))
     ([state] state))
   (channel-provider :way-dataset))

  (pipeline/pass-last-go
   (context/wrap-scope context "wait-last-way")
   (channel-provider :way-dataset)
   (channel-provider :way-dataset-final))
  
  (pipeline/reducing-go
   (context/wrap-scope context "relation-dataset")
   (channel-provider :relation)
   (fn
     ([] {})
     ([state relation]
      (dataset/dataset-append-relation state relation))
     ([state] state))
   (channel-provider :relation-dataset))

  (pipeline/pass-last-go
   (context/wrap-scope context "wait-last-relation")
   (channel-provider :relation-dataset)
   (channel-provider :relation-dataset-final))

  (pipeline/funnel-go
   (context/wrap-scope context "funnel-dataset")
   [
    (channel-provider :node-dataset-final)
    (channel-provider :way-dataset-final)
    (channel-provider :relation-dataset-final)]
   (channel-provider :dataset))

  (pipeline/reducing-go
   (context/wrap-scope context "dataset")
   (channel-provider :dataset)
   (fn
     ([] {})
     ([state dataset]
      (dataset/merge-datasets state dataset))
     ([state] state))
   (channel-provider :wait-last))

  (pipeline/pass-last-go
   (context/wrap-scope context "wait-last")
   (channel-provider :wait-last)
   (channel-provider :capture))

  (alter-var-root #'active-pipeline (constantly (channel-provider)))

  ;; returns pss dataset
  (pipeline/wait-pipeline-output (channel-provider :capture))))

(defn extract-pss-stats [job-context]
  (let [configuration (core/context-configuration job-context)
        integration-git-path (:osm-pss-integration-path configuration)
        timestamp (System/currentTimeMillis)]
    (core/context-report job-context "loading osm-pss-extract")
    ;; overpass for relations
    ;; (overpass/query-string "relation[type=route][route=hiking](area:3601741311);")
    (let [dataset (load-pss-osm job-context)
          relation-seq (vals (:relation dataset))
          relation-map (view/seq->map #(get-in % [:tags "ref"]) relation-seq)]
      (core/context-report
       job-context
       (str "Number of PSS relations in OSM: " (count relation-seq)))
      ;; 202 20220629
      ;; 200 20220624

      (core/context-report
       job-context
       (str "Number of unique PSS relations in OSM: " (count relation-map)))
      
      #_(count relation-seq)
      ;; 383 20220629
      ;; 372 20220624
      ;; 356 20220531
      ;; 350 20220417
      ;; 348 20220410 updated to use all relations not just ones with source=pss_staze
      ;; 163 20220319
      ;; 155 20220307
      ;; 141

      ;; read prepared dataset from website
      (let [routes (with-open [is (fs/input-stream (path/child integration-git-path "pss-dataset.edn"))]
                     (edn/read-object is))]        
        (core/context-report job-context "Creating osm-pss-integration git files")
        ;; 20220817
        ;; table for data verification for PSS working group
        (with-open [os (fs/output-stream (path/child integration-git-path "osm-status.tsv"))]
          (io/write-line os
                         (str
                          "\""
                          (clojure.string/join
                           "\"\t\""
                           ["ref" "id" "name" "website" "waymarkedtrails" "source" "note"])
                          "\""))
          (let [pss-set (into #{} (keys routes))]
            (run!
             (fn [relation]
               (let [ref (get-in relation [:tags "ref"])]
                 (io/write-line os
                                (str
                                 "\""
                                 (clojure.string/join
                                  "\"\t\""
                                  [
                                   ref
                                   (get relation :id)
                                   (get-in relation [:tags "name"])
                                   (get-in relation [:tags "website"])
                                   (str "https://hiking.waymarkedtrails.org/#route?id="
                                        (get relation :id))
                                   (get-in relation [:tags "source"])
                                   (cond
                                     (some? (get-in relation [:tags "note"]))
                                     (get-in relation [:tags "note"])
                                     
                                     (some? (get note-map ref))
                                     (str "(internal) " (get note-map ref))

                                     :else
                                     nil)])
                                 "\""))))
             (sort-by
              #(get-in % [:tags "ref"])
              (filter
               #(contains? pss-set (get-in % [:tags "ref"]))
               relation-seq)))))

        ;; 20231210 tags for all pss hiking relations, to make tracking easier
        (with-open [os (fs/output-stream (path/child integration-git-path "osm-relation-tags.txt"))]
          (let [pss-set (into #{} (keys routes))]
            (run!
             (fn [relation]
               (let [ref (get-in relation [:tags "ref"])]
                 (io/write-line os (str (get relation :id)))
                 (doseq [[tag value] (sort-by first (get relation :tags))]
                   (io/write-line os (str "\t" tag " = " value)))
                 (io/write-line os "")))
             (sort-by
              #(get-in % [:tags "ref"])
              (filter
               #(contains? pss-set (get-in % [:tags "ref"]))
               relation-seq)))))

        (let [missing-routes (filter
                              #(nil? (get relation-map (:id %)))
                              (vals routes))]
          (core/context-report
           job-context
           (str "Number of missing routes: " (count missing-routes)))
          (with-open [os (fs/output-stream (path/child integration-git-path "missing-trails.tsv"))]
            (io/write-line os
                           (str
                            "\""
                            (clojure.string/join
                             "\"\t\""
                             ["ref" "name" "website" "note"])
                            "\""))
            (run!
             (fn [route]
               (io/write-line os
                              (str
                               "\""
                               (clojure.string/join
                                "\"\t\""
                                [
                                 (:id route)
                                 (:title route)
                                 (:link route)
                                 (get note-map (:id route))])
                               "\"")))
             (sort-by :id missing-routes))))


        ;; prepare wiki table
        ;; data should be from OSM, different tool should be develop to prepare diff
        ;; between data provided by pss.rs vs data in OSM
        (with-open [os (fs/output-stream (path/child integration-git-path "wiki-status.md"))]
          (binding [*out* (new java.io.OutputStreamWriter os)]
            (println "== Trenutno stanje ==")
            (println "Tabela se mašinski generiše na osnovu OSM baze\n\n")
            (println "Staze dostupne unutar OSM baze:\n")
            (println "{| border=1")
            (println "! scope=\"col\" | ref")
            (println "! scope=\"col\" | region")
            (println "! scope=\"col\" | planina")
            (println "! scope=\"col\" | uređenost")
            (println "! scope=\"col\" | naziv")
            (println "! scope=\"col\" | link")
            (println "! scope=\"col\" | osm")
            (println "! scope=\"col\" | note")
            (doseq [route (sort
                           #(id-compare %1 %2)
                           (filter
                            #(some? (get relation-map (:id %)))
                            (vals routes)))]
              (let [id (:id route)
                    relation (get relation-map id)]
                (do
                  (println "|-")
                  (println "|" (get-in relation [:tags "ref"]))
                  (println "|" (id->region id))
                  (println "|" (or (:planina route) ""))
                  (println "|" (or (:uredjenost route) ""))
                  (println "|" (get-in relation [:tags "name:sr"]))
                  (println "|" (str "[" (get-in relation [:tags "website"]) " pss]"))
                  (println "|" (if-let [relation-id (:id relation)]
                                 (str "{{relation|" relation-id "}}")
                                 ""))
                  (println "|" (if-let [note (get (:tags relation) "note")]
                                 note
                                 (if-let [note (get note-map id)]
                                   note
                                   ""))))))
            (println "|}")

            (println "Staze koje je moguće mapirati:\n")
            (println "{| border=1")
            (println "! scope=\"col\" | ref")
            (println "! scope=\"col\" | region")
            (println "! scope=\"col\" | planina")
            (println "! scope=\"col\" | uređenost")
            (println "! scope=\"col\" | naziv")
            (println "! scope=\"col\" | link")
            (println "! scope=\"col\" | note")
            (doseq [route (sort
                           #(id-compare %1 %2)
                           (filter
                            #(and
                              (nil? (get relation-map (:id %)))
                              (some? (get % :gpx-path)))
                            (vals routes)))]
              (let [id (:id route)
                    relation (get relation-map id)]
                (do
                  (println "|-")
                  (println "|" id)
                  (println "|" (id->region id))
                  (println "|" (:planina route))
                  (println "|" (or (:uredjenost route) ""))
                  (println "|" (:title route))
                  (println "|" (str "[" (:link route) " pss]"))
                  (println "|" (if-let [note (get (:osm relation) "note")]
                                 note
                                 (if-let [note (get note-map id)]
                                   note
                                   ""))))))
            (println "|}")))

        ;; extract connected set
        (let [connected-set (into #{}
                                  (map
                                   :id
                                   (filter
                                    #(second (osm/check-connected?
                                              (:way dataset) %))
                                    (vals (:relation dataset)))))]
          (doseq [relation (vals (:relation dataset))]
            (let [ref (get-in relation [:tags "ref"])]
              (if (contains? connected-set (:id relation))
                (core/context-report job-context (str ref " connected"))
                (core/context-report
                 job-context
                 (str "[WARN]" ref " not connected")))))

          (with-open [os (fs/output-stream (path/child integration-git-path "osm-state.html"))]
            (io/write-string
             os
             (let [[mapped-routes routes-with-gpx rest-of-routes]
                   (reduce
                    (fn [[mapped gpx rest-of] route]
                      (if (some? (get relation-map (:id route)))
                        [(conj mapped route) gpx rest-of]
                        (if (some? (get route :gpx-path))
                          [mapped (conj gpx route) rest-of]
                          [mapped gpx (conj rest-of route)])))
                    [[] [] []]
                    (vals routes))
                   complete-routes (filter #(contains? connected-set
                                                       (:id (get relation-map (:id %))))
                                           mapped-routes)
                   not-complete-routes (filter #(not (contains? connected-set
                                                                (:id (get relation-map (:id %)))))
                                               mapped-routes)]
               (hiccup/html
                   [:html
                    [:head
                     [:meta {:charset "UTF-8"}]]
                    [:body {:style "font-family:arial;"}
                     [:br]
                     [:div (str "rute koje poseduju gpx a nisu mapirane (" (count routes-with-gpx) ")")]
                     [:br]
                     [:table {:style "border-collapse:collapse;"}
                      (map
                       #(render-route % (get relation-map (:id %)) (get note-map (:id %)))
                       (sort
                        #(id-compare %1 %2)
                        routes-with-gpx))]
                     [:br]

                     [:div (str "mapirane rute koje nisu kompletne (" (count not-complete-routes)  ")")]
                     [:br]
                     [:table {:style "border-collapse:collapse;"}
                      (map
                       #(render-route % (get relation-map (:id %)) (get note-map (:id %)))
                       (sort
                        #(id-compare %1 %2)
                        not-complete-routes))]
                     [:br]

                     [:div (str "mapirane rute koje su kompletne (" (count complete-routes)  ")")]
                     [:br]
                     [:table {:style "border-collapse:collapse;"}
                      (map
                       #(render-route % (get relation-map (:id %)) (get note-map (:id %)))
                       (sort
                        #(id-compare %1 %2)
                        complete-routes))]
                     [:br]
                  
                     [:div (str "ostale rute (" (count rest-of-routes) ")")]
                     [:br]
                     [:table {:style "border-collapse:collapse;"}
                      (map
                       #(render-route % (get relation-map (:id %)) (get note-map (:id %)))
                       (sort
                        #(id-compare %1 %2)
                        rest-of-routes))]]]))))))

      (core/context-report
       job-context
       (str
        "View report <a href='file://"
        (path/path->string (path/child integration-git-path "osm-state.html"))
        "'>here</a>"))
      
      (core/context-report job-context "Job finished"))))

#_(core/job-sumbit
   (core/job-create
    "extract-pss-stats"
    {
     :osm-pss-extract-path ["Users" "vanja" "dataset-local" "osm-pss-extract"]}
    extract-pss-stats))


(defn extract-geojson-combined-map
  "Creates GeoJSON which contains all ways that belong to PSS hiking trail. Each
  way contains information to which trails belongs."
  [job-context]
  (let [configuration (core/context-configuration job-context)
        context (core/context-pipeline-adapter job-context)
        channel-provider (pipeline/create-channels-provider)
        resource-controller (pipeline/create-trace-resource-controller context)
        osm-pss-integration-path (:osm-pss-integration-path configuration)
        osm-pss-extract-path (:osm-pss-extract-path configuration)
        timestamp (System/currentTimeMillis)]
    (pipeline/read-edn-go
     (context/wrap-scope context "read-node")
     resource-controller
     (path/child osm-pss-extract-path "node.edn")
     (channel-provider :node-in))

    (pipeline/read-edn-go
     (context/wrap-scope context "read-way")
     resource-controller
     (path/child osm-pss-extract-path "way.edn")
     (channel-provider :way-in))

    (pipeline/read-edn-go
     (context/wrap-scope context "read-relation")
     resource-controller
     (path/child osm-pss-extract-path "relation.edn")
     (channel-provider :relation-in))

    #_(pipeline/create-lookup-go
       (context/wrap-scope context "relation-lookup")
       (channel-provider :relation-in)
       :id
       identity
       (channel-provider :relation-lookup-in))

    (pipeline/reducing-go
     (context/wrap-scope context "way-lookup")
     (channel-provider :relation-in)
     (fn
       ([] {})
       ([state relation]
        (let [network (get-in relation [:tags "network"])
              trail (get-in relation [:tags "ref"])]
          (reduce
           (fn [state member]
             (assoc
              state
              (:id member)
              (if-let [way (get state (:id member))]
                {
                 :networks
                 (conj (or (get way :networks) #{}) network)
                 :trails
                 (conj (or (get way :trails) #{}) trail)}
                {
                 :networks #{network}
                 :trails #{trail}})))
           state
           (filter #(= (:type %) :way) (:members relation)))))
       ([state] state))
     (channel-provider :way-lookup-drain))

    (pipeline/drain-go
     (context/wrap-scope context "way-lookup-drain")
     (channel-provider :way-lookup-drain)
     (channel-provider :way-lookup))
    
    (osm/resolve-way-geometry-in-memory-go
     (context/wrap-scope context "resolve-geometry")
     (channel-provider :node-in)
     (channel-provider :way-in)
     (channel-provider :way-out))

    (async/go
      (let [context (context/wrap-scope context "transform")
            way-lookup-in (channel-provider :way-lookup)
            resolved-way-in (channel-provider :way-out)
            feature-out (channel-provider :feature-in)]
        (let [way-lookup (or (async/<! way-lookup-in) {})]
          (loop [way (async/<! resolved-way-in)]
            (context/set-state context "step")
            (when way
              (context/increment-counter context "way-in")
              (if-let [metadata (get way-lookup (:id way))]
                (let [networks (get metadata :networks)
                      width (cond
                              (contains? networks "iwn") 6
                              (contains? networks "nwn") 4
                              (contains? networks "rwn") 2
                              :else 1)]
                  (context/increment-counter context (str "width-" width))
                  (context/increment-counter context "way-out")
                  (context/increment-counter context "lookup-match")
                  (async/>!
                   feature-out
                   (binding [geojson/*style-stroke-width* width
                             geojson/*style-stroke-color* "#FF0000"]
                     (geojson/line-string metadata (:coords way)))))
                (do
                  (context/increment-counter context "way-out")
                  (context/increment-counter context "lookup-mismatch")
                  (async/>!
                   feature-out
                   (binding [geojson/*style-stroke-width* 1
                             geojson/*style-stroke-color* "#FF0000"]
                     (geojson/line-string {} (:coords way))))))
              (recur (async/<! resolved-way-in))))
          (async/close! feature-out)
          (context/set-state context "completion"))))
    
    #_(pipeline/transducer-stream-go
       (context/wrap-scope context "transform")
       (channel-provider :way-out)
       (map (fn [way]
              (geojson/line-string (:coords way))))
       (channel-provider :feature-in))

    (geojson/write-geojson-go
     (context/wrap-scope context "write-geojson")
     (path/child osm-pss-integration-path "combined-map.geojson")
     (channel-provider :feature-in))

    (alter-var-root #'active-pipeline (constantly (channel-provider)))
    
    (core/wait-pipeline-job context)))

#_(core/job-sumbit
 (core/job-create
  "extract-geojson-combined-map"
  {
   :osm-pss-extract-path ["Users" "vanja" "dataset-local" "osm-pss-extract"]
   :osm-pss-integration-path ["Users" "vanja" "projects" "osm-pss-integration"]
   :state-done-node ["pss" "geojson-combined-map"]}
  ;; todo
  extract-geojson-combined-map))

(defn load-pss-extract-as-dataset
  "Loads pss-extract into memory as dataset suitable for analysis and extracts."
  [job-context]
  (let [configuration (core/context-configuration job-context)
        context (core/context-pipeline-adapter job-context)
        channel-provider (pipeline/create-channels-provider)
        resource-controller (pipeline/create-trace-resource-controller context)
        osm-pss-integration-path (:osm-pss-integration-path configuration)
        osm-pss-extract-path (:osm-pss-extract-path configuration)
        timestamp (System/currentTimeMillis)
        pss-dataset (atom {})]
    (pipeline/read-edn-go
     (context/wrap-scope context "read-node")
     resource-controller
     (path/child osm-pss-extract-path "node.edn")
     (channel-provider :node))

    (pipeline/read-edn-go
     (context/wrap-scope context "read-way")
     resource-controller
     (path/child osm-pss-extract-path "way.edn")
     (channel-provider :way))

    (pipeline/read-edn-go
     (context/wrap-scope context "read-relation")
     resource-controller
     (path/child osm-pss-extract-path "relation.edn")
     (channel-provider :relation))

    (pipeline/reducing-go
     (context/wrap-scope context "node-dataset")
     (channel-provider :node)
     (fn
       ([] {})
       ([state node]
        (dataset/dataset-append-node state node))
       ([state] state))
     (channel-provider :node-dataset))
    
    (pipeline/pass-last-go
     (context/wrap-scope context "wait-last-node")
     (channel-provider :node-dataset)
     (channel-provider :node-dataset-final))
    
    (pipeline/reducing-go
     (context/wrap-scope context "way-dataset")
     (channel-provider :way)
     (fn
       ([] {})
       ([state way]
        (dataset/dataset-append-way state way))
       ([state] state))
     (channel-provider :way-dataset))

    (pipeline/pass-last-go
     (context/wrap-scope context "wait-last-way")
     (channel-provider :way-dataset)
     (channel-provider :way-dataset-final))
    
    (pipeline/reducing-go
     (context/wrap-scope context "relation-dataset")
     (channel-provider :relation)
     (fn
       ([] {})
       ([state relation]
        (dataset/dataset-append-relation state relation))
       ([state] state))
     (channel-provider :relation-dataset))

    (pipeline/pass-last-go
     (context/wrap-scope context "wait-last-relation")
     (channel-provider :relation-dataset)
     (channel-provider :relation-dataset-final))

    (pipeline/funnel-go
     (context/wrap-scope context "funnel-dataset")
     [
      (channel-provider :node-dataset-final)
      (channel-provider :way-dataset-final)
      (channel-provider :relation-dataset-final)]
     (channel-provider :dataset))

    (pipeline/reducing-go
     (context/wrap-scope context "dataset")
     (channel-provider :dataset)
     (fn
       ([] {})
       ([state dataset]
        (dataset/merge-datasets state dataset))
       ([state] state))
     (channel-provider :wait-last))

    (pipeline/pass-last-go
     (context/wrap-scope context "wait-last")
     (channel-provider :wait-last)
     (channel-provider :capture))

    (pipeline/capture-atom-go
     (context/wrap-scope context "capture")
     (channel-provider :capture)
     pss-dataset)

    (alter-var-root #'active-pipeline (constantly (channel-provider)))

    (core/wait-pipeline-job context)
    
    (core/context-report
     job-context
     (str "dataset contains " (count (:node (deref pss-dataset))) " nodes"))
    (core/context-report
     job-context
     (str "dataset contains " (count (:way (deref pss-dataset))) " ways"))
    (core/context-report
     job-context
     (str "dataset contains " (count (:relation (deref pss-dataset))) " relations"))

    (deref pss-dataset)))

(defn extract-geojson-per-trail
  "Creates GeoJSON for each trail, containing all trail geometry. If trail is
  not complete multiple features will be extracted"
  [job-context]
  (let [configuration (core/context-configuration job-context)
        context (core/context-pipeline-adapter job-context)
        channel-provider (pipeline/create-channels-provider)
        resource-controller (pipeline/create-trace-resource-controller context)
        osm-pss-integration-path (:osm-pss-integration-path configuration)
        osm-pss-extract-path (:osm-pss-extract-path configuration)
        timestamp (System/currentTimeMillis)]
    (let [dataset (load-pss-extract-as-dataset job-context)]
      (core/context-report
       job-context
       "dataset loaded, writing trails")
      (doseq [relation (vals (:relation dataset))]
        (let [ref (get-in relation [:tags "ref"])]
          (core/context-report
           job-context
           (str "processing: " ref " (" (get relation :id) ")"))
          (with-open [os (fs/output-stream (path/child
                                            osm-pss-integration-path "trails" (str ref ".geojson")))]
            (json/write-pretty-print
             {
              :type "FeatureCollection"
              :properties {}
              :features
              (concat
               ;; ways as lines
               (filter
                some?
                (map
                 (fn [member]
                   (cond
                     (= (:type member) :way)
                     (let [nodes (map
                                  (fn [id]
                                    (let [node (get-in dataset [:nodes id])]
                                      [(as/as-double (:longitude node)) (as/as-double (:latitude node))]))
                                  (:nodes (get-in dataset [:way (:id member)])))]
                       {
                        :type "Feature"
                        :properties {}
                        :geometry {
                                   :type "LineString"
                                   :coordinates nodes}})
                     :else
                     nil))
                 (:members relation))))}
             (io/output-stream->writer os))))))))

(defn extract-geojson-with-trails
  "Creates GeoJSON containing each trail as single feature for each trail, containing all trail geometry. If trail is
  not complete multiple features will be extracted"
  [job-context]
  (let [configuration (core/context-configuration job-context)
        context (core/context-pipeline-adapter job-context)
        channel-provider (pipeline/create-channels-provider)
        resource-controller (pipeline/create-trace-resource-controller context)
        osm-pss-integration-path (:osm-pss-integration-path configuration)
        osm-pss-extract-path (:osm-pss-extract-path configuration)
        timestamp (System/currentTimeMillis)]
    (let [dataset (load-pss-extract-as-dataset job-context)]
      (core/context-report
       job-context
       "dataset loaded, writing trails")
      (with-open [os (fs/output-stream (path/child
                                        osm-pss-integration-path "trails.geojson"))]
        (json/write-pretty-print
         (geojson/geojson
          (doall
           (map
            (fn [relation]
              (let [ref (get-in relation [:tags "ref"])]
                (core/context-report
                 job-context
                 (str "processing: " ref " (" (get relation :id) ")"))
                (binding [geojson/*style-stroke-color* "#FF0000"
                          geojson/*style-stroke-width* (cond
                                                         (.startsWith ref "E")
                                                         6
                                                         (.startsWith ref "T")
                                                         4
                                                         :else
                                                         2)]
                  (geojson/multi-line-string
                   {
                    "ref" ref
                    "osm-relation-id" (get relation :id)
                    "name" (get-in relation [:tags "name"])
                    "operator" (get-in relation [:tags "operator"])
                    "website" (get-in relation [:tags "website"])
                    "network" (get-in relation [:tags "network"])
                    "distance" (get-in relation [:tags "distance"])}
                   (filter
                    some?
                    (map
                     (fn [member]
                       (cond
                         (= (:type member) :way)
                         (map
                          (fn [id]
                            (get-in dataset [:nodes id]))
                          (:nodes (get-in dataset [:way (:id member)])))
                         :else
                         nil))
                     (:members relation)))))))
            (vals (:relation dataset)))))
         (io/output-stream->writer os))))))

(defn create-trails-image
  "Draws trails on top of image generated from tiles"
  [job-context]
  (let [configuration (core/context-configuration job-context)
        context (core/context-pipeline-adapter job-context)
        channel-provider (pipeline/create-channels-provider)
        resource-controller (pipeline/create-trace-resource-controller context)
        osm-pss-extract-path (:osm-pss-extract-path configuration)
        background-image-path (:background-image-path configuration)
        image-path (:image-path configuration)
        [zoom min-tile-x min-tile-y] (:upper-left-tile configuration)
        [zoom max-tile-x max-tile-y] (:lower-right-tile configuration)
        min-x (* min-tile-x 256)
        max-x (+ (* max-tile-x 256) 256)
        min-y (* min-tile-y 256)
        max-y (+ (* max-tile-y 256) 256)
        timestamp (System/currentTimeMillis)]

    (core/context-report job-context "write trails on top of background")
    (let [image (with-open [is (fs/input-stream background-image-path)]
                  (draw/input-stream->image-context is))
          dataset (load-pss-extract-as-dataset job-context)]
      (doseq [relation (vals (:relation dataset))]
        (let [ref (get-in relation [:tags "ref"])
              network (get-in relation [:tags "network"])]
          (core/context-report
           job-context
           (str "processing: " ref " - " network " (" (get relation :id) ")"))
          (doseq [way (filter #(= (:type %) :way) (:members relation))]
            (let [nodes (map
                         (fn [id]
                           (let [node (get-in dataset [:nodes id])]
                             {
                              :longitude (as/as-double (:longitude node))
                              :latitude (as/as-double (:latitude node))}))
                         (:nodes (get-in dataset [:way (:id way)])))
                  points (map (comp
                               (fn [[x y]]
                                 {:x (- x min-x) :y (- y min-y)})
                               (tile-math/zoom-->location->point zoom))
                              nodes)]
              (cond
                (= "iwn" network)
                (draw/draw-polyline image points draw/color-red 8)

                (= "nwn" network)
                (draw/draw-polyline image points draw/color-red 4)

                (= "rwn" network)
                (draw/draw-polyline image points draw/color-red 2)

                (= "lwn" network)
                (draw/draw-polyline image points draw/color-red 2)

                :else
                (draw/draw-polyline image points draw/color-red 2))))))

      (core/context-report job-context "write generated image")
      (with-open [os (fs/output-stream image-path)]
        (draw/write-png-to-stream image os)))))

;; 20250301 prototype diff procedure
;; 20250330 something is wrong with this piece of code, commenting
;; todo
#_(let [original (with-open [is (fs/input-stream
                                 (path/string->path
                                  "/Users/vanja/projects/pss-map-v1/dataset/trails.geojson"))]
                   (:features (json/read-keyworded is)))
        original-ids (into
                      #{}
                      (map
                       (fn [feature]
                         (get-in feature [:properties :ref]))
                       original))
        new (with-open [is (fs/input-stream
                            (path/string->path
                             "/Users/vanja/projects/osm-pss-integration/dataset/trails.geojson"))]
              (:features (json/read-keyworded is)))
        new-ids (into
                 #{}
                 (map
                  (fn [feature]
                    (get-in feature [:properties :ref]))
                  new))]
    (println "count in original:" (count original))
    (println "count in new:" (count new))
    (println "new routes")
    (doseq [feature (filter
                     (fn [feature]
                       (not (contains?
                             original-ids
                             (get-in feature [:properties :ref]))))
                     new)]
      (println "\t"
               (get-in feature [:properties :ref])
               (get-in feature [:properties :name])))
    (println "deleted routes")
    (doseq [feature (filter
                     (fn [feature]
                       (not (contains?
                             new-ids
                             (get-in feature [:properties :ref]))))
                     original)]
      (println "\t"
               (get-in feature [:properties :ref])
               (get-in feature [:properties :name]))))


;; e7 report from various gpx
(defn create-e7-map [context]
  (let [configuration (context/configuration context)
        dataset-git-root-path (get configuration :dataset-git-root-path)
        export-path (get configuration :export-path)]
   (context/trace context (str "creating map for: e7" ))
   (with-open [os (fs/output-stream export-path)]
     (io/write-string
      os
      (map/render-raw
       {}
       [
        (map/tile-layer-osm true)
        (map/tile-layer-bing-satellite false)
        (map/tile-layer-google-satellite false)
        (with-open [is (fs/input-stream (path/child dataset-git-root-path
                                                    "era" "E7 Serbia final.gpx"))]
           (map/tile-overlay-gpx "ERA E7" is true true))
        
        (binding [geojson/*style-stroke-color* geojson/color-red]
          (with-open [is (fs/input-stream (path/child dataset-git-root-path
                                                      "pss.rs" "routes" "E7-1.gpx"))]
            (map/tile-overlay-gpx "pss.rs E7-1" is true false)))
        (binding [geojson/*style-stroke-color* geojson/color-red]
          (with-open [is (fs/input-stream (path/child dataset-git-root-path
                                                      "pss.rs" "routes" "E7-2.gpx"))]
            (map/tile-overlay-gpx "pss.rs E7-2" is true false)))
        (binding [geojson/*style-stroke-color* geojson/color-red]
          (with-open [is (fs/input-stream (path/child dataset-git-root-path
                                                      "pss.rs" "routes" "E7-3.gpx"))]
            (map/tile-overlay-gpx "pss.rs E7-3" is true false)))
        (binding [geojson/*style-stroke-color* geojson/color-red]
          (with-open [is (fs/input-stream (path/child dataset-git-root-path
                                                      "pss.rs" "routes" "E7-4.gpx"))]
            (map/tile-overlay-gpx "pss.rs E7-4" is true false)))
        (binding [geojson/*style-stroke-color* geojson/color-red]
          (with-open [is (fs/input-stream (path/child dataset-git-root-path
                                                      "pss.rs" "routes" "E7-5.gpx"))]
            (map/tile-overlay-gpx "pss.rs E7-5" is true false)))
        (binding [geojson/*style-stroke-color* geojson/color-red]
          (with-open [is (fs/input-stream (path/child dataset-git-root-path
                                                      "pss.rs" "routes" "E7-6.gpx"))]
            (map/tile-overlay-gpx "pss.rs E7-6" is true false)))
        (binding [geojson/*style-stroke-color* geojson/color-red]
          (with-open [is (fs/input-stream (path/child dataset-git-root-path
                                                      "pss.rs" "routes" "E7-7.gpx"))]
            (map/tile-overlay-gpx "pss.rs E7-7" is true false)))
        (binding [geojson/*style-stroke-color* geojson/color-red]
          (with-open [is (fs/input-stream (path/child dataset-git-root-path
                                                      "pss.rs" "routes" "E7-8.gpx"))]
            (map/tile-overlay-gpx "pss.rs E7-8" is true false)))
        (binding [geojson/*style-stroke-color* geojson/color-red]
          (with-open [is (fs/input-stream (path/child dataset-git-root-path
                                                      "pss.rs" "routes" "E7-9.gpx"))]
            (map/tile-overlay-gpx "pss.rs E7-9" is true false)))
        (binding [geojson/*style-stroke-color* geojson/color-red]
          (with-open [is (fs/input-stream (path/child dataset-git-root-path
                                                      "pss.rs" "routes" "E7-10.gpx"))]
            (map/tile-overlay-gpx "pss.rs E7-10" is true false)))
        (binding [geojson/*style-stroke-color* geojson/color-red]
          (with-open [is (fs/input-stream (path/child dataset-git-root-path
                                                      "pss.rs" "routes" "E7-11.gpx"))]
            (map/tile-overlay-gpx "pss.rs E7-11" is true false)))
        (binding [geojson/*style-stroke-color* geojson/color-red]
          (with-open [is (fs/input-stream (path/child dataset-git-root-path
                                                      "pss.rs" "routes" "E7-12.gpx"))]
            (map/tile-overlay-gpx "pss.rs E7-12" is true false)))
        (binding [geojson/*style-stroke-color* geojson/color-red]
          (with-open [is (fs/input-stream (path/child dataset-git-root-path
                                                      "pss.rs" "routes" "E7-13.gpx"))]
            (map/tile-overlay-gpx "pss.rs E7-13" is true false)))
        (binding [geojson/*style-stroke-color* geojson/color-red]
          (with-open [is (fs/input-stream (path/child dataset-git-root-path
                                                      "pss.rs" "routes" "E7-14.gpx"))]
            (map/tile-overlay-gpx "pss.rs E7-14" is true false)))
        (binding [geojson/*style-stroke-color* geojson/color-red]
          (with-open [is (fs/input-stream (path/child dataset-git-root-path
                                                      "pss.rs" "routes" "E7-15.gpx"))]
            (map/tile-overlay-gpx "pss.rs E7-15" is true false)))
        (binding [geojson/*style-stroke-color* geojson/color-red]
          (with-open [is (fs/input-stream (path/child dataset-git-root-path
                                                      "pss.rs" "routes" "E7-16.gpx"))]
            (map/tile-overlay-gpx "pss.rs E7-16" is true false)))
        (binding [geojson/*style-stroke-color* geojson/color-red]
          (with-open [is (fs/input-stream (path/child dataset-git-root-path
                                                      "pss.rs" "routes" "E7-17.gpx"))]
            (map/tile-overlay-gpx "pss.rs E7-17" is true false)))
        (binding [geojson/*style-stroke-color* geojson/color-red]
          (with-open [is (fs/input-stream (path/child dataset-git-root-path
                                                      "pss.rs" "routes" "E7-18.gpx"))]
            (map/tile-overlay-gpx "pss.rs E7-18" is true false)))
        (binding [geojson/*style-stroke-color* geojson/color-red]
          (with-open [is (fs/input-stream (path/child dataset-git-root-path
                                                      "pss.rs" "routes" "E7-19.gpx"))]
            (map/tile-overlay-gpx "pss.rs E7-19" is true false)))
        (binding [geojson/*style-stroke-color* geojson/color-red]
          (with-open [is (fs/input-stream (path/child dataset-git-root-path
                                                      "pss.rs" "routes" "E7-20.gpx"))]
            (map/tile-overlay-gpx "pss.rs E7-20" is true false)))

        ;; problematic E10-11
        (binding [geojson/*style-stroke-color* geojson/color-red]
          (with-open [is (fs/input-stream (path/child dataset-git-root-path
                                                      "pss.rs" "routes" "E7-10-11.gpx"))]
            (map/tile-overlay-gpx "pss.rs E7-10-11" is true false)))

        
        ;; new divided routes
        
        (binding [geojson/*style-stroke-color* geojson/color-green]
          (with-open [is (fs/input-stream (path/child dataset-git-root-path
                                                      "pss.rs" "routes" "E7-10-4.gpx"))]
            (map/tile-overlay-gpx "pss.rs E7-10-4" is true false)))
        (binding [geojson/*style-stroke-color* geojson/color-green]
          (with-open [is (fs/input-stream (path/child dataset-git-root-path
                                                      "pss.rs" "routes" "E7-10-5.gpx"))]
            (map/tile-overlay-gpx "pss.rs E7-10-5" is true false)))
        (binding [geojson/*style-stroke-color* geojson/color-green]
          (with-open [is (fs/input-stream (path/child dataset-git-root-path
                                                      "pss.rs" "routes" "E7-10-6.gpx"))]
            (map/tile-overlay-gpx "pss.rs E7-10-6" is true false)))

        (binding [geojson/*style-stroke-color* geojson/color-green]
          (with-open [is (fs/input-stream (path/child dataset-git-root-path
                                                      "pss.rs" "routes" "E7-11-1.gpx"))]
            (map/tile-overlay-gpx "pss.rs E7-11-1" is true false)))
        (binding [geojson/*style-stroke-color* geojson/color-green]
          (with-open [is (fs/input-stream (path/child dataset-git-root-path
                                                      "pss.rs" "routes" "E7-11-2.gpx"))]
            (map/tile-overlay-gpx "pss.rs E7-11-2" is true false)))
        (binding [geojson/*style-stroke-color* geojson/color-green]
          (with-open [is (fs/input-stream (path/child dataset-git-root-path
                                                      "pss.rs" "routes" "E7-11-3.gpx"))]
            (map/tile-overlay-gpx "pss.rs E7-11-3" is true false)))
        (binding [geojson/*style-stroke-color* geojson/color-green]
          (with-open [is (fs/input-stream (path/child dataset-git-root-path
                                                      "pss.rs" "routes" "E7-11-4.gpx"))]
            (map/tile-overlay-gpx "pss.rs E7-11-4" is true false)))
        (binding [geojson/*style-stroke-color* geojson/color-green]
          (with-open [is (fs/input-stream (path/child dataset-git-root-path
                                                      "pss.rs" "routes" "E7-11-5.gpx"))]
            (map/tile-overlay-gpx "pss.rs E7-11-5" is true false)))

        (binding [geojson/*style-stroke-color* geojson/color-green]
          (with-open [is (fs/input-stream (path/child dataset-git-root-path
                                                      "pss.rs" "routes" "E7-12-1.gpx"))]
            (map/tile-overlay-gpx "pss.rs E7-12-1" is true false)))
        (binding [geojson/*style-stroke-color* geojson/color-green]
          (with-open [is (fs/input-stream (path/child dataset-git-root-path
                                                      "pss.rs" "routes" "E7-12-2.gpx"))]
            (map/tile-overlay-gpx "pss.rs E7-12-2" is true false)))
        (binding [geojson/*style-stroke-color* geojson/color-green]
          (with-open [is (fs/input-stream (path/child dataset-git-root-path
                                                      "pss.rs" "routes" "E7-12-3.gpx"))]
            (map/tile-overlay-gpx "pss.rs E7-12-3" is true false)))
        (binding [geojson/*style-stroke-color* geojson/color-green]
          (with-open [is (fs/input-stream (path/child dataset-git-root-path
                                                      "pss.rs" "routes" "E7-12-4.gpx"))]
            (map/tile-overlay-gpx "pss.rs E7-12-4" is true false)))
        (binding [geojson/*style-stroke-color* geojson/color-green]
          (with-open [is (fs/input-stream (path/child dataset-git-root-path
                                                      "pss.rs" "routes" "E7-12-5.gpx"))]
            (map/tile-overlay-gpx "pss.rs E7-12-5" is true false)))
        (binding [geojson/*style-stroke-color* geojson/color-green]
          (with-open [is (fs/input-stream (path/child dataset-git-root-path
                                                      "pss.rs" "routes" "E7-12-6.gpx"))]
            (map/tile-overlay-gpx "pss.rs E7-12-6" is true false)))


        ;; osm relations
        (binding [geojson/*style-stroke-color* geojson/color-purple]
          (map/tile-overlay-osm-hiking-relation "E7-8 14177412" 14177412 true false false))
        (binding [geojson/*style-stroke-color* geojson/color-purple]
          (map/tile-overlay-osm-hiking-relation "E7-9 14180878" 14180878 true false false))
        (binding [geojson/*style-stroke-color* geojson/color-purple]
          (map/tile-overlay-osm-hiking-relation "E7-12 11753312" 11753312 true false false))
        (binding [geojson/*style-stroke-color* geojson/color-purple]
          (map/tile-overlay-osm-hiking-relation "E7-16 14185390" 14185390 true false false))

        ;; osm relations added by Stadtigel
        (binding [geojson/*style-stroke-color* geojson/color-purple]
          (map/tile-overlay-osm-hiking-relation "E7-1 18335543" 18335543 true false false))
        (binding [geojson/*style-stroke-color* geojson/color-purple]
          (map/tile-overlay-osm-hiking-relation "E7-2 18335542" 18335542 true false false))
        (binding [geojson/*style-stroke-color* geojson/color-purple]
          (map/tile-overlay-osm-hiking-relation "E7-3 18331386" 18331386 true false false))
        (binding [geojson/*style-stroke-color* geojson/color-purple]
          (map/tile-overlay-osm-hiking-relation "E7-4 18206217" 18206217 true false false))
        (binding [geojson/*style-stroke-color* geojson/color-purple]
          (map/tile-overlay-osm-hiking-relation "E7-5 12499130" 12499130 true false false))
        (binding [geojson/*style-stroke-color* geojson/color-purple]
          (map/tile-overlay-osm-hiking-relation "E7-6 18335544" 18335544 true false false))
        (binding [geojson/*style-stroke-color* geojson/color-purple]
          (map/tile-overlay-osm-hiking-relation "E7-7 18345452" 18345452 true false false))
        
        (binding [geojson/*style-stroke-color* geojson/color-purple]
          (map/tile-overlay-osm-hiking-relation "E7-10 17610623" 17610623 true false false))
        (binding [geojson/*style-stroke-color* geojson/color-purple]
          (map/tile-overlay-osm-hiking-relation "E7-11 18360637" 18360637 true false false))
        (binding [geojson/*style-stroke-color* geojson/color-purple]
          (map/tile-overlay-osm-hiking-relation "E7-12 18360638" 18360638 true false false))
        (binding [geojson/*style-stroke-color* geojson/color-purple]
          (map/tile-overlay-osm-hiking-relation "E7-13 18368092" 18368092 true false false))
        (binding [geojson/*style-stroke-color* geojson/color-purple]
          (map/tile-overlay-osm-hiking-relation "E7-14 18371638" 18371638 true false false))
        (binding [geojson/*style-stroke-color* geojson/color-purple]
          (map/tile-overlay-osm-hiking-relation "E7-15 18374951" 18374951 true false false))
        
        (binding [geojson/*style-stroke-color* geojson/color-purple]
          (map/tile-overlay-osm-hiking-relation "E7-17 18404717" 18404717 true false false))
        (binding [geojson/*style-stroke-color* geojson/color-purple]
          (map/tile-overlay-osm-hiking-relation "E7-18 18397689" 18397689 true false false))
        (binding [geojson/*style-stroke-color* geojson/color-purple]
          (map/tile-overlay-osm-hiking-relation "E7-19 18378518" 18378518 true false false))
        (binding [geojson/*style-stroke-color* geojson/color-purple]
          (map/tile-overlay-osm-hiking-relation "E7-20 18391888" 18391888 true false false))
        
        
        ])))
   (context/trace
    context
    (str
     "map created, view <a href='file://"
     (path/path->string export-path)
     "'>map</a>"))))



;; old code, useful for history
#_(do
    (println "routes")
    (run!
     (fn [[route relation]]
       (println
        (str
         (get-in relation [:tags "ref"]) "\t"
         (get relation :id) "\t"
         (get-in relation [:tags "network"]) )))
     (filter
      #(= (get-in (second %) [:tags "network"]) "lwn")
      (filter
       some?
       (map
        (fn [route]
          (when-let [relation (get relation-map (:id route))]
            [route relation]))
        (sort-by :id (vals routes)))))))

;; 20220624 find lwn routes and change to rwn
#_(run!
   println
   (map
    #(clojure.string/join "," %)
    (partition
     20
     20
     nil 
     (map
      #(str "r" (get (second %) :id))
      (filter
       #(= (get-in (second %) [:tags "network"]) "lwn")
       (filter
        some?
        (map
         (fn [route]
           (when-let [relation (get relation-map (:id route))]
             [route relation]))
         (sort-by :id (vals routes)))))))))




;; todo impement as job
;; todo remove ignore list in next iteration
;; concept, run diff, commit what is ok, what is not resolve, iterate

#_(let [original (with-open [is (fs/input-stream ["Users" "vanja" "projects" "pss-map-v1" "dataset" "trails.geojson"])]
                 (json/read-keyworded is))
      new (with-open [is (fs/input-stream ["Users" "vanja" "projects" "osm-pss-integration" "dataset" "trails.geojson"])]
            (json/read-keyworded is))
      ;; todo reset on each iteration
      ignore #{"2-3-5" "1-2-1" "1-2-2" "1-2-3" "1-3-1" "1-4-1" "1-4-3" "1-4-4"
               "1-4-5" "1-13-2" "2-3-1" "2-3-2"
               "2-3-3" ;; lose mapirano
               "2-3-6" "2-3-7"
               "2-3-8"
               "2-13-1" "2-14-19" "2-14-20" "2-14-21" "2-16-1"
               "3-3-2" "3-7-1" "3-8-1" "3-8-2" "3-8-3" "3-13-1" "3-13-2"
               "3-14-1" "3-14-2" "3-14-3" "3-14-4" "3-14-5" "3-14-6"
               "3-14-7" "3-14-8" "3-18-1" "3-20-1" "3-20-3" "3-20-5" "3-20-7" "3-20-8" "3-20-9"
               "3-22-1" "3-22-2" "3-22-3" "3-22-4"
               "3-22-5" ;; promenio kokan proverice
               "3-28-3"
               "3-28-5" ; lose mapirano
               "3-32-1" "3-34-1"
               "4-4-3" "4-4-6" "4-4-7"
               "4-23-1" "4-26-1"
               "4-27-1" "4-27-2" "4-27-3" "4-27-4" "4-27-5" "4-27-6" "4-27-7" "4-27-8"
               "4-29-1" "4-30-1" "4-31-1" "4-31-2" "4-31-4" "4-31-6" "4-31-11"
               "4-31-14" "4-33-3" "4-33-6" "4-33-8" "4-33-9"
               "4-36-1" "4-36-2" "4-37-1" "4-37-3" "4-42-1" "4-42-2" "4-47-2"
               "4-47-7" "4-48-4" "4-49-1" "4-53-1"
               "5-6-1" ""
               }]
  (let [original-ref-seq (map #(get-in % [:properties :ref]) (:features original))
        new-ref-seq (map #(get-in % [:properties :ref]) (:features new))]
    (println "original refs:" (count original-ref-seq))
    (println "new refs:" (count new-ref-seq))
    (let [new-ref-set (into #{} new-ref-seq)]
      (doseq [ref original-ref-seq]
        (when (not (contains? new-ref-set ref))
          (println "[REMOVED]" ref))))
    (let [original-ref-set (into #{} original-ref-seq)]
      (doseq [ref new-ref-seq]
        (when (not (contains? original-ref-set ref))
          (println "[ADDED]" ref))))
    ;; delete old report
    (doseq [file (fs/list ["Users" "vanja" "projects" "osm-pss-integration" "dataset" "staze-pss-rs-diff"])]
      (fs/delete file))
    (doseq [new-trail (:features new)]
      (let [ref (get-in new-trail [:properties :ref])]
        (when-let [original-trail (first (filter
                                          #(= (get-in % [:properties :ref]) ref)
                                          (:features original)))]
          (let [original-properties (:properties original-trail)
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
                original-geom (simplify-geom original-trail)
                new-geom (simplify-geom new-trail)
                osm-relation-id (get original-properties :osm-relation-id)
                source-geojson (json/read-keyworded (http/get-as-stream
                                                     (str "http://localhost:7077/route/source/"
                                                          osm-relation-id)))]
            (when (and
                   (or
                    (not (= original-properties new-properties))
                    (not (= original-geom new-geom)))
                   (not (contains? ignore ref)))
              (println "[MODIFIED]" ref (str "(r" osm-relation-id ")"))
              (with-open [os (fs/output-stream
                              (path/child
                               ["Users" "vanja" "projects" "osm-pss-integration" "dataset" "staze-pss-rs-diff" (str ref ".html")]))]
                (io/write-string
                 os
                 (map/render-raw
                  {}
                  [
                   (map/tile-layer-osm true)
                   (map/tile-layer-bing-satellite false)
                   (binding [geojson/*style-stroke-color* geojson/color-green
                             geojson/*style-stroke-width* 16]
                     (map/geojson-layer "original" original-trail true true))
                   (binding [geojson/*style-stroke-color* geojson/color-red
                             geojson/*style-stroke-width* 8]
                     (map/geojson-layer "new" new-trail true true))
                   (binding [geojson/*style-stroke-color* geojson/color-blue
                             geojson/*style-stroke-width* 4]
                     (map/geojson-layer "source gpx" source-geojson true true))])))
              )))))
    (println "[DONE]")))

