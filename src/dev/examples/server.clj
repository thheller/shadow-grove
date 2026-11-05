(ns examples.server
  "dev-http :handler for examples/public, see examples/README.md

   file roots are tried first, so this only sees requests for files that don't exist:
   - /<example>/api/... are small fake backends (api multimethod), state in atoms
   - POST /<example>/api/reset resets all of them, used by examples/test/run.mjs
   - any other /<example>/... serves examples/public/<example>/index.html (pushState routing)"
  (:require
    [clojure.edn :as edn]
    [clojure.java.io :as io]
    [clojure.string :as str]
    [cognitect.transit :as transit]
    [shadow.http.push-state :as push-state])
  (:import
    [java.io ByteArrayOutputStream]))

;; artificial latency in ms, so loading states and suspense are visible
;; (swap! examples.server/latency assoc "http-pull" 2000)
(defonce latency
  (atom {"http-fx" 300
         "http-pull" 400
         "auth-gate" 300
         "vlist-remote" 150}))

;; requests

(defn read-body [{:keys [body headers] :as req}]
  (when body
    (let [s (slurp body)
          ct (get headers "content-type" "")]
      (cond
        (str/blank? s) nil
        (str/includes? ct "transit") (transit/read (transit/reader (io/input-stream (.getBytes s "UTF-8")) :json))
        :else (edn/read-string s)))))

(defn query-params [{:keys [query-string]}]
  (if-not query-string
    {}
    (->> (str/split query-string #"&")
         (map #(str/split % #"=" 2))
         (into {} (map (fn [[k v]] [(keyword k) (java.net.URLDecoder/decode (str v) "UTF-8")]))))))

(defn get-cookie [req name]
  (some->> (get-in req [:headers "cookie"])
           (re-find (re-pattern (str "(?:^|; )" name "=([^;]+)")))
           (second)))

;; responses

(defn edn-res
  ([data]
   (edn-res 200 data))
  ([status data]
   {:status status
    :headers {"content-type" "application/edn; charset=utf-8"}
    :body (pr-str data)}))

(defn transit-res [data]
  (let [out (ByteArrayOutputStream. 4096)]
    (transit/write (transit/writer out :json) data)
    {:status 200
     :headers {"content-type" "application/transit+json; charset=utf-8"}
     :body (.toString out "UTF-8")}))

(def not-found
  {:status 404
   :headers {"content-type" "text/plain"}
   :body "Not found."})

;; per example APIs, dispatched on the first path segment. parts are the segments after /api/

(defmulti api (fn [app req parts] app))

(defmethod api :default [_ _ _]
  not-found)

;; http-fx

(defn http-fx-init []
  {:next-id 3
   :items {1 {:id 1 :label "first"} 2 {:id 2 :label "second"}}})

(defonce http-fx-db (atom (http-fx-init)))

(defmethod api "http-fx" [_ req [route arg]]
  (case [(:request-method req) route]
    [:get "items"]
    (edn-res (->> (:items @http-fx-db) (vals) (sort-by :id) (vec)))

    [:post "items"]
    (let [{:keys [label]} (read-body req)]
      (if (str/starts-with? (str label) "!")
        (edn-res 422 {:error (str "the server rejects labels starting with !, got " label)})
        (let [id (:next-id @http-fx-db)
              item {:id id :label label}]
          (swap! http-fx-db #(-> % (assoc-in [:items id] item) (update :next-id inc)))
          (edn-res item))))

    [:get "count"]
    (edn-res {:count (count (:items @http-fx-db))})

    [:get "slow"]
    (do (Thread/sleep 1500)
        (edn-res {:done (str (java.time.LocalTime/now))}))

    [:get "status"]
    (edn-res (parse-long arg) {:error (str "you asked for " arg)})

    [:post "transit"]
    (let [body (read-body req)]
      (transit-res {:received body
                    :types (into {} (map (fn [[k v]] [k (str (type v))])) body)
                    :at (java.util.Date.)}))

    not-found))

;; http-pull

(defn http-pull-init []
  {:items {1 {:id 1 :label "alpha" :details "alpha details" :rev 1}
           2 {:id 2 :label "beta" :details "beta details" :rev 1}
           3 {:id 3 :label "gamma" :details "gamma details" :rev 1}}})

(defonce http-pull-db (atom (http-pull-init)))

(defmethod api "http-pull" [_ req [route arg]]
  (case [(:request-method req) route]
    ;; list payload, only some fields
    [:get "items"]
    (edn-res (->> (:items @http-pull-db) (vals) (sort-by :id) (mapv #(select-keys % [:id :label]))))

    ;; detail payload, all fields
    [:get "item"]
    (if-some [item (get-in @http-pull-db [:items (parse-long arg)])]
      (edn-res item)
      (edn-res 404 {:error (str "no item " arg)}))

    [:post "item"]
    (let [id (parse-long arg)
          {:keys [label]} (read-body req)]
      (swap! http-pull-db update-in [:items id] #(-> % (assoc :label label) (update :rev inc)))
      (edn-res (get-in @http-pull-db [:items id])))

    [:get "info"]
    (edn-res {:server "examples.server" :items (count (:items @http-pull-db))})

    [:post "search"]
    (let [{:keys [text]} (read-body req)]
      (edn-res {:text text
                :results (->> (vals (:items @http-pull-db))
                              (filter #(str/includes? (:label %) (str/lower-case (str text))))
                              (sort-by :id)
                              (mapv :label))}))

    [:get "time"]
    (edn-res {:time (str (java.time.LocalTime/now))})

    [:get "fail"]
    (edn-res 500 {:error "this endpoint always fails"})

    not-found))

;; auth-gate: session cookie

(defonce auth-sessions (atom {}))

(defn auth-user [req]
  (some->> (get-cookie req "auth-gate-session") (get @auth-sessions)))

(defmethod api "auth-gate" [_ req [route]]
  (let [user (auth-user req)]
    (case [(:request-method req) route]
      [:get "session"]
      (edn-res {:user user})

      [:post "login"]
      (let [{:keys [username password]} (read-body req)]
        (if (not= ["demo" "demo"] [username password])
          (edn-res 403 {:error "wrong username or password, use demo/demo"})
          (let [token (str (random-uuid))
                user {:username username :name "Demo User"}]
            (swap! auth-sessions assoc token user)
            (-> (edn-res {:user user})
                (assoc-in [:headers "set-cookie"] (str "auth-gate-session=" token "; Path=/auth-gate; HttpOnly"))))))

      [:post "logout"]
      (-> (edn-res {:user nil})
          (assoc-in [:headers "set-cookie"] "auth-gate-session=; Path=/auth-gate; Max-Age=0"))

      [:get "secret"]
      (if user
        (edn-res {:secret (str "only " (:name user) " can read this")})
        (edn-res 403 {:error "not logged in"}))

      not-found)))

;; vlist-remote: a big collection the client only ever sees in slices

(def vlist-total 100000)

(defonce vlist-realized (atom 300))

(defn vlist-row [idx]
  {:idx idx :label (str "row #" idx) :value (mod (* idx 7919) 1000)})

(defmethod api "vlist-remote" [_ req [route]]
  (case [(:request-method req) route]
    [:post "slice"]
    (let [{:keys [collection offset num]} (read-body req)
          total (if (= :growing collection) @vlist-realized vlist-total)
          end (min total (+ offset num))]
      (edn-res {:collection collection
                :item-count total
                :more? (= :growing collection)
                :offset offset
                :rows (mapv vlist-row (range offset end))}))

    ;; the growing collection only realizes more when asked
    [:post "more"]
    (do (swap! vlist-realized + 300)
        (edn-res {:item-count @vlist-realized}))

    not-found))

(defn reset-all! []
  (reset! http-fx-db (http-fx-init))
  (reset! http-pull-db (http-pull-init))
  (reset! auth-sessions {})
  (reset! vlist-realized 300)
  ::reset)

;; dev-http entry

(def public-dir (io/file "examples" "public"))

(defn handler [{:keys [uri] :as req}]
  (let [[_ app api-seg & parts] (str/split uri #"/")]
    (try
      (cond
        (and (= "api" api-seg) (= ["reset"] parts) (= :post (:request-method req)))
        (do (reset-all!)
            (edn-res {:ok true}))

        (= "api" api-seg)
        (do (when-some [ms (get @latency app)]
              (Thread/sleep (long ms)))
            (api app req parts))

        ;; /routing/item/1 serves examples/public/routing/index.html, so pushState routes survive reloads
        (and (seq app)
             (.exists (io/file public-dir app "index.html"))
             (str/includes? (get-in req [:headers "accept"] "") "text/html"))
        {:status 200
         :headers {"content-type" "text/html; charset=utf-8"}
         :body (slurp (io/file public-dir app "index.html"))}

        :else
        (push-state/handle req))
      (catch Exception e
        (.printStackTrace e)
        {:status 500
         :headers {"content-type" "text/plain"}
         :body (str "examples.server failed: " (.getMessage e))}))))
