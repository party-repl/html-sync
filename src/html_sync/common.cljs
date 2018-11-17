(ns html-sync.common
  (:require [clojure.string :as string]
            [cljs.nodejs :as node]))

(defn console-log
  "Used for development. The output can be viewed in the Atom's Console when in
  Dev Mode."
  [& output]
  (apply (.-log js/console) output))
