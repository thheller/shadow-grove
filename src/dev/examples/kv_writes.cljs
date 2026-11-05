(ns examples.kv-writes
  "Tables are created once at init and change only in transaction handlers, which get
   every table in `tx-env` and return it updated ([transactions](/transactions/)).
   Plain `assoc-in` / `update-in` work. `shadow.grove.kv` adds helpers for entities with
   a primary key. Reading them: [kv-reads](/kv-reads/)."
  (:require
    [clojure.string :as str]
    [examples.common :as c]
    [shadow.grove :as sg :refer (defc deftx << css)]
    [shadow.grove.kv :as kv]))

(defonce app (sg/get-runtime :app))

;; ## tables and their config
;;
;; `(sg/get-runtime :app)` returns the runtime for that id, created once and stable
;; across hot-reloads. `(sg/add-kv-table rt-ref id config init-data)` installs a table.
;; It is **init-only**: it overwrites the table, so call it before the first render,
;; never from handlers or components. Data that arrives later (from the server, embedded
;; in the page) enters through a tx.
;;
;; - `:primary-key` is the entity id key (`:id`, `:article-id`, whatever the entities
;;   use), or a vector of keys for a composite key. Plain `assoc` / `update` don't need
;;   it, `kv/add` and `kv/merge-seq` do.
;; - `:validate-fn (fn [table key value] ...)` throws on bad writes.
;; - `:joins {key other-table}` makes `kv/add` / `kv/merge-seq` normalize: a nested map
;;   (or vector of maps) under `key` is stored in `other-table` and replaced by its
;;   primary key. Both tables need `:primary-key`.
;; - `:attrs {key {:references other-table}}` declares foreign keys so the devtools can
;;   navigate between tables. Nothing else uses it.
;; - Table ids are keywords. A namespaced id (`::toast`, [animations](/animations/))
;;   keeps a helper namespace's table apart from app tables.
;;
;; Table granularity is a modeling choice: one table per entity type plus a `:ui` table
;; for ephemeral UI state is common. Separate tables keep observation precise, one big
;; table also works.

(defn setup-tables! []
  (sg/add-kv-table app :person
    {:primary-key :id
     :joins {:address :address}
     :validate-fn (fn [table key value]
                    (when-not (number? key)
                      (throw (ex-info "person ids must be numbers" {:key key}))))}
    {1 {:id 1 :name "Ada" :email "ada@example.com"}
     2 {:id 2 :name "Grace" :email "grace@example.com"}})
  (sg/add-kv-table app :address {:primary-key :address-id} {})
  (sg/add-kv-table app :booking {:primary-key [:day :slot]} {})
  (sg/add-kv-table app :ui {} {}))

;; ## kv/add and kv/merge-seq
;;
;; `(kv/add env table entity)` writes one entity, `(kv/merge-seq env table entities)` a
;; seq of them, taking the key from each map's primary key. Both **merge** into the stored
;; entity instead of replacing it, so a list payload with only id and name doesn't wipe
;; fields a detail fetch already stored. That makes them the usual way server payloads
;; enter KV ([http-fx](/http-fx/), [http-pull](/http-pull/)).
;;
;; - `merge-seq` can also store the ids in order: pass a path, or a fn of `[env ids]`.
;; - With `:joins`, the nested address goes into its own table.
;; - They throw without a `:primary-key` on the table, for an item missing its key, for
;;   `merge-seq` of something that isn't sequential, or `add` of a non-map.

(deftx add-partial! []
  [tx-env ev e]
  (kv/add tx-env :person {:id 1 :name "Ada (renamed)"}))

(deftx add-with-address! []
  [tx-env ev e]
  (kv/add tx-env :person {:id 3 :name "Edsger" :email "ed@example.com"
                          :address {:address-id "a3" :city "Austin"}}))

(deftx merge-list! []
  [tx-env ev e]
  (kv/merge-seq tx-env :person
    [{:id 2 :name "Grace (from list)"} {:id 4 :name "Barbara"}]
    [:ui :person-ids]))

(deftx merge-list-fn! []
  [tx-env ev e]
  (kv/merge-seq tx-env :person
    [{:id 5 :name "Joe"}]
    (fn [env ids] (update-in env [:ui :person-ids] (fnil into []) ids))))

;; ## kv/set, kv/update-val, kv/remove-all
;;
;; All `(env table ...) -> env`. `kv/set` replaces one entity, fields not in the new
;; value are gone (`kv` excludes `clojure.core/set`). `(kv/update-val env t k f & args)`
;; updates one. `kv/remove-all` empties the table. `assoc-in` / `update-in` work just as
;; well.

