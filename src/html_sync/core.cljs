(ns html-sync.core
  (:require [clojure.string :as string]
            [cljs.nodejs :as node]
            [html-sync.common :as common :refer [uri-to-state disposables]]
            [html-sync.html-editor :as html-editor]
            [html-sync.hidden-state :as hidden-state]
            [html-sync.guest :as guest]))

(def path (node/require "path"))
(def node-atom (node/require "atom"))

(def CompositeDisposable (.-CompositeDisposable node-atom))
(def commands (.-commands js/atom))

(def html-extension ".html")

;; Takes the uri that contains the original path of the text editor
(defn open-URI [uri-to-open]
  (when (string/starts-with? uri-to-open common/protocol)
    (html-editor/HTMLEditor. uri-to-open)))

(defn ^:private update-iframe-content [iframe new-content]
  (common/console-log "Applying IFrame content")
  (hidden-state/update-hidden-state :change-count (inc (get (hidden-state/get-hidden-state :change-count) 0)))
  (.. iframe -contentWindow -document (open))
  (.. iframe -contentWindow -document (write new-content))
  (.. iframe -contentWindow -document (close)))

(defn ^:private html-text-editor? [pane-item]
  (when-let [buffer (and pane-item (.-getBuffer pane-item))]
    (let [item-path (.getPath pane-item)]
      (when (string? item-path)
            (= html-extension (.toLowerCase (.extname path item-path)))))))

(defn open-editor [pane-item]
  (common/console-log "Checking URI:" pane-item)
  (when (html-text-editor? pane-item)
    (let [item-path (.getPath pane-item)
          buffer (.-getBuffer pane-item)]
      (common/console-log "Openning:" item-path)
      (swap! common/uri-to-state assoc item-path {:editor pane-item
                                                  :buffer buffer})
      (-> (.-workspace js/atom)
          (.open (str common/protocol item-path))
          (.then (fn [html-editor]
                   (let [{:keys [iframe-element editor]} (get @uri-to-state item-path)]
                      (update-iframe-content iframe-element (.getText editor))
                      (.add (.-subscriptions html-editor)
                            (.observePanes (.-workspace js/atom)
                                           (fn [pane]
                                             (.add (.-subscriptions html-editor)
                                                   (.onDidAddItem pane
                                                                  (fn [event]
                                                                    (common/console-log "New Item Added!" event)
                                                                    (when (= html-editor (.-item event))
                                                                      (.activateItem pane (.-item event))
                                                                      (update-iframe-content iframe-element (.getText editor)))))))))
                      (.add (.-subscriptions html-editor)
                            (.onDidChange (.getBuffer editor)
                                          (fn [change]
                                            (common/console-log "Changed content!" change)
                                            (update-iframe-content iframe-element (.getText editor))))))))))))

(defn add-buttons [editor buttons]
  (common/console-log "Adding action buttons:" buttons)
  (let [editor-element (.-element editor)
        button-container (doto (.createElement js/document "div")
                               (.setAttribute "class" "button-container"))]
    (.add (.-classList editor-element) "html-sync")
    (doseq [[title callback] buttons]
      (let [button (doto (.createElement js/document "button")
                         (.setAttribute "class" title))]
        (set! (.-innerText button) title)
        (.appendChild button-container button)
        (.addEventListener button "click" (partial callback editor))))
    (.appendChild editor-element button-container)))

(defn ^:private has-buttons? [editor]
  (let [editor-element (.-element editor)]
    (.contains (.-classList editor-element) "html-sync")))

(defn observe-editor [editor]
  (when (and (html-text-editor? editor)
             (not (has-buttons? editor)))
    (add-buttons editor {"sync" hidden-state/sync-hidden-editor
                         "show" (partial open-editor editor)})))

(defn open []
  (open-editor (.getActiveTextEditor (.-workspace js/atom))))

(defn activate []
  (common/console-log "Activating HTML Sync!")
  (hidden-state/clean-up-hidden-editor)
  (hidden-state/create-hidden-state)
  (.add disposables (.addOpener (.-workspace js/atom) open-URI))
  (.add disposables (.add commands "atom-workspace" (str common/package-name ":open") open))
  (.add disposables (.observeActiveTextEditor (.-workspace js/atom) observe-editor))
  (guest/look-for-teletyped-hidden-editor))

(defn deactivate []
  (hidden-state/destroy-hidden-pane)
  (.dispose disposables)
  (reset! common/uri-to-state {}))

(def start activate)
(def stop deactivate)
