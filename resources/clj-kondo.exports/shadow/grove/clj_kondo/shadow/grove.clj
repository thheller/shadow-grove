(ns clj-kondo.shadow.grove
  (:require [clj-kondo.hooks-api :as api]
            [clojure.string :as str]))

(def valid-hook-names
  "Return if the provided `name` is a valid hook name"
  #{'render 'bind 'event 'hook '<<})

(defn- -hook-node->name
  "Return the provided name of the `node`, or `nil`
  if it is not a list node."
  [node]
  (when (api/list-node? node)
    (api/sexpr (first (:children node)))))

(defmulti rewrite-hooks!
  "Multimethod to rewrite a hooks body for linting in clj-kondo. Is called with a sequence of
  hooks to be rewritten.

  It dispatches of of the hook name of the first hook in the sequence. Most implementations will
  recursively call `rewrite-hooks!` on the rest of their list."
  (fn [hooks]
    (if-not (seq hooks)
      :done
      (if-some [hook-name (-> hooks first -hook-node->name valid-hook-names)]
        hook-name
        :invalid))))

(defmethod rewrite-hooks! :done
  [_]
  nil)

(defmethod rewrite-hooks! :invalid
  [[hook & hooks]]
  (api/reg-finding!
    (assoc
      (meta
        (if (api/list-node? hook)
          (first (:children hook))
          hook))
      :level :error
      :message
      (str "Invalid hook: "
           (or (-hook-node->name hook) (api/sexpr hook))
           ", should be one of: " (str/join ", " valid-hook-names))
      :type :shadow.grove/invalid-hook))
  (rewrite-hooks! hooks))

(defmethod rewrite-hooks! 'bind
  [[hook & hooks]]
  (list
    (let [[_ bindings expr] (:children hook)]
      (api/list-node
        (list*
          (api/token-node 'let)
          (api/vector-node [bindings expr])
          (rewrite-hooks! hooks))))))

(defmethod rewrite-hooks! 'hook
  [[hook & hooks]]
  (cons
    (api/list-node
      (list*
        (api/token-node 'do)
        (-> hook :children rest)))
    (rewrite-hooks! hooks)))

(defmethod rewrite-hooks! 'render
  [[hook & hooks]]
  (concat
    (-> hook :children rest)
    (rewrite-hooks! hooks)))

(defmethod rewrite-hooks! '<<
  [[hook & hooks]]
  (concat
    (-> hook :children rest)
    (rewrite-hooks! hooks)))

(defmethod rewrite-hooks! 'event
  [[hook & hooks]]
  (let [[_ event-name params & body] (:children hook)]
    (when-not (api/keyword-node? event-name)
      (api/reg-finding!
        (assoc (meta event-name)
               :level :error
               :message "Event name must be keyword"
               :type :shadow.grove/invalid-event)))
    (when-not (<= 1 (count (:children params)) 3)
      (api/reg-finding!
        (assoc (meta params)
               :level :error
               :message "Must be arity 1, 2, or 3. Definition called with `env`, `ev`, `e`"
               :type :shdow.grove/invalid-event-artity)))
    (cons
      (api/list-node
        (list*
          (api/token-node 'fn)
          params
          body))
      (rewrite-hooks! hooks))))

(defn validate-component!
  "Function to validate all hooks inside a `defc` and make sure they create a valid component."
  [component-node hook-nodes]
  (let [hook-names         (->> hook-nodes
                                (keep -hook-node->name))
        has-render?        (some (set hook-names) ['<< 'render])
        hook->last-ix      (->> hook-names
                                (map-indexed (comp vec reverse vector))
                                (into {}))
        bind-after-render? (> (hook->last-ix 'bind -1)
                              (max (hook->last-ix 'render -1)
                                   (hook->last-ix '<< -1)))]
    (when-not has-render?
      (api/reg-finding!
        (assoc
          (meta component-node)
          :level :error
          :message "Components must have a `render` or `<<` hook"
          :type :shadow.grove/invalid-component)))
    (when bind-after-render?
      (api/reg-finding!
        (assoc
          (meta component-node)
          :level :error
          :message "All binds must be declared before `render` / `<<`"
          :type :shadow.grove/invalid-component)))))

(defn defc
  [{:keys [node]}]
  (let [[_ name & args] (:children node)
        [comp-bindings
         hooks]         (->> args
                             (drop-while #(not (api/vector-node? %)))
                             ((juxt first rest)))
        rewritten-hooks (->> hooks
                             (rewrite-hooks!))]

    (validate-component! node hooks)
    {:node
     (api/list-node
       (list*
         (api/token-node 'defn)
         name
         comp-bindings
         rewritten-hooks))}))
