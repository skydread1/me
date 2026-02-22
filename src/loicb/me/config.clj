(ns loicb.me.config
  "Reads config.edn at build/macro-expansion time.
   Provides config map for JVM code and a macro for CLJS embedding."
  (:require [clojure.edn :as edn]))

(def config
  (edn/read-string (slurp "config.edn")))

(defmacro site-config
  "Embed site metadata in CLJS bundle at compile time."
  []
  `~(:site config))

(def version
  (:version (edn/read-string (slurp "resources/version.edn"))))

(defmacro app-version
  "Embed app version string in CLJS bundle at compile time."
  []
  version)
