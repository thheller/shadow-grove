(ns shadow.grove.http-fx
  (:require
    [clojure.string :as str]
    [shadow.grove :as sg]))

(defonce trace-state-ref
  (atom {}))

(defn trace-state [request-id]
  (sg/watch trace-state-ref [request-id]))

;; this is using XMLHttpRequest. no intent on making this usable with anything else.
;; might split this up into different namespace so there could be one variant using js/fetch
;; or some other node-specific APIs

(defn transform-request-body
  [config env body opts]
  (let [request-format
        (or (:request-format opts)
            (:request-format config)
            (::request-format env))]

    (when-not request-format
      (throw (ex-info "no request-format configured but :body provided" {:env env :config config :body body :opts opts})))

    (cond
      (fn? request-format)
      (request-format env body opts)

      ;; FIXME: use a better mechanism to extend request formats
      ;; shouldn't just use some keyword and hope the thing to be there
      (= :transit request-format)
      (let [{::sg/keys [^function transit-str]} @(::sg/runtime-ref env)]
        ["application/transit+json; charset=utf-8"
         (transit-str body)])

      (= :edn request-format)
      ["application/edn; charset=utf-8"
       (pr-str body)]

      :else
      (throw (ex-info "unknown request format" {:request-format request-format :config config :env env}))
      )))

(defn body-transform [config env request ^js xhr-req]
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
        (get-in config [:response-formats content-type])]

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
      (str s
           (when-not (str/blank? s) "&")
           (js/encodeURIComponent (name key)) "=" (js/encodeURIComponent (str val))))
    ""
    m))

