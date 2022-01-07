(ns todomvc.model
  "just for keyword alias purposes")

;; main and worker live in separate namespaces so :: doesn't work

(def schema
  {::todo
   {:type :entity
    :primary-key ::todo-id
    :attrs {}
    }})
