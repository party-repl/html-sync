(ns html-sync.html-editor
  (:require [clojure.string :as string]
            [cljs.nodejs :as node]
            [html-sync.common :as common]
            [html-sync.html-editor-view :as view]))

(def path (node/require "path"))
(def node-atom (node/require "atom"))

(def CompositeDisposible (.-CompositeDisposible node-atom))
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

(defn create-dom [uri])

(defn HTMLEditor [uri]
  (this-as this
           (set! (.-file this) (File. uri))
           (set! (.-subscriptions this) (CompositeDisposible.))
           (set! (.-emitter this) (Emitter.))
           (set! (.-element this) (.-getElement this))
           (set! (.-view this) (view/create-view (.-element this)))
           (set! (.-editorView this) nil)
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
                                                (.dispose (.-subscriptions this)))))))))
           this))

(set! (.. HTMLEditor -prototype -getElement)
      (fn []
        (this-as this
                 (and (.-view this)
                      (or (.-element (.-view this))
                          (.createDom js/DOM "div"))))))
(set! (.. HTMLEditor -prototype -getView)
      (fn []
        (this-as this
                 (when-not (.-editorView this)
                   (try
                     (set! (.-editorView this) (view/HTMLEditorView. this))
                     (.-editorView this)
                     (catch js/Error e
                       (common/console-log "ERROR:" "Cloud not create HTMLEditorView.")))))))
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
(set! (.. HTMLEditor -prototype -serialize)
      (fn []
        (this-as this
                 (js-obj "filePath" (.-getPath this)
                         "deserializer" (.-name (.-constructor this))))))
(set! (.. HTMLEditor -prototype -isEqual)
      (fn [other-editor]
        (this-as this
                 (= this other-editor))))
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
