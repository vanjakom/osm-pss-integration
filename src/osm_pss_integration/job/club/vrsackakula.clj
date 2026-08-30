(ns osm-pss-integration.job.club.vrsackakula)

;; 20260720
;; code copied from trek-mate.dataset.pss
;; not ready for running

;; 20221012
;; vrsacka kula psd
;; missing trails

;; transverzala
;; KT 1 - SC Milenijum
;; KT 2 - Vršačka kula
;; KT 3 - Đakov vrh
;; KT 4 - Planinarski dom na Širokom Bilu
;; KT 5 - Lisičja Glava
;; KT 6 - Manastir Malo Središte
;; KT 7 - Gudurički vrh
;; KT 8 - Manastir Mesić
#_(let [poi-seq [
                 "n995944969"   ;; Гудурички врх
                 "w298995099"   ;; Планинарски дом „Широко било“
                 "w167736184"   ;; Манастир Средиште
                 "w167729936"   ;; Манастир Месић
                 "n10094978312" ;; Каменарица, Хајдучке стене
                 "n1455237628"  ;; Лисичија глава

                 "w134768104"  ;; Центар Миленијум
                 "n986920487"  ;; Вршачка кула
                 "n1764106560" ;; Ђаков врх
                 ]
        poi-dataset (apply
                     osmapi/merge-datasets
                     (filter
                      some?
                      (map
                       (fn [element]
                         (let [type (.substring element 0 1)
                               id (as/as-long (.substring element 1))]
                           (cond
                             (= type "n") (osmapi/node-full id)
                             (= type "w") (osmapi/way-full id)
                             (= type "r") (osmapi/relation-full id)
                             :else nil)))
                       poi-seq)))

        note->geojson-point (fn [longitude latitude note]
                              (geojson/point
                               longitude
                               latitude
                               {
                                :marker-body note
                                :marker-icon "https://vanjakom.github.io/trek-mate-pins/blue_and_grey/visit.grey.png"}))]
    (count poi-dataset)
    (map/define-map
      "psdvrsackakula"
      (map/tile-layer-osm true)
      (map/tile-layer-bing-satellite false)
      (map/tile-layer-osm-rs false)
      (map/tile-layer-opentopomap false)
      (map/tile-overlay-waymarked-hiking false)
      (map/tile-overlay-bounds false)

      (map/geojson-style-extended-layer
       "poi"
       (geojson/geojson
        (filter
         some?
         (map
          (fn [element]
            (let [location (osmapi/element->location poi-dataset element)]
              (geojson/point
               (:longitude location)
               (:latitude location)
               {
                :marker-body (or (get-in location [:tags "name"]) "unknown")
                :marker-icon "https://vanjakom.github.io/trek-mate-pins/blue_and_grey/location.green.png"})))
          poi-seq))))
      (map/geojson-style-extended-layer
       "questions"
       (geojson/geojson
        [
         ;; notes from meeting with mile
         #_(note->geojson-point 21.32800, 45.12407
                                "pesacki put do parkinga, zaobilazi serpenditen")
         #_(note->geojson-point 21.35563, 45.12348
                                "uz potok do glavnog puta")
         #_(note->geojson-point 21.34780, 45.12986
                                (str
                                 "glavnim putem desno ka sumarevoj kuci</br>"
                                 "od sumareve kuce se ide na lisiciju glavu</br>"
                                 "posle djakovog vrha, ide lisicja glava"))
         #_(note->geojson-point 21.41188, 45.12917
                                "nastavljamo nazad ka poljanama, to da bude aleternativa")
         #_(note->geojson-point 21.39903, 45.12275
                                "ici ovim putem, za alternativu")
         #_(note->geojson-point 21.35680, 45.12942
                                "skinuti deo od doma do sumareve kucice, transverzala zavrsava u domu")
         #_(note->geojson-point 21.38437, 45.13209
                                "zemunica, toponim")
         #_(note->geojson-point 21.37225, 45.12246
                                (str
                                 "1-4-4 da bude kruzna</br>"
                                 "od hajduckih stena na branu pa na dom</br>"
                                 "koristiti"))
         #_(note->geojson-point 21.36927, 45.11946
                                (str
                                 "1-4-4 da bude kruzna</br>"
                                 "od hajduckih stena na branu pa na dom</br>"
                                 "koristiti"))       
       
       
         #_(note->geojson-point 21.35987 45.12440
                                "Т-1-3 На Угљешиној мапи трансверзала иде левом стазом")
         ;; mile: transverzala ne treba da ide do doma vec do sumareve kuce pa zavrsava u domu
         #_(note->geojson-point 21.37826 45.10194
                                "Т-1-3 Угљеша иде локалним путем преко Моје воде, Синпе долази путем од бране")
         #_(note->geojson-point 21.41117, 45.12020
                                "Т-1-3 Угљеша се пење директно на Чуку док Синпе иде према Пољанама")
         ;; mile: ostaje kako je uneseno
         #_(note->geojson-point 21.39946, 45.13636
                                "T-1-3 OSM релација иде десном страном, Угљеша и Синпе левом")
         ;; mile: ok je da se ide levom stranom

       
         #_(note->geojson-point )

         #_(note->geojson-point )
         #_(note->geojson-point )
         #_(note->geojson-point )

         ]))

      ;; dodatna pitanja
      ;; 1-4-1 se poklapa sa stazom 8
      ;; 1-4-2 se poklapa sa stazom 11
      ;; 1-4-3 unesen od doma do manastira (staza 9), ugljesa isao od manastira do Gudurickog vrha

      (with-open [is (fs/input-stream (path/child
                                       env/*dataset-cloud-path*
                                       "mile_markovic" "TREKING Vrsacke Mala.gpx"))]
        (map/tile-overlay-gpx "TREKING Vrsacke Mala" is true true))
      (with-open [is (fs/input-stream (path/child
                                       env/*dataset-cloud-path*
                                       "mile_markovic" "TREKING Vrsacke Srednja.gpx"))]
        (map/tile-overlay-gpx "TREKING Vrsacke Srednja" is true true))
      (with-open [is (fs/input-stream (path/child
                                       env/*dataset-cloud-path*
                                       "mile_markovic" "TREKING Vrsacke Velika.gpx"))]
        (map/tile-overlay-gpx "TREKING Vrsacke Velika" is true true))
      (with-open [is (fs/input-stream (path/child
                                       env/*dataset-cloud-path*
                                       "mile_markovic" "00 Vrsacke planine - STAZE+WP.gpx"))]
        (map/tile-overlay-gpx "00 Vrsacke planine - STAZE+WP" is true true))

      ;; Т-1-3 Вршачка трансверзала - https://pss.rs/terenipp/vrsacka-transverzala/
      (binding [geojson/*style-stroke-color* map/color-red]
        (map/tile-overlay-osm-hiking-relation
         "T-1-3 Вршачка трансверзала" 13145926 false false false))

      ;; 1-4-1 Успон на Гудурички врх - https://pss.rs/terenipp/uspon-na-guduricki-vrh/
      (binding [geojson/*style-stroke-color* map/color-red]
        (map/tile-overlay-osm-hiking-relation
         "1-4-1 Успон на Гудурички врх" 14906749 false false false))

      ;; 1-4-2 Манастир Средиште - Гудурички врх - https://pss.rs/terenipp/manastir-srediste-guduricki-vrh/
      (binding [geojson/*style-stroke-color* map/color-red]
        (map/tile-overlay-osm-hiking-relation
         "1-4-2 Манастир Средиште - Гудурички врх" 14911970 false false false))

      ;; 1-4-3 Манастир Месић - https://pss.rs/terenipp/manastir-mesic/
      (binding [geojson/*style-stroke-color* map/color-red]
        (map/tile-overlay-osm-hiking-relation
         "1-4-3 Манастир Месић" 14912124 false false false))

      ;; 1-4-4 Каменарице преко Лисич. главе - https://pss.rs/terenipp/kamenarice-preko-lisic-glave/
      (binding [geojson/*style-stroke-color* map/color-red]
        (map/tile-overlay-osm-hiking-relation
         "1-4-4 Каменарице преко Лисич. главе" 14916943 false false false))

      ;; 1-4-5 Гудурички врх преко Лисичије главе - https://pss.rs/terenipp/guduricki-vrh-preko-lisicije-glave/
      (binding [geojson/*style-stroke-color* map/color-red]
        (map/tile-overlay-osm-hiking-relation
         "1-4-5 Гудурички врх преко Лисичије главе" 14921298 false false false))

      ))
