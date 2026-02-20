(ns user
  "REPL helpers for portfolio development.

   Start:   (start!)
   Stop:    (stop!)
   CLJS:    (cljs-repl!)
   Quit:    :cljs/quit"
  (:require [shadow.cljs.devtools.server :as server]
            [shadow.cljs.devtools.api :as shadow]))

(defn start!
  "Start shadow-cljs server + watch :app build."
  []
  (server/start!)
  (shadow/watch :app))

(defn stop!
  "Stop shadow-cljs server."
  []
  (server/stop!))

(defn cljs-repl!
  "Connect to browser CLJS REPL. Exit with :cljs/quit"
  []
  (shadow/repl :app))

(comment
  (start!)
  (stop!)
  (cljs-repl!))
