(ns html-sync.core
  (:require [clojure.string :as string]
            [cljs.nodejs :as node]
            [html-sync.common :as common :refer [uri-to-state disposables]]
            [html-sync.html-editor :as html-editor]
            [html-sync.hidden-state :as hidden-state]))

(def path (node/require "path"))
(def node-atom (node/require "atom"))

(def CompositeDisposable (.-CompositeDisposable node-atom))
(def commands (.-commands js/atom))

(def html-extension ".html")

;; Takes the uri that contains the original path of the text editor
(defn open-URI [uri-to-open]
  (when (string/starts-with? uri-to-open common/protocol)
    (html-editor/HTMLEditor. uri-to-open)))

(defn update-iframe-content [iframe new-content]
  (common/console-log "Applying IFrame content")
  (hidden-state/update-hidden-state :change-count (inc (get (hidden-state/get-hidden-state :change-count) 0)))
  (.. iframe -contentWindow -document (open))
  (.. iframe -contentWindow -document (write new-content))
  (.. iframe -contentWindow -document (close)))

(defn observe-editor [pane-item]
  (common/console-log "Checking URI:" pane-item)
  (when-let [buffer (.-getBuffer pane-item)]
    (let [item-path (.getPath pane-item)]
      (when (= html-extension (.toLowerCase (.extname path item-path)))
        (common/console-log "Openning:" item-path)
        (swap! common/uri-to-state assoc item-path {:editor pane-item
                                                    :buffer buffer})
        (-> (.-workspace js/atom)
            (.open (str common/protocol item-path))
            (.then (fn [html-editor]
                     (let [{:keys [iframe editor]} (get @uri-to-state item-path)
                           iframe-content (.getText editor)]
                        (update-iframe-content iframe iframe-content)
                        (.add (.-subscriptions html-editor)
                              (.onDidChange (.getBuffer editor)
                                            (fn [change]
                                              (common/console-log "Changed content!" change)
                                              (update-iframe-content iframe (.getText editor)))))))))))))

(defn open []
  (observe-editor (.getActiveTextEditor (.-workspace js/atom))))

(defn activate []
  (common/console-log "Activating HTML Sync!")
  (hidden-state/create-hidden-state)
  (.add disposables (.addOpener (.-workspace js/atom) open-URI))
  (.add disposables (.add commands "atom-workspace" (str common/package-name ":open") open)))

(defn deactivate []
  (hidden-state/destroy-hidden-pane)
  (.dispose disposables)
  (reset! common/uri-to-state {}))

(def start activate)
(def stop deactivate)
