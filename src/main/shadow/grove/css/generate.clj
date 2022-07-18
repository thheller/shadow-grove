(ns shadow.grove.css.generate
  (:require [shadow.grove.css.specs :as s]
            [clojure.string :as str]
            [clojure.java.io :as io]
            [clojure.edn :as edn])
  (:import [java.io StringWriter Writer]))

(defn lookup-alias [index alias-kw]
  (get-in index [:aliases alias-kw]))

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

(def plain-numeric-props
  #{:flex :order :flex-shrink :flex-grow})

(defn convert-num-val [index prop num]
  (if (contains? plain-numeric-props prop)
    (str num)
    (or (get-in index [:svc :spacing num])
        (throw
          (ex-info
            (str "invalid numeric value for prop " prop)
            {:prop prop :num num})))))

(defn add-warning [{:keys [current] :as index} warning-type warning-vals]
  (update index :warnings conj (assoc warning-vals :warning warning-type :current current)))

(declare add-part)

(defn add-alias [index alias-kw]
  ;; FIXME: aliases should be allowed to be any other part, and act accordingly
  (let [alias-val (lookup-alias index alias-kw)]
    (if-not alias-val
      (add-warning index ::missing-alias {:alias alias-kw})
      (update-in index [:current :rules] merge alias-val))))

(defn add-var [index var-kw]
  (throw (ex-info "tbd" {:var-kw var-kw})))

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

(defn current-to-defs [{:keys [current] :as index}]
  (-> index
      (cond->
        (seq (:rules current))
        (update :defs conj current))
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

(defn generate-1 [index {:keys [css-id] :as current}]
  (-> (reduce add-part
        (assoc index
          :current
          (-> current
              (dissoc :parts)
              (assoc :rules {} :at-rules [] :sel (str "." css-id))))
        (:parts current))
      (current-to-defs)))

(defn generate-rules [svc class-defs]
  (-> (reduce generate-1 {:svc svc :warnings [] :defs []} class-defs)
      (dissoc :svc)))

;; helper methods for eventual data collection for source mapping
(defn emits
  ([^Writer w ^String s]
   (.write w s))
  ([w s & more]
   (emits w s)
   (doseq [s more]
     (emits w s))))

(defn emitln
  ([^Writer w]
   (.write w "\n"))
  ([^Writer w & args]
   (doseq [s args]
     (emits w s))
   (emitln w)))

;; argument order in case this ever moves to some kind of ast or protocols dispatching on first arg
(defn emit-def [{:keys [sel at-rules ns line column rules] :as def} w svc]
  (let [prefix (str/join "" (map (constantly " ") at-rules))]
    (when ns
      (emitln w (str "/* " ns " " line ":" column " */")))

    ;; could be smarter and combine at-rules if there are multiple
    ;;   @media (prefers-color-scheme: dark) {
    ;;   @media (min-width: 768px) {
    ;; could be
    ;;  @media (prefers-color-scheme: dark) and (min-width: 768px)

    ;; could also me smarter about rules and group all defs
    ;; so each rule only needs to be emitted once
    (doseq [rule at-rules]
      (emitln w rule " {"))

    (emitln w prefix sel " {")
    (doseq [prop (sort (keys rules))]
      (emitln w "  " prefix (name prop) ": " (get rules prop) ";"))

    (emits w "}")

    (doseq [_ at-rules]
      (emits w "}"))

    (emitln w)
    (emitln w)
    ))

;; naive very verbose generator
;; classnames for everything. no sharing. no re-use.
;; ideal for development, less ideal for deployment
(defn generate-css [svc class-defs]
  (let [{:keys [warnings defs]} (generate-rules svc class-defs)]

    ;; FIXME: should accept writer arg
    (let [css
          (let [sw (StringWriter.)]
            (.write sw (:normalize-src svc))
            (.write sw "\n\n")
            (doseq [def defs]
              (emit-def def sw svc))
            (.toString sw))]

      {:css css
       :warnings warnings})))

;; same naming patterns tailwind uses
(def spacing-alias-groups
  {"px-" [:padding-left :padding-right]
   "py-" [:padding-top :padding-bottom]
   "p-" [:padding]
   "mx-" [:margin-left :margin-right]
   "my-" [:margin-top :margin-bottom]
   "w-" [:width]
   "max-w-" [:max-width]
   "h-" [:height]
   "max-h-" [:max-height]
   "basis-" [:flex-basis]
   "gap-" [:gap]
   "gap-x-" [:column-gap]
   "gap-y-" [:row-gap]})

(defn generate-default-aliases [{:keys [spacing] :as svc}]
  (update svc :aliases
    (fn [aliases]
      (reduce-kv
        (fn [aliases space-num space-val]
          (reduce-kv
            (fn [aliases prefix props]
              (assoc aliases
                (keyword (str prefix space-num))
                (reduce #(assoc %1 %2 space-val) {} props)))
            aliases
            spacing-alias-groups))
        aliases
        spacing))))

(defn load-default-aliases []
  (edn/read-string (slurp (io/resource "shadow/grove/css/aliases.edn"))))

(defn start []
  (-> {::svc true
       :aliases
       (load-default-aliases)

       ;; https://tailwindcss.com/docs/customizing-spacing#default-spacing-scale
       :spacing
       {0 "0"
        0.5 "0.125rem"
        1 "0.25rem"
        1.5 "0.375rem"
        2 "0.5rem"
        2.5 "0.626rem"
        3 "0.75rem"
        3.5 "0.875rem"
        4 "1rem"
        5 "1.25rem"
        6 "1.5rem"
        7 "1.75rem"
        8 "2rem"
        9 "2.25rem"
        10 "2.5rem"
        11 "2.75rem"
        12 "3rem"
        13 "3.25rem"
        14 "3.5rem"
        15 "3.75rem"
        16 "4rem"
        17 "4.25rem"
        18 "4.5rem"
        19 "4.75rem"
        20 "5rem"
        24 "6rem"
        28 "7rem"
        32 "8rem"
        36 "9rem"
        40 "10rem"
        44 "11rem"
        48 "12rem"
        52 "13rem"
        56 "14rem"
        60 "15rem"
        64 "16rem"
        96 "24rem"}

       :normalize-src
       (slurp (io/resource "shadow/grove/css/modern-normalize.css"))}
      (generate-default-aliases)))

(comment
  (tap> (start))

  (require 'clojure.pprint)
  (println
    (generate-css
      (start)
      [(-> (s/conform!
             '[:px-4 {:padding 2} :flex {:foo "yo" :bar ("url(" :ui/foo ")")}
               ["@media (prefers-color-scheme: dark)"
                [:ui/md :px-8
                 ["&:hover" {:color "green"}]]]])
           (assoc :css-id "foo" :ns "foo.bar" :line 3 :column 1))])))

