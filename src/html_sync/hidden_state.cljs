(ns html-sync.hidden-state
  (:require [clojure.string :as string]
            [cljs.nodejs :as node]
            [cljs.reader :refer [read-string]]
            [goog.crypt.base64 :as base64]
            [html-sync.common :as common :refer [console-log]]))

(def node-atom (node/require "atom"))
(def AtomPoint (.-Point node-atom))
(def AtomRange (.-Range node-atom))

(def resize-handle-tag-name "ATOM-PANE-RESIZE-HANDLE")

(def hidden-state (atom {:change-count [-1]
                         :snapshots []}))

(def hidden-state-callbacks {:change-count (fn [state old-value new-value])})

(def hidden-workspace (atom {:hidden-pane nil
                             :hidden-editor nil}))

(def state-types (set (keys @hidden-state)))

(defn ^:private hide-resize-handle [pane-element]
  (let [handle-element (.-nextSibling pane-element)]
    (when (= resize-handle-tag-name (.-tagName handle-element))
      (set! (.-id handle-element) "hidden-resize-handle"))))

(defn encode-base64 [text]
  (base64/encodeString text))

(defn decode-base64 [text]
  (try
    (base64/decodeString (string/trim-newline text))
    (catch js/Error e
      (console-log "ERROR Could not decode because not encoded in base64" text))))

(defn ^:private find-state-type-for-row
  "Returns the state type for the row."
  [hidden-editor row-number]
  (when (< 0 row-number)
    (loop [row (dec row-number)]
      (let [text-in-line (string/trim-newline (.lineTextForBufferRow hidden-editor row))]
        (console-log "Seaching for state at row" row text-in-line)
        (if (and (string/starts-with? text-in-line ":")
                 (contains? state-types (read-string text-in-line)))
          (read-string text-in-line)
          (when (< 0 row)
            (recur (dec row))))))))

(defn ^:private find-first-row-for-state-type
  "Returns the row number of the first item for the state type."
  [hidden-editor state-type]
  (let [eof-row (.-row (.getEndPosition (.getBuffer hidden-editor)))]
    (loop [row 0]
      (let [text-in-line (string/trim-newline (.lineTextForBufferRow hidden-editor row))]
        (if (and (string/starts-with? text-in-line ":")
                 (= state-type (read-string text-in-line)))
          (inc row)
          (when (< row eof-row)
            (recur (inc row))))))))

(defn ^:private find-rows-for-state-type
  "Returns a tuple which defines the range of the array for the state type."
  [hidden-editor state-type]
  (when-let [first-row (find-first-row-for-state-type hidden-editor state-type)]
    (let [eof-row (.-row (.getEndPosition (.getBuffer hidden-editor)))]
      (loop [end-row (inc first-row)]
        (let [text-in-line (string/trim-newline (.lineTextForBufferRow hidden-editor end-row))]
          (if (or (string/starts-with? text-in-line ":")
                  (= "" (decode-base64 text-in-line)))
            [first-row end-row]
            (if (< end-row eof-row)
              (recur (inc end-row))
              [first-row eof-row])))))))

(defn ^:private delete-row [editor row]
  (.deleteRow (.getBuffer editor) row))

(defn ^:private replace-text-in-range [editor range text]
  (.setTextInBufferRange editor range text (js-obj "bypassReadOnly" true)))

(defn ^:private replace-text-at-row
  "Replaces the text in the row with the new text."
  [editor row text]
  (replace-text-in-range editor
                        (AtomRange. (AtomPoint. row 0) (AtomPoint. row js/Number.POSITIVE_INFINITY))
                        (encode-base64 text)))

(defn ^:private insert-text-at-row
  "Inserts new line with the text at the row in the hidden editor."
  [editor row text]
  (console-log "Inserting in hidden state at row" row text)
  (replace-text-in-range editor
                        (AtomRange. (AtomPoint. row 0) (AtomPoint. row 0))
                        (str (encode-base64 text) "\n")))

(defn ^:private get-next-pane [pane]
  (let [panes (.getPanes (.getActivePaneContainer (.-workspace js/atom)))
        current-index (.indexOf panes pane)
        next-index (mod (inc current-index) (inc (.-length panes)))]
    (aget panes next-index)))

(defn ^:private join-state-types
  "Joins the state type with the initial values. Since each row in the hidden
  editor will hold one value, initial values are joined with newline after
  being base64 encoded."
  [text [state-type initial-value]]
  (if-not (vector? initial-value)
    (console-log "ERROR:" "Hidden state needs to have an array as the initial value." state-type initial-value)
    (if (empty? initial-value)
      (str text state-type "\n\n")
      (str text state-type "\n" (string/join "\n" (reverse (map (comp encode-base64 str) initial-value))) "\n"))))

