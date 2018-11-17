(ns html-sync.html-editor-view
  (:require [clojure.string :as string]
            [cljs.nodejs :as node]))

(def path (node/require "path"))
(def node-atom (node/require "atom"))
(def etch (node/require "etch"))

(def CompositeDisposible (.-CompositeDisposible node-atom))
(def File (.-File node-atom))
(def Emitter (.-Emitter node-atom))

(defn HTMLEditorView [editor]
  (this-as this
           (set! (.-editor this) editor)
           (set! (.-emitter this) (Emitter.))
           (set! (.-disposables this) (CompositeDisposible.))
           (.initialize etch this)
           this))

(set! (.. HTMLEditorView -prototype -onDidLoad)
      (fn [callback]
        (this-as this
                 (.on (.-emitter this) "did-load") callback)))
(set! (.. HTMLEditorView -prototype -update)
      (fn []))
(set! (.. HTMLEditorView -prototype -destroy)
      (fn []
        (this-as this
                 (.dispose (.-disposables this))
                 (.dispose (.-emitter this))
                 (.destroy etch this))))
(set! (.. HTMLEditorView -prototype -render)
      (fn []
        "<div className=\"html-sync html-editor-view\">
          <div clossName=\"html-editor-controls\"></div>
          <iframe></iframe>
        </div>"))
