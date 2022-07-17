(ns shadow.grove.css.generate
  (:require [shadow.grove.css.specs :as s]
            [clojure.string :as str]))

(defn lookup-alias [index alias-kw]
  (case alias-kw
    :px-4
    {:padding-bottom "4px"
     :padding-top "4px"}

    :px-8
    {:padding-bottom "8px"
     :padding-top "8px"}

    :flex
    {:flex "1"}

    nil))

(defn lookup-var [index var-kw]
  (case var-kw
    :ui/sm
    "@media (min-width: 640px)"
    :ui/md
    "@media (min-width: 768px)"
    :ui/lg
    "@media (min-width: 1024px)"
    :ui/xl
    "@media (min-width: 1280px)"
    :ui/xxl ;; :ui/2xl invalid keyword
    "@media (min-width: 1536px)"
    nil))

(defn convert-num-val [index prop num]
  ;; FIXME: use tailwind number schema instead an emit rem
  (str num "px"))

(defn add-warning [{:keys [current] :as index} warning-type warning-vals]
  (update index :warnings conj (assoc warning-vals :warning warning-type :current current)))

(defn add-alias [index alias-kw]
  (let [alias-val (lookup-alias index alias-kw)]
    (if-not alias-val
      (add-warning index ::missing-alias {:alias alias-kw})
      (update-in index [:current :rules] merge alias-val))))

(defn add-var [index var-kw]
  index)

(defn add-map [index defs]
  (reduce-kv
    (fn [index prop [val-type val]]
      (case val-type
        :val
        (update-in index [:current :rules] assoc prop val)

        :number
        (assoc-in index [:current :rules prop] (convert-num-val index prop val))

        :string
        (assoc-in index [:current :rules prop] val)

        :concat
        (let [s (->> val
                     (map (fn [part]
                            (if (string? part)
                              part
                              ;; FIXME: validate exists and a string. warn otherwise
                              (lookup-var index part))))
                     (str/join ""))]
          (assoc-in index [:current :rules prop] s))

        :var
        (let [var-value (lookup-var index val)]
          (cond
            (nil? var-value)
            (add-warning index ::missing-var {:var val})

            (and (map? var-value) (= 1 (count var-value)))
            (assoc-in index [:current :rules prop] (first (vals var-value)))

            (string? var-value)
            (assoc-in index [:current :rules prop] var-value)

            (number? var-value)
            (assoc-in index [:current :rules prop] (convert-num-val index prop var-value))

            :else
            (add-warning index ::invalid-map-val {:prop prop :val-type val-type})))))

    index
    defs))

(declare add-part)

(defn current-to-defs [{:keys [current] :as index}]
  (-> index
      (update :defs conj current)
      (dissoc :current)))

(defn make-selector [{:keys [sel] :as item} sub-sel]
  [sub-sel sel])

(defn add-group [{:keys [current] :as index} {:keys [sel parts]}]
  (let [[sel-type sel-val] sel

        sel
        (case sel-type
          :var
          (lookup-var index sel-val)
          :string
          sel-val)]

    (cond
      (not sel)
      (add-warning index ::group-sel-var-not-found {:val sel-val})

      (map? sel)
      (add-warning index ::group-sel-resolved-to-map {:var sel-val :val sel})

      :else
      (-> (reduce add-part
            (assoc index
              :current
              (-> current
                  (assoc :rules {})
                  (cond->
                    (str/starts-with? sel "@")
                    (-> (update :at-rules conj sel)
                        (assoc :sel (:sel current)))

                    (str/index-of sel "&")
                    (assoc :sel (str/replace sel #"&" (:sel current)))
                    )))
            parts)
          (current-to-defs)
          (assoc :current current)))))

(defn add-part [index [part-id part-val]]
  (case part-id
    :alias
    (add-alias index part-val)
    :map
    (add-map index part-val)
    :var
    (add-var index part-val)
    :group
    (add-group index part-val)))

(defn generate-1 [index current]
  (-> (reduce add-part
        (assoc index
          :current
          (-> current
              (dissoc :parts)
              (assoc :rules {} :at-rules [])))
        (:parts current))
      (current-to-defs)))

(defn generate-rules [svc class-defs]
  (-> (reduce generate-1 {:svc svc :warnings [] :defs []} class-defs)
      (dissoc :svc)))

(defn make-selectors
  [item]
  (loop [{:keys [sel parent] :as x} item
         path []]
    (if-not parent
      (conj path sel)
      (recur parent [sel])
      )))


;; formatting could be better but good enough for now
(defn generate-css* [{:keys [sel at-rules ns line column rules] :as item}]
  (let [prefix (str/join "" (map (constantly " ") at-rules))]
    (str
      (when ns
        (str "/* " ns " " line ":" column " */\n"))
      (reduce
        (fn [s rule]
          (str rule " {\n"
               s
               "\n}"))
        (str prefix sel " {\n"
             (->> rules
                  (map (fn [[prop val]]
                         (str "  " prefix (name prop) ": " val ";")))
                  (str/join "\n"))
             "\n}")
        at-rules))))

;; naive very verbose generator
;; classnames for everything. no sharing. no re-use.
;; ideal for development, less ideal for deployment
(defn generate-css [svc class-defs]
  (let [{:keys [warnings defs]} (generate-rules svc class-defs)]

    (doseq [warning warnings]
      (prn warning))

    (->> defs
         (map generate-css*)
         (str/join "\n\n")
         )))

(defn start []
  {::svc true})

(comment
  (require 'clojure.pprint)
  (println
    (generate-css
      (start)
      [(-> (s/conform!
             '[:px-4 {:padding 2} :flex :some/var {:foo "yo" :bar ("url(" :ui/foo ")")}
               ["@media (prefers-color-scheme: dark)"
                [:ui/md :px-8
                 ["&:hover" {:color "green"}]]]])
           (assoc :sel "test" :ns "foo.bar" :line 3 :column 1))])))

