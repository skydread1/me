(ns loicb.me.ui.core
  "Portfolio SPA entry point — dispatch-of effect pattern.

   dispatch-of creates a dispatch function that routes effect maps:
   - :db      — pure state updater (swap! app-db update root-key f)
   - :history — pushState URL update

   No server communication. All data embedded at compile time."
  (:require #?(:cljs [replicant.dom :as r])
            [loicb.me.ui.core.db :as db]
            #?(:cljs [loicb.me.ui.core.views :as views])
            [loicb.me.ui.core.history :as history]))

(def root-key :app/me)

#?(:cljs (defonce app-db (atom {root-key db/initial-db})))

;;=============================================================================
;; Scroll
;;=============================================================================

(def scroll-keys [:view :tag-filter :selected-slug])

(defn should-scroll-top?
  "Check if navigation warrants scrolling to top."
  [old-state new-state]
  (not= (select-keys old-state scroll-keys)
        (select-keys new-state scroll-keys)))

^:rct/test
(comment
  (should-scroll-top? {:view :home :tag-filter nil :selected-slug nil}
                      {:view :detail :tag-filter nil :selected-slug "x"})
  ;=> true

  (should-scroll-top? {:view :home :tag-filter nil :selected-slug nil}
                      {:view :home :tag-filter nil :selected-slug nil})
  ;=> false

  (should-scroll-top? {:view :home :tag-filter nil :selected-slug nil}
                      {:view :home :tag-filter "clojure" :selected-slug nil})
  ;=> true
  )

;;=============================================================================
;; Dispatch
;;=============================================================================

(def effect-order
  "Execution order for effect types.
   :db      — state update first (history sees updated state)
   :history — pushState last (URL reflects final state)"
  [:db :history])

#?(:cljs
   (defn dispatch-of
     "Create a stable dispatch function that routes effect maps.
      Returns a single closure with a self-reference via volatile.
      Effects execute in `effect-order`, not map iteration order."
     [app-db root-key]
     (let [self (volatile! nil)
           dispatch!
           (fn [effects]
             (doseq [type effect-order
                     :let [effect-def (get effects type)]
                     :when (some? effect-def)]
               (case type
                 :db      (swap! app-db update root-key effect-def)
                 :history (history/push-state! (get @app-db root-key)))))]
       (vreset! self dispatch!)
       dispatch!)))

#?(:cljs (def dispatch! (dispatch-of app-db root-key)))

#?(:cljs
   (defn ^:export render!
     "Trigger re-render after hot reload."
     []
     (swap! app-db identity)))

;;=============================================================================
;; Rendering
;;=============================================================================

#?(:cljs
   (add-watch app-db :render
              (fn [_ _ old-state new-state]
                (let [old-db (root-key old-state)
                      new-db (root-key new-state)]
                  (when-let [el (js/document.getElementById "app")]
                    (r/render el (views/app-view
                                  {:loicb.me.ui.core.views/db        new-db
                                   :loicb.me.ui.core.views/dispatch! dispatch!})))
                  (when (should-scroll-top? old-db new-db)
                    (js/window.scrollTo 0 0))))))

;;=============================================================================
;; Navigation
;;=============================================================================

#?(:cljs
   (defn on-popstate
     "Handle browser back/forward. Parses URL and dispatches state change."
     [parsed]
     (let [{:keys [view slug tag]} parsed]
       (case view
         :home   (dispatch! {:db (fn [d] (assoc d :view :home :tag-filter tag :selected-slug nil))})
         :detail (dispatch! {:db (fn [d] (db/select-post d slug))})
         nil))))

#?(:cljs
   (defn init-from-url!
     "Initialize state from current URL path."
     []
     (when-let [parsed (history/path->state (history/current-path))]
       (on-popstate parsed)
       (history/replace-state! (get @app-db root-key)))))

;;=============================================================================
;; Theme
;;=============================================================================

#?(:cljs
   (defn ^:export toggle-theme!
     "Toggle dark/light theme. Persists to localStorage."
     []
     (let [body      (.-body js/document)
           current   (.getAttribute body "data-theme")
           new-theme (if (= current "dark") "light" "dark")]
       (.setAttribute body "data-theme" new-theme)
       (js/localStorage.setItem "theme" new-theme)
       (swap! app-db identity))))

#?(:cljs
   (defn init-theme!
     "Apply saved theme on load."
     []
     (when (= (js/localStorage.getItem "theme") "dark")
       (.setAttribute (.-body js/document) "data-theme" "dark"))))

;;=============================================================================
;; Initialization
;;=============================================================================

#?(:cljs
   (defn ^:export init!
     []
     (init-theme!)
     (init-from-url!)
     (swap! app-db identity)))

#?(:cljs (defonce _popstate (.addEventListener js/window "popstate" (partial history/on-popstate! on-popstate))))
#?(:cljs (defonce _init (init!)))
