(ns examples.common
  "compile-time half of examples.common: turns an example's own source into its page.

   an example file is written like a document. the ns docstring is the intro, and each
   `;; ## title` comment starts a chapter that runs until the next `;; #` or `;; ##`
   heading. inside a chapter:

   - the first comment block is prose
   - a later comment block followed by a blank line is prose too
   - a comment block directly above a form belongs to that form and shows as code
   - everything else is code

   prose is a small markdown subset: paragraphs, `- ` bullets (continued with two
   spaces), `### ` subheadings, fenced code, pipe tables, `code`, **bold**, [links](url).
   `(c/page \"title\" {\"chapter title\" demo})` renders all chapters in file order and
   puts each demo after its chapter's first prose block. a demo whose chapter doesn't
   exist is a compile error."
  (:require
    [clojure.java.io :as io]
    [clojure.string :as str]))

(defn esc [s]
  (-> (str s)
      (str/replace "&" "&amp;")
      (str/replace "<" "&lt;")
      (str/replace ">" "&gt;")
      (str/replace "\"" "&quot;")))

;; --- code

(def code-re
  #"(;[^\n]*)|(#?\"(?:\\.|[^\"\\])*\")|(\\(?:newline|space|tab|[^\s]))|(::?[A-Za-z0-9\-_.*+!?<>=/']+)")

(defn code-html [text]
  (let [m (re-matcher code-re text)
        sb (StringBuilder.)]
    (loop [pos 0]
      (if-not (.find m)
        (.append sb (esc (subs text pos)))
        (let [cls (cond (.group m 1) "tc" (.group m 2) "ts" (.group m 3) "ts" :else "tk")]
          (.append sb (esc (subs text pos (.start m))))
          (.append sb (str "<span class=\"" cls "\">" (esc (.group m)) "</span>"))
          (recur (.end m)))))
    (str "<pre><code>" sb "</code></pre>")))

;; --- prose

(def inline-re
  #"`([^`]+)`|\*\*([^*]+)\*\*|\[([^\]]+)\]\(([^)\s]+)\)")

(defn inline-html [s]
  (let [m (re-matcher inline-re s)
        sb (StringBuilder.)]
    (loop [pos 0]
      (if-not (.find m)
        (.append sb (esc (subs s pos)))
        (do (.append sb (esc (subs s pos (.start m))))
            (cond
              (.group m 1)
              (.append sb (str "<code>" (esc (.group m 1)) "</code>"))
              (.group m 2)
              (.append sb (str "<b>" (inline-html (.group m 2)) "</b>"))
              :else
              ;; target, so history/init! in the routing examples leaves these links alone
              (.append sb (str "<a href=\"" (esc (.group m 4)) "\" target=\"_self\">" (inline-html (.group m 3)) "</a>")))
            (recur (.end m)))))
    (str sb)))

(defn table-html [rows]
  (let [cells (fn [row] (->> (str/split (str/replace row #"^\||\|$" "") #"\|") (map str/trim)))
        [head _ & body] rows]
    (str "<table><thead><tr>"
         (apply str (for [c (cells head)] (str "<th>" (inline-html c) "</th>")))
         "</tr></thead><tbody>"
         (apply str (for [row body]
                      (str "<tr>" (apply str (for [c (cells row)] (str "<td>" (inline-html c) "</td>"))) "</tr>")))
         "</tbody></table>")))

(defn prose-html
  "lines without the comment prefix"
  [lines]
  (let [out (StringBuilder.)
        state (volatile! {:para [] :items [] :fence nil :table []})
        flush! (fn []
                 (let [{:keys [para items table]} @state]
                   (when (seq para)
                     (.append out (str "<p>" (inline-html (str/join " " para)) "</p>")))
                   (when (seq items)
                     (.append out (str "<ul>" (apply str (for [i items] (str "<li>" (inline-html (str/join " " i)) "</li>"))) "</ul>")))
                   (when (seq table)
                     (.append out (table-html table)))
                   (vswap! state assoc :para [] :items [] :table [])))]
    (doseq [line lines]
      (let [{:keys [fence items]} @state]
        (cond
          (str/starts-with? (str/trim line) "```")
          (if fence
            (do (.append out (code-html (str/join "\n" fence)))
                (vswap! state assoc :fence nil))
            (do (flush!)
                (vswap! state assoc :fence [])))

          fence
          (vswap! state update :fence conj line)

          (str/blank? line)
          (flush!)

          (str/starts-with? line "|")
          (do (when (or (seq (:para @state)) (seq items)) (flush!))
              (vswap! state update :table conj line))

          (str/starts-with? line "### ")
          (do (flush!)
              (.append out (str "<h3>" (inline-html (subs line 4)) "</h3>")))

          (str/starts-with? line "- ")
          (do (when (seq (:para @state)) (flush!))
              (vswap! state update :items conj [(subs line 2)]))

          (and (seq items) (str/starts-with? line "  "))
          (vswap! state update :items #(update % (dec (count %)) conj (str/trim line)))

          :else
          (do (when (seq items) (flush!))
              (vswap! state update :para conj (str/trim line))))))
    (flush!)
    (str out)))

;; --- source

(defn ns->resource [ns-sym]
  (str (-> (str ns-sym) (str/replace "-" "_") (str/replace "." "/")) ".cljs"))

(defn heading [line]
  (when-let [[_ hashes title] (re-matches #";; (#{1,2}) (.+)" line)]
    [(count hashes) (str/trim title)]))

(defn comment-line? [line]
  (str/starts-with? line ";;"))

(defn uncomment [line]
  (str/replace line #"^;; ?" ""))

(defn slug [title]
  (-> (str/lower-case title)
      (str/replace #"[^a-z0-9]+" "-")
      (str/replace #"^-|-$" "")))

(defn trim-blank [lines]
  (->> lines
       (drop-while str/blank?)
       (reverse)
       (drop-while str/blank?)
       (reverse)))

(defn segments
  "chapter body lines -> [[:prose lines] [:code lines] ...]"
  [lines]
  (loop [lines lines
         segs []
         first? true]
    (if-not (seq lines)
      segs
      (if (comment-line? (first lines))
        (let [[block more] (split-with comment-line? lines)
              attached? (and (not first?) (seq more) (not (str/blank? (first more))))]
          (if attached?
            (let [[code more] (split-with #(not (comment-line? %)) more)]
              (recur more (conj segs [:code (concat block code)]) false))
            (recur more (conj segs [:prose (map uncomment block)]) false)))
        (let [[code more] (split-with #(not (comment-line? %)) lines)]
          (recur more (conj segs [:code code]) false))))))

(defn merge-code [segs]
  (->> segs
       (partition-by first)
       (mapcat (fn [[[kind] :as group]]
                 (if (= kind :code)
                   [[:code (mapcat second group)]]
                   group)))
       (map (fn [[kind lines]] [kind (if (= kind :code) (trim-blank lines) lines)]))
       (remove (fn [[_ lines]] (empty? lines)))))

(defn seg-html [[kind lines]]
  (case kind
    :prose (prose-html lines)
    :code (code-html (str/join "\n" lines))))

(defn chapters [lines]
  (let [heads (keep-indexed (fn [idx line] (when-let [h (heading line)] (conj h idx))) lines)]
    (->> (partition-all 2 1 heads)
         (keep (fn [[[level title idx] [_ _ next-idx]]]
                 (when (= level 2)
                   (let [body (subvec lines (inc idx) (or next-idx (count lines)))
                         [intro & more] (merge-code (segments body))
                         ;; the demo goes after the first prose block
                         [before after] (if (= :prose (first intro)) [[intro] more] [[] (cons intro more)])]
                     {:title title
                      :id (slug title)
                      :before (apply str (map seg-html before))
                      :after (apply str (map seg-html (remove nil? after)))})))))))

(defn dedent-doc [doc]
  (let [[first-line & more] (str/split-lines doc)
        indent (->> more (remove str/blank?) (map #(count (re-find #"^ *" %))) (apply min 0x7fffffff))]
    (cons (str/trim first-line)
          (map #(if (str/blank? %) "" (subs % (min indent (count %)))) more))))

(defn ns-doc
  "the docstring of the file's ns form"
  [text]
  (let [form (binding [*read-eval* false]
               (read (java.io.PushbackReader. (java.io.StringReader. text))))
        doc (nth form 2 nil)]
    (if (string? doc) doc "")))

(defmacro page
  "the whole page for the current ns: header, intro, contents, chapters with their demos"
  [title demos]
  (when-not (map? demos)
    (throw (ex-info "page demos must be a literal map of chapter title -> demo" {})))
  (let [ns-sym (-> &env :ns :name)
        resource (ns->resource ns-sym)
        rc (or (io/resource resource) (throw (ex-info (str "can't find source " resource) {})))
        text (slurp rc)
        lines (vec (str/split-lines text))
        chs (chapters lines)
        titles (set (map :title chs))
        missing (remove titles (keys demos))]
    (when (seq missing)
      (throw (ex-info (str "no chapter for demo " (pr-str (vec missing)) " in " resource ", chapters are " (pr-str (mapv :title chs))) {})))
    `(examples.common/page*
       ~title
       ~(prose-html (dedent-doc (ns-doc text)))
       ~(str "src/dev/" resource)
       ~(str/includes? text "c/log!")
       ~(str/includes? text "(c/render-count")
       ~(mapv (juxt :id :title) chs)
       (shadow.grove/<<
         ~@(for [{:keys [id title before after]} chs]
             (let [demo (get demos title)
                   ;; a literal vector is markup
                   demo (if (vector? demo) `(shadow.grove/<< ~demo) demo)]
               `(examples.common/chapter* ~id ~title ~before ~demo ~after)))))))
