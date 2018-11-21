(ns html-sync.html-editor
  (:require [clojure.string :as string]
            [cljs.nodejs :as node]
            [html-sync.common :as common :refer [uri-to-state]]
            [html-sync.hidden-state :as hidden-state]))

(def path (node/require "path"))
(def node-atom (node/require "atom"))

(def CompositeDisposable (.-CompositeDisposable node-atom))
(def File (.-File node-atom))
(def Emitter (.-Emitter node-atom))

(defn dispose [uri subscriptions]
  (let [pane (.paneForURI (.-workspace js/atom) uri)]
    (try
      (.destroyItem pane (.itemForURI pane uri))
      (catch js/Error e
        (common/console-log "ERROR:" "Failed to destroy item with URI" uri))
      (finally
        (.dispose subscriptions)))))

(defn create-dom [uri]
  (common/console-log "Current state:" uri @uri-to-state)
  (let [container (doto (.createElement js/document "div")
                        (.setAttribute "className" "html-sync html-editor-view"))
        iframe (doto (.createElement js/document "iframe")
                     (.setAttribute "className" "html-sync")
                     (.setAttribute "width" "100%")
                     (.setAttribute "height" "100%"))]
    (.appendChild container iframe)
    (swap! uri-to-state update uri #(assoc % :iframe-element iframe))
    container))

;; TODO: The editor can't be moved between different Panels because it loses
;;       the iframe content. Do we need to provide serialize?
(defn HTMLEditor [uri]
  (this-as this
           (let [original-uri (subs uri (count common/protocol))]
             (set! (.-file this) (File. original-uri))
             (set! (.-subscriptions this) (CompositeDisposable.))
             (set! (.-emitter this) (Emitter.))
             (set! (.-element this) (create-dom original-uri))
             (set! (.-view this) nil)
             (.add (.-subscriptions this)
                   (.onDidDelete (.-file this)
                                 (fn []
                                   (this-as this
                                            (let [pane (.paneForURI (.-workspace js/atom) uri)]
                                              (try
                                                (.destroyItem pane (.itemForURI pane uri))
                                                (catch js/Error e
                                                  (common/console-log "ERROR:" "Failed to destroy item with URI" uri))
                                                (finally
                                                  (.dispose (.-subscriptions this))))))))))
           this))

(set! (.. HTMLEditor -prototype -getElement)
      (fn []
        (this-as this
                 (.-element this))))
(set! (.. HTMLEditor -prototype -getView)
      (fn []
        (this-as this
                 nil)))
(set! (.. HTMLEditor -prototype -getAllowedLocations)
      (fn []
        (array "center")))
(set! (.. HTMLEditor -prototype -getPath)
      (fn []
        (this-as this
          (.getPath (.-file this)))))
(set! (.. HTMLEditor -prototype -getTitle)
      (fn []
        (this-as this
                 (let [file-path (.getPath this)]
                   (if file-path
                     (.basename path file-path)
                     "untitled")))))
(set! (.. HTMLEditor -prototype -getURI)
      (fn []
        (this-as this
          (.getPath this))))
(set! (.. HTMLEditor -prototype -getEncodedURI)
      (fn []
        (this-as this
          (.getPath this))))
(set! (.. HTMLEditor -prototype -copy)
      (fn [uri]
        (HTMLEditor. uri)))
(set! (.. HTMLEditor -prototype -destroy)
      (fn []
        (this-as this
          (.dispose (.-subscriptions this))
          (when (.-view this)
            (.destroy (.-view this)))
          (.emit (.-emitter this) "did-destroy"))))
(set! (.. HTMLEditor -prototype -isEqual)
      (fn [other-editor]
        (this-as this
                 (and true ;; check instance type
                      (= (.getURI this) (.getURI other-editor))))))
(set! (.. HTMLEditor -prototype -terminatePendingState)
      (fn []
        (this-as this
                 (when (.isEqual this (.getPendingItem (.getActivePane (.getCenter (.-workspace js/atom)))))
                   (.emit (.-emitter this) "did-terminate-pending-state")))))
(set! (.. HTMLEditor -prototype -onDidTerminatePendingState)
      (fn [callback]
        (this-as this
                 (.on (.-emitter this) "did-terminate-pending-state" callback))))
(set! (.. HTMLEditor -prototype -onDidChange)
      (fn [callback]
        (this-as this
                 (let [change-subscriptions (.onDidChange (.-file this) callback)]
                   (.add (.-subscriptions this) change-subscriptions)
                   change-subscriptions))))
(set! (.. HTMLEditor -prototype -onDidTitleChange)
      (fn [callback]
        (this-as this
                 (let [rename-subscriptions (.onDidRename (.-file this) callback)]
                   (.add (.-subscriptions this) rename-subscriptions)
                   rename-subscriptions))))
(set! (.. HTMLEditor -prototype -onDidDestroy)
      (fn [callback]
        (this-as this
                 (.on (.-emitter this) "did-destroy" callback))))
