(ns html-sync.guest
  (:require [clojure.string :as string]
            [cljs.nodejs :as node]
            [html-sync.common :as common]
            [html-sync.hidden-state :as hidden-state]))

(defn teletyped-hidden-editor?
  "Returns true if the title includes the hidden editor title, but doesn't
  start with it. This is because Teletype adds the host's username at the
  beginning of the title."
  [title]
  (and (string/includes? title common/hidden-editor-title)
       (not (string/starts-with? title common/hidden-editor-title))))

(defn look-for-teletyped-hidden-editor
  "Whenever a new text editor opens in Atom, check the title and look for
  hidden editors that opened through teletype."
  []
  (.add common/disposables
    (-> (.-workspace js/atom)
        (.onDidAddTextEditor
          (fn [event]
            (let [editor (.-textEditor event)
                  title (.getTitle editor)]
              (common/console-log "Teletyped? " title)
              (when (teletyped-hidden-editor? title)
                (hidden-state/link-teletyped-hidden-editor editor))))))))
