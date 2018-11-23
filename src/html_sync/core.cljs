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

(defn ^:private update-iframe-content
  "Writes the new content into the iframe. Make sure the iframe is fully loaded
  so that the contentWindow exists before calling this."
  [iframe new-content]
  (common/console-log "Applying IFrame content")
  (hidden-state/update-hidden-state :change-count (inc (get (hidden-state/get-hidden-state :change-count) 0)))
  (.. iframe -contentWindow -document (open))
  (.. iframe -contentWindow -document (write new-content))
  (.. iframe -contentWindow -document (close)))

(defn ^:private update-iframe-when-moved
  "Adds a listener to update the iframe's content when the HTMLEditor is
  moved to a different pane."
  [html-editor iframe text-editor]
  (.add (.-subscriptions html-editor)
        (.observePanes (.-workspace js/atom)
                       (fn [pane]
                         (.add (.-subscriptions html-editor)
                               (.onDidAddItem pane
                                              (fn [event]
                                                (when (= html-editor (.-item event))
                                                  (common/console-log "HTMLEditor moved!" event)
                                                  (.activateItem pane (.-item event))
                                                  (update-iframe-content iframe (.getText text-editor))))))))))

(defn ^:private update-iframe-when-changed
  "Adds a listener to update the iframe's content when the HTML TextEditor
  buffer changes."
  [html-editor iframe text-editor]
  (.add (.-subscriptions html-editor)
        (.onDidChange (.getBuffer text-editor)
                      (fn [change]
                        (common/console-log "Changed content!" change)
                        (update-iframe-content iframe (.getText text-editor))))))

(defn ^:private html-text-editor? [pane-item]
  (when-let [buffer (and pane-item (.-getBuffer pane-item))]
    (let [item-path (.getPath pane-item)]
      (when (string? item-path)
            (= html-extension (.toLowerCase (.extname path item-path)))))))

(defn ^:private open-html-editor
  "Opens a HTMLEditor for the HTML file and appends the content to the iframe.
  "
  [pane-item]
  (common/console-log "Checking URI:" pane-item)
  (if-not (html-text-editor? pane-item)
    (common/show-error "ERROR: " "Cannot open HTMLEditor for non HTML file. " (.getTitle pane-item))
    (let [item-path (.getPath pane-item)
          buffer (.-getBuffer pane-item)]
      (common/console-log "Openning:" item-path)
      (swap! common/uri-to-state assoc item-path {:html-text-editor pane-item
                                                  :html-text-buffer buffer})
      (-> (.-workspace js/atom)
          (.open (str common/protocol item-path))
          (.then (fn [html-editor]
                   (let [{:keys [iframe-element html-text-editor]} (get @uri-to-state item-path)]
                      (update-iframe-content iframe-element (.getText html-text-editor))
                      (update-iframe-when-moved html-editor iframe-element html-text-editor)
                      (update-iframe-when-changed html-editor iframe-element html-text-editor))))))))

(defn ^:private add-buttons [editor buttons]
  (common/console-log "Adding action buttons:" buttons)
  (let [editor-element (.-element editor)
        button-container (doto (.createElement js/document "div")
                               (.setAttribute "class" "button-container"))]
    (.add (.-classList editor-element) "html-sync")
    (doseq [[title callback] buttons]
      (let [button (doto (.createElement js/document "button")
                         (.setAttribute "class" (str "btn " title)))]
        (set! (.-innerText button) title)
        (.appendChild button-container button)
        (.addEventListener button "click" (partial callback editor))))
    (.appendChild editor-element button-container)))

(defn ^:private has-buttons? [editor]
  (let [editor-element (.-element editor)]
    (.contains (.-classList editor-element) "html-sync")))

(defn ^:private observe-editor
  "Adds buttons in the TextEditor if it's a HTML file and doesn't already
  have the buttons."
  [editor]
  (when (and (html-text-editor? editor)
             (not (has-buttons? editor)))
    (add-buttons editor {"sync" hidden-state/sync-hidden-editor
                         "show" (partial open-html-editor editor)})))

(defn ^:private html-editor-opener
  "Returns a HTMLEditor if the URI starts with the protocol."
  [uri-to-open]
  (when (string/starts-with? uri-to-open common/protocol)
    (html-editor/HTMLEditor. uri-to-open)))

(defn open []
  (open-html-editor (.getActiveTextEditor (.-workspace js/atom))))

(defn activate []
  (common/console-log "Activating HTML Sync!")
  ;(hidden-state/clean-up-hidden-editor)
  (hidden-state/create-hidden-state)
  (.add disposables (.addOpener (.-workspace js/atom) html-editor-opener))
  (.add disposables (.add commands "atom-workspace" (str common/package-name ":open") open))
  (.add disposables (.observeActiveTextEditor (.-workspace js/atom) observe-editor))
  (guest/look-for-teletyped-hidden-editor))

(defn deactivate []
  (hidden-state/destroy-hidden-pane)
  (.dispose disposables)
  (reset! common/uri-to-state {}))

(def start activate)
(def stop deactivate)