(defn as-uri [env input]
  (cond
    (string? input)
    input

    (vector? input)
    (reduce-kv
      (fn [url idx part]
        (cond
          (map? part)
          (reduced ;; FIXME: should probably hard fail if there is something left after a map, this just drops it
            (str url
                 (if (str/includes? url "?") "&" "?")
                 (query-params->str env part)))

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
  [{:keys [base-url] :as config}
   env
   request
   ^function callback]
  (let [[method uri body opts]
        (cond
          ;; :request [:GET request nil {}]
          (vector? request)
          request

          ;; :request {:uri "/foo" :method :GET}
          (map? request)
          (let [{:keys [method uri body]} request]
            [(or method (if body :POST :GET))
             (or uri "")
             body
             request])

          ;; :request "/foo"
          (string? request)
          [:GET request nil {}])

        {:keys [timeout trace]}
        opts

        body? (some? body)

        [content-type body]
        (if body?
          (transform-request-body config env body opts)
          [nil nil])

        ;; FIXME: validate valid :GET, :POST, ...
        request-method
        (name method)

        req-url
        (as-uri env uri)

        req-url
        (if base-url
          (str base-url req-url)
          req-url)

        xhr-req (js/XMLHttpRequest.)

        sent-request
        {:uri req-url
         :method request-method
         :content-type content-type
         :body body}]

    (try
      ;; FIXME: last 2 args, from either env or request?
      (.open xhr-req request-method req-url true #_username #_password)

      (when trace
        (swap! trace-state-ref assoc trace {:status :started :request-at (js/Date.now)}))

      ;; old IE was picky about setting some of these only after open and before send
      ;; doesn't matter otherwise so just keep doing it this way

      (when-some [abort-id (:abort-id request)]
        (set! xhr-req -onabort
          (fn [e]
            ;; FIXME: actually store and abort request, then trigger on-abort
            (js/console.warn "request was aborted" request xhr-req e))))

      (set! xhr-req -onerror
        (fn [e]
          (js/console.warn "request error" request xhr-req e)

          (when trace
            (swap! trace-state-ref assoc trace {:status :error}))
          ))

      ;; FIXME: could use this point for cleanup duty if needed
      #_(set! xhr-req -onloadend
          (fn [e]
            (js/console.log "request loadend" request xhr-req e)))

      (set! xhr-req -onload
        (fn [e]
          (let [status (.-status xhr-req)
                body (body-transform config env request xhr-req)]

            (when trace
              (swap! trace-state-ref update trace
                (fn [{:keys [request-at] :as req}]
                  (let [now (js/Date.now)
                        runtime (- now request-at)]
                    (assoc req
                      :status :completed
                      :duration runtime
                      :completed-at now)))))

            (callback body status sent-request xhr-req)

            )))

      (when timeout
        (set! xhr-req -timeout timeout)
        (set! xhr-req -ontimeout
          (fn [e]
            ;; FIXME: callback with 409 status (client timeout), stricly speaking not the correct code but close enough
            (js/console.log "request actually timed out" xhr-req request)
            )))

      (set! (.-responseType xhr-req) "text")
      (set! (.-withCredentials xhr-req)
        (and (not (false? (:with-credentials opts)))
             (not (false? (:with-credentials config)))))

      (when content-type
        (.setRequestHeader xhr-req "content-type" content-type))

      (if body?
        (.send xhr-req body)
        (.send xhr-req))

      (catch :default e
        (js/console.warn "failed to setup request" request xhr-req e)
        (throw e)))))

(defn just-response-text [env ^js xhr-req]
  (.-responseText xhr-req))

;; taking the read-fns from env so this ns doesn't depend on either cljs.reader nor transit
;; there are also several other places that will require these fns anyways
;; FIXME: realld should find a better way to handle this, harcoded these like this is bad
(defn parse-edn [env ^js xhr-req]
  (let [read-fn (::sg/edn-read @(::sg/runtime-ref env))]
    (when-not read-fn
      (throw (ex-info "received a EDN response but didn't have edn-read fn" {})))
    (read-fn (.-responseText xhr-req))))

(defn parse-transit [env ^js xhr-req]
  (let [read-fn (::sg/transit-read @(::sg/runtime-ref env))]
    (when-not read-fn
      (throw (ex-info "received a transit response but didn't have transit-read fn" {})))
    (read-fn (.-responseText xhr-req))))

(defn parse-json [env ^js xhr-req]
  ;; FIXME: should have used responseType = "json" and responseJSON
  (js/JSON.parse (.-responseText xhr-req)))

(def default-response-formats
  {"text/plain" just-response-text
   "text/html" just-response-text
   "text/edn" parse-edn
   "application/json" parse-json
   "application/edn" parse-edn
   "application/transit+json" parse-transit})

(defn handle-error [config env request-def result status sent-request xhr-req]
  (let [on-error
        (or (:on-error request-def)
            (:on-error config)
            (::on-error env))]
    (if-not on-error
      (js/console.warn "request result in error response without handler" env request-def xhr-req status)
      (trigger env (assoc on-error :result result :status status :sent-request sent-request)))))

(defn handle-success [config env request result]
  (let [on-success (:on-success request)]
    (trigger env (assoc on-success :result result))))

(defn merge-right [left right]
  (merge right left))

(defn make-handler [config]
  ;; FIXME: deep-merge, so maps are merged properly
  (let [config (update config :response-formats merge-right default-response-formats)]
    (fn http-fx-handler [env request-def]
      (when request-def
        (let [{:keys [request request-many]} request-def]
          (cond
            (and request request-many)
            (throw (ex-info "can only use :request OR :request-many, not both" {:request-def request-def}))

            request
            (do-request config env request
              (fn [result status sent-request xhr-req]
                (if (request-error? status)
                  (handle-error config env request-def result status sent-request xhr-req)
                  (handle-success config env request-def result))))

            request-many
            (let [results-ref (atom {})]
              (reduce-kv
                (fn [_ key request]
                  (do-request config env request
                    (fn [result status sent-request xhr-req]
                      (if (request-error? status)
                        ;; FIXME: should also support :on-partial-success (when at least on of the requests succeeds)
                        (handle-error config env request-def result status sent-request xhr-req)
                        (do (swap! results-ref assoc key result)
                            ;; FIXME: should also support :on-progress (for intermediate completion)
                            (when (= (count request-many)
                                     (count @results-ref))
                              (handle-success config env request-def @results-ref)))))
                    ))
                nil
                request-many))

            :else
            (throw (ex-info "missing :request OR :request-many, need one" {:request-def request-def}))
            )))
      env)))

(comment
  (let [handler (make-handler {})
        env {:transact! (fn [tx] (js/console.log "fake fx" tx))}]

    (handler env
      {:request ["foo" {:id 1}]
       :on-error [::error!]
       :on-success [::success!]})))