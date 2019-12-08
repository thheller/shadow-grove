(ns shadow.experiments.grove.http-fx
  (:require
    [clojure.string :as str]))

;; this is using XMLHttpRequest. no intent on making this usable with anything else.
;; might split this up into different namespace so there could be one variant using js/fetch
;; or some other node-specific APIs

(defn transform-request-body
  [env request]
  (let [request-format
        (or (:request-format request)
            (::request-format env))]

    (when-not request-format
      (throw (ex-info "no request-format configured" {:env env :request request})))

    ;; not allowing this via request since that would make it not-data
    (let [request-formatter (get-in env [::request-formatters request-format])]
      (when-not request-format
        (throw (ex-info "no request-format configured" {:env env :request request :request-format request-format})))
      (request-formatter env request))))

(defn body-transform [env request ^js xhr-req]
  (let [content-type
        ;; FIXME: don't just drop off encoding, actually handle it
        (let [ct (str/lower-case (.getResponseHeader xhr-req "content-type"))
              sep (.indexOf ct ";")]
          (if (not= -1 sep)
            (.substring ct 0 sep)
            ct))

        ;; FIXME: allow custom format handler in request map?
        ;; adding a function would make it not-data so unlikely
        transform-fn
        (get-in env [::response-formats content-type])]

    (if (nil? transform-fn)
      (throw (ex-info "unsupported content-type" {:env env :req xhr-req :content-type content-type}))
      (transform-fn env xhr-req))))

(defn request-error? [status]
  (>= status 400))

(defn query-params->str [env m]
  ;; FIXME: this should be overridable via env
  ;; there are too many different url encoding schemes to cover all
  (reduce-kv
    (fn [s key val]
      (str (js/encodeURIComponent (name key)) "=" (js/encodeURIComponent (str val))))
    ""
    m))

(defn as-url [env input]
  (cond
    (string? input)
    input

    (vector? input)
    (reduce-kv
      (fn [url idx part]
        (cond
          (map? part)
          (str url
               (if (str/includes? url "?") "&" "?")
               (query-params->str env part))

          ;; don't mess with first part since it may be url base
          (zero? idx)
          (str url part)

          ;; if follow part starts with / don't add another
          (= "/" (aget part 0))
          (str url part)

          ;; otherwise join with /
          :else
          (str url "/" part)
          ))
      ""
      input)

    :else
    (throw (ex-info "doesnt look like an url" {:input input}))))

(defn trigger [{:keys [transact!] :as env} tx]
  (transact! tx))

(defn do-request
  [env
   {:keys [method uri body timeout]
    :as request
    :or {method :GET}}]
  (let [body? (and (not= :GET method) body)

        [content-type body]
        (if body?
          (transform-request-body env request)
          [nil nil])

        ;; FIXME: validate valid :GET, :POST, ...
        request-method
        (name method)

        req-url
        (as-url env uri)

        xhr-req (js/XMLHttpRequest.)

        ;; FIXME: undecided whether this should include xhr-req, makes it not-data
        ;; but this should never leave the local scope anyways. might make things easier to debug/inspect?
        sent-request
        (assoc request ::uri req-url ::method request-method ::content-type content-type ::body body)]

    (try
      ;; FIXME: last 2 args, from either env or request?
      (.open xhr-req request-method req-url true #_username #_password)

      ;; old IE was picky about setting some of these only after open and before send
      ;; doesn't matter otherwise so just keep doing it this way

      (when-some [abort-id (:abort-id request)]
        (set! xhr-req -onabort
          (fn [e]
            ;; FIXME: actually store and abort request, then trigger on-abort
            (js/console.warn "request was aborted" request xhr-req e))))

      (set! xhr-req -onerror
        (fn [e]
          (js/console.warn "request error" request xhr-req e)))

      ;; FIXME: could use this point for cleanup duty if needed
      #_(set! xhr-req -onloadend
          (fn [e]
            (js/console.log "request loadend" request xhr-req e)))

      (set! xhr-req -onload
        (fn [e]
          (let [status (.-status xhr-req)
                body (body-transform env request xhr-req)]
            (if (request-error? status)
              (let [on-error
                    (or (:on-error request)
                        (::on-error env))]
                (if-not on-error
                  (js/console.warn "request result in error response without handler" env request xhr-req e status)
                  (trigger env (conj on-error body status sent-request))))
              (let [on-success (:on-success request)]
                (trigger env (conj on-success body status sent-request))
                )))))

      (when timeout
        (set! xhr-req -timeout timeout)
        (set! xhr-req -ontimeout
          (fn [e]
            (js/console.log "request actually timed out" xhr-req request)
            )))

      (set! (.-responseType xhr-req) "text")
      ;; FIXME: bad for CORS! but who uses http auth for anything serious?
      (set! (.-withCredentials xhr-req) (not (false? (:with-credentials request))))

      (when body?
        (.setRequestHeader xhr-req "content-type" content-type))

      (if body?
        (.send xhr-req body)
        (.send xhr-req))

      (catch :default e
        (js/console.warn "failed to setup request" request xhr-req e)
        (throw e)))))

(defn handler [env request]
  (when request
    (cond
      (map? request)
      (do-request env request)

      (sequential? request)
      (reduce
        (fn [_ request]
          (do-request env request))
        nil
        request)

      :else
      (throw (ex-info "invalid http request" {:env env :request request}))))
  env)

(defn just-response-text [env ^js xhr-req]
  (.-responseText xhr-req))

;; FIXME: this shouldn't be tied to grove-worker ns. need some kind of API ns
;; taking the read-fns from env so this ns doesn't depend on either cljs.reader nor transit
;; there are also several other places that will require these fns anyways
(defn parse-edn [env ^js xhr-req]
  (let [read-fn (:shadow.experiments.grove-worker/edn-read env)]
    (when-not read-fn
      (throw (ex-info "received a EDN response but didn't have edn-read fn" {})))
    (read-fn (.-responseText xhr-req))))

(defn parse-transit [env ^js xhr-req]
  (let [read-fn (:shadow.experiments.grove-worker/transit-read env)]
    (when-not read-fn
      (throw (ex-info "received a transit response but didn't have transit-read fn" {})))
    (read-fn (.-responseText xhr-req))))

(defn parse-json [env ^js xhr-req]
  ;; FIXME: should take a fn from the env to convert to CLJS data, not by default though
  (js/JSON.parse (.-responseText xhr-req)))

(defn with-default-formats [env]
  (update env ::response-formats
    (fn [current]
      (merge
        {"text/plain" just-response-text
         "text/html" just-response-text
         "application/json" parse-json
         "application/edn" parse-edn
         "application/transit+json" parse-transit}
        current))))

(comment
  (handler
    (-> {:transact!
         (fn [tx]
           (js/console.log "fake fx" tx))}
        (with-default-formats))

    {:uri ["foo" {:id 1}]
     :on-error [::error!]
     :on-success [::success!]}))