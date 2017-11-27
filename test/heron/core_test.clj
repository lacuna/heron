(ns heron.core-test
  (:require
   [clojure.test :refer :all]
   [heron.core :refer :all]
   [rhizome.viz :as viz])
  (:import
   [io.lacuna.bifurcan
    ISet
    IList]
   [io.lacuna.heron
    AutomatonBuilder
    State]))

(defn match [s]
  (.matchAll (AutomatonBuilder.) s))

(defn ->seq [^Iterable x]
  (when x
    (-> x .iterator iterator-seq)))

(defn view [^AutomatonBuilder builder]
  (viz/view-graph
    (-> builder .states ->seq)
    (fn [^State s]
      (concat
        (-> s .transitions .keys ->seq)
        (-> s .epsilonTransitions ->seq)
        (when-let [d (.defaultTransition s)]
          [d])))
    :options {:dpi 100}
    :vertical? false
    :node->descriptor (fn [^State s]
                        {:shape :circle
                         :peripheries (when (-> builder .accept (.contains s)) 2)
                         :label (when (= State/REJECT s) "REJ")})
    :edge->descriptor (fn [^State a ^State b]
                        (let [signals (concat
                                        (-> a .transitions (.get b nil) ->seq)
                                        (when (some-> a .epsilonTransitions (.contains b))
                                          ["\u03B5"])
                                        (when (= b (.defaultTransition b))
                                          ["DEF"]))]
                          {:label (->> signals (interpose ", ") (apply str))}))))