(defn initialize-hidden-state
  "Sets the content of the hidden editor as the given initial state."
  [hidden-editor initial-hidden-state]
  (let [final-text (reduce join-state-types "" initial-hidden-state)]
    (console-log "Initial State:" final-text)
    (.setText hidden-editor final-text)))

(defn ^:private close-editor
  "Searches through all the panes for the editor and destroys it."
  [editor]
  (when-let [pane (.paneForItem (.-workspace js/atom) editor)]
    (.destroyItem pane editor true)))

(defn ^:private add-listeners
  "This Pane should only contain our hidden buffers. When other editors
  accidently get placed in here, we want to move them to the next available
  Pane."
  [hidden-pane]
  (.add common/disposables
        (.onDidAddItem hidden-pane
                       (fn [event]
                         (let [item (.-item event)]
                           (when-not (= item (get @hidden-workspace :hidden-editor))
                             (console-log "Moving item to the next pane!" item)
                             (.moveItemToPane hidden-pane item (get-next-pane hidden-pane))))))))

(defn ^:private open-in-hidden-pane
  "Opens the given hidden editor in the pane when it already doesn't exists
  in the workspace. Otherwise, move the editor from the current pane to the
  hidden pane."
  [hidden-editor]
  (let [hidden-pane (get @hidden-workspace :hidden-pane)]
    (if-let [current-pane (.paneForItem (.-workspace js/atom) hidden-editor)]
      (do
        (.moveItemToPane current-pane hidden-editor hidden-pane)
        (.activateItem hidden-pane hidden-editor)
        (.activate hidden-pane))
      (do
        (.addItem hidden-pane hidden-editor (js-obj "moved" false))
        (.setActiveItem hidden-pane hidden-editor)))))

(defn ^:private create-hidden-editor
  "Builds a TextEditor and returns it."
  []
  (let [editor (.buildTextEditor (.-workspace js/atom)
                                 (js-obj "autoHeight" false))]
    (set! (.-isModified editor) (fn [] false))
    (set! (.-isModified (.getBuffer editor)) (fn [] false))
    (.setSoftWrapped editor false)
    (.add (.-classList (.-element editor)) "hidden-editor")
    (initialize-hidden-state editor @hidden-state)
    editor))

(defn ^:private collect-state-for-rows [hidden-editor first-row end-row]
  (loop [row (dec end-row)
         state []]
    (if (<= first-row row)
      (let [text-in-line (read-string (decode-base64 (string/trim-newline (.lineTextForBufferRow hidden-editor row))))]
        (recur (dec row) (conj state text-in-line)))
      state)))

(defn collect-state-for-state-type
  "Returns an array of values for the state type. Each value of an array is
  stored in each row in a reversed order, the top most row is the last value
  in the array."
  [hidden-editor state-type]
  (let [[first-row end-row] (find-rows-for-state-type hidden-editor state-type)]
    (collect-state-for-rows hidden-editor first-row end-row)))

(defn sync-hidden-state
  "Finds the state type for each change and calls an appropriate callback for it."
  [hidden-editor changes]
  (console-log "Hidden editor changes" changes)
  (doseq [change changes]
    (when (and (.-newRange change) (.-newText change))
      (when-let [state-type (find-state-type-for-row hidden-editor
                                                     (.-row (.-start (.-newRange change))))]
        (common/console-log "Change is for" state-type (.-newText change))
        (when-let [callback (get hidden-state-callbacks state-type)]
          (let [old-state (get @hidden-state state-type)
                new-state (collect-state-for-state-type hidden-editor state-type)]
            (swap! hidden-state assoc state-type new-state)
            (callback old-state new-state)))))))

(defn ^:private watch-hidden-editor
  "Adds a change listener on the hidden editor to sync the changes back
  to the local state."
  [hidden-editor]
  (console-log "Changing hidden state:" @hidden-state)
  (.add common/disposables
        (.onDidChange (.getBuffer hidden-editor)
                      (fn [event]
                        (sync-hidden-state hidden-editor (.-changes event))))))

(defn conj-hidden-state
  "Adds new value at the end of the array for the state type."
  [state-type new-value]
  (let [hidden-editor (get @hidden-workspace :hidden-editor)
        first-row (find-first-row-for-state-type hidden-editor state-type)]
    (when first-row
      (insert-text-at-row hidden-editor first-row new-value))))

