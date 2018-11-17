(ns html-sync.core
  (:require [clojure.string :as string]
            [cljs.nodejs :as node]
            [html-sync.html-editor :as html-editor]))

(def path (node/require "path"))
(def node-atom (node/require "atom"))

(def CompositeDisposible (.-CompositeDisposible node-atom))
(def commands (.-commands js/atom))

(def disposables (CompositeDisposible.))
(def html-extension ".html")

(defn open-URI [uri-to-open]
  (when (= html-extension (.toLowerCase (.extname path uri-to-open)))
    (html-editor/HTMLEditor. uri-to-open)))

(defn activate []
  (.add disposables (.addOpener (.-workspace js/atom) open-URI)))

(defn deactivate []
  (.dispose disposables))

(def start activate)
(def stop deactivate)