(deftx set-person! []
  [tx-env ev e]
  (kv/set tx-env :person 1 {:id 1 :name "Ada (set)"}))

(deftx update-val! []
  [tx-env ev e]
  (kv/update-val tx-env :person 2 update :name str "!"))

(deftx remove-all! []
  [tx-env ev e]
  (kv/remove-all tx-env :person))

;; ## replacing a whole table
;;
;; `(assoc tx-env :person new-map)` is diffed against the old table: keys missing from
;; it are removed, changed ones written. Use it for "the server sent the full table", and
;; `kv/merge-seq` when the payload is partial. A table can't be removed or set to
;; anything that isn't a map, see the failing writes below. Empty it with
;; `kv/remove-all`.

(deftx replace-table! []
  [tx-env ev e]
  (assoc tx-env :person {1 {:id 1 :name "Ada" :email "ada@example.com"}
                         9 {:id 9 :name "Alan"}}))

;; ## composite keys
;;
;; `:booking` has `{:primary-key [:day :slot]}`, so `kv/add` stores each booking under
;; `[day slot]`, and `(sg/kv-lookup :booking ["mon" 1])` reads it.

(deftx book! [day slot]
  [tx-env ev e]
  (kv/add tx-env :booking {:day day :slot slot :who "me"}))

;; ## writes that fail
;;
;; A handler that throws fails the whole tx: the runtime logs the error and commits
;; nothing.
;;
;; - The first button writes a string key, which the table's `:validate-fn` rejects.
;; - The second uses `kv/add` on `:ui`, which has no `:primary-key`.
;; - The third sets the table to nil. Tables are installed at init and stay, a tx can
;;   replace their contents with a map but not remove them. (The error it logs comes from
;;   inside the kv code, an `IKVReduce` protocol error, not a message saying so.)

(deftx clear-table! []
  [tx-env ev e]
  (assoc tx-env :person nil))

(deftx invalid-write! []
  [tx-env ev e]
  (assoc-in tx-env [:person "not-a-number"] {:id "not-a-number"}))

(deftx add-without-pk! []
  [tx-env ev e]
  (kv/add tx-env :ui {:x 1}))

;; # page

(defc ui-table [table-id]
  (bind table (sg/kv-lookup table-id))
  (render
    (<< [:div (css :mt-2)
         [:div (css :text-sm :font-semibold) (name table-id)]
         [:pre {:class (css :text-xs :bg-gray-50 :p-2 :rounded :whitespace-pre-wrap) :data-table (name table-id)}
          (str/join "\n" (map pr-str (sort-by (comp str key) table)))]])))

(defc ui-ids []
  (bind ids (sg/kv-lookup :ui :person-ids))
  (render
    (<< [:div {:class c/$muted :data-ids "person-ids"} "[:ui :person-ids] " (pr-str ids)])))

(defn btn [label ev]
  (<< [:button {:class c/$btn :on-click ev} label]))

(defc ui-root []
  (render
    (c/page "kv writes"
      {"kv/add and kv/merge-seq"
       (<< [:div {:class c/$row}
            (btn "kv/add partial" (add-partial!))
            (btn "kv/add with :joins" (add-with-address!))
            (btn "kv/merge-seq to path" (merge-list!))
            (btn "kv/merge-seq with fn" (merge-list-fn!))]
           (ui-table :person)
           (ui-table :address)
           (ui-ids))

       "kv/set, kv/update-val, kv/remove-all"
       (<< [:div {:class c/$row}
            (btn "kv/set" (set-person!))
            (btn "kv/update-val" (update-val!))
            (btn "kv/remove-all" (remove-all!))]
           (ui-table :person))

       "replacing a whole table"
       (<< [:div {:class c/$row}
            (btn "replace table" (replace-table!))]
           (ui-table :person))

       "composite keys"
       (<< [:div {:class c/$row}
            (btn "book mon/1" (book! "mon" 1))
            (btn "book mon/2" (book! "mon" 2))]
           (ui-table :booking))

       "writes that fail"
       (<< [:div {:class c/$row}
            (btn "write rejected by :validate-fn" (invalid-write!))
            (btn "kv/add without :primary-key" (add-without-pk!))
            (btn "table nil" (clear-table!))]
           (ui-table :person))})))

(defn init []
  (setup-tables!)
  (c/render! app (ui-root)))

(defn ^:dev/after-load reload! []
  (c/render! app (ui-root)))