(defn update-hidden-state
  ([state-type new-value]
    (update-hidden-state state-type 0 new-value))
  ([state-type index new-value]
    (let [hidden-editor (get @hidden-workspace :hidden-editor)
          [first-row end-row] (find-rows-for-state-type hidden-editor state-type)
          row (+ first-row index)]
      (if (< row end-row)
        (replace-text-at-row hidden-editor row new-value)
        (common/console-log "ERROR:" "Couldn't update hidden state:" state-type index new-value)))))

(defn get-hidden-state
  ([] @hidden-state)
  ([state-name]
    (if-not (keyword? state-name)
      (common/console-log "ERROR: Hidden state name needs to be a keyword." state-name)
      (get @hidden-state state-name))))

(defn ^:private transfer-hidden-state [hidden-editor]
  (let [eof-row (.-row (.getEndPosition (.getBuffer hidden-editor)))]
    (loop [row 0
           state {}]
      (if (< row eof-row)
        (let [text-in-line (string/trim-newline (.lineTextForBufferRow hidden-editor row))]
          (if (string/starts-with? text-in-line ":")
            (let [state-type (read-string text-in-line)
                  values (collect-state-for-state-type hidden-editor state-type)]
              (recur (+ row (count values)) (assoc state state-type values)))))
        state))))

(defn link-teletyped-hidden-editor
  "Overrides the hidden editor reference with the new one that's been shared
  through Teletype.

  NOTE: If we want to support multiple HTML files and have one hidden state
  per  HTML file, keep the hidden editor by the file path."
  [hidden-editor]
  (let [original-hidden-editor (get @hidden-workspace :hidden-editor)]
    (swap! hidden-workspace assoc :hidden-editor hidden-editor)
    (transfer-hidden-state hidden-editor)
    (open-in-hidden-pane hidden-editor)
    (.add common/disposables
          (.onDidDestroy hidden-editor
                         (fn []
                           (swap! hidden-workspace assoc :hidden-editor original-hidden-editor)
                           (transfer-hidden-state hidden-editor))))))

(defn clean-up-hidden-editor
  "Closes a hidden editor in the workspace. This should be called when Atom
  initializes the package because the editor might not have been cleaned up
  properly before."
  []
  (let [pane-items (.getPaneItems (.-workspace js/atom))]
    (doseq [pane-item pane-items]
      (when-let [title (.getTitle pane-item)]
        (when (string/starts-with? title common/hidden-editor-title)
          (close-editor pane-item))))))

(defn sync-hidden-editor
  "Focuses on the hidden editor in order to allow Teletype to share the editor."
  []
  (let [editor (get @hidden-workspace :hidden-editor)]
    (when-let [pane (.paneForItem (.-workspace js/atom) editor)]
    (.activateItem pane editor)
    (.activate pane))))

(defn destroy-hidden-pane
  "Destroys the Pane. If this is the last Pane, all the items inside it will be
  destroyed but the pane will not be destroyed. Would this ever happen?"
  []
  (let [{:keys [hidden-pane hidden-editor]} @hidden-workspace]
    (when hidden-editor
      (close-editor hidden-editor)
      (swap! hidden-workspace assoc :hidden-editor nil))
    (.destroy hidden-pane)
    (swap! hidden-workspace assoc :hidden-pane nil)))

(defn create-hidden-pane
  "Creates a hidden pane on the left most and returns it."
  []
  (let [pane (.getActivePane (.-workspace js/atom))
        left-most-pane (.findLeftmostSibling pane)
        hidden-pane (.splitLeft left-most-pane)
        hidden-pane-element (.getElement hidden-pane)]
    (set! (.-id hidden-pane-element) (str common/package-name "-hidden-pane"))
    (hide-resize-handle hidden-pane-element)
    (swap! hidden-workspace assoc :hidden-pane hidden-pane)
    (add-listeners hidden-pane)
    hidden-pane))

(defn create-hidden-state
  "Creates a new Pane to the very left of the PaneContainer. Since Atom will
  always have at least one Pane, even when you haven't opened a file,
  we can safely create it in all starting cases."
  []
  (let [hidden-pane (create-hidden-pane)
        hidden-editor (create-hidden-editor)]
    (swap! hidden-workspace assoc :hidden-editor hidden-editor)
    (.setPath (.getBuffer hidden-editor) (.resolvePath (.-project js/atom) common/hidden-editor-title))
    (open-in-hidden-pane hidden-editor)
    (watch-hidden-editor hidden-editor)
    (.activate hidden-pane)))
