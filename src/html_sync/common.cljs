(ns html-sync.common
  (:require [clojure.string :as string]
            [cljs.nodejs :as node]))

(def node-atom (node/require "atom"))
(def CompositeDisposable (.-CompositeDisposable node-atom))

(def disposables (CompositeDisposable.))
(def uri-to-state (atom {}))

(def package-name "html-sync")
(def protocol (str package-name "://"))
(def hidden-editor-title "HTMLSync hidden editor")

(defn console-log
  "Used for development. The output can be viewed in the Atom's Console when in
  Dev Mode."
  [& output]
  (apply (.-log js/console) output))

(defn show-error [& error]
  (apply (.-error js/console) error)
  (.addError (.-notifications js/atom) (apply str error)))
