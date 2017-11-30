(ns heron.core-test
  (:require
   [clojure.test :refer :all]
   [rhizome.viz :as viz])
  (:import
   [io.lacuna.bifurcan
    Set
    ISet
    IList]
   [io.lacuna.heron
    AutomatonBuilder
    State
    Utils]))

(defn any []
  (AutomatonBuilder/any))

(defn match [& s]
  (let [b (AutomatonBuilder.)]
    (doseq [x s]
      (.match b x))
    b))

(defn ->seq [^Iterable x]
  (when x
    (-> x .iterator iterator-seq)))

(defn view [^AutomatonBuilder builder]
  (viz/view-graph
    (cons nil (-> builder .states ->seq))
    (fn [^State s]
      (if (nil? s)
        [(.init builder)]
        (concat
          (-> s .downstreamStates ->seq)
          (-> s .epsilonTransitions ->seq)
          (-> s .defaultTransitions ->seq))))
    :options {:dpi 75}
    :vertical? false
    :node->descriptor (fn [^State s]
                        (if (nil? s)
                          {:width 0, :shape :plaintext}
                          {:shape :circle
                           :peripheries (when (-> builder .accept (.contains s)) 2)
                           :label (if (= State/REJECT s)
                                    "REJ"
                                    (str s))}))
    :edge->descriptor (fn [^State a ^State b]
                        (when a
                          (let [signals (concat
                                          (-> a (.signals b) ->seq)
                                          (when (-> a .epsilonTransitions (.contains b))
                                            ["\u03B5"])
                                          (when (-> a .defaultTransitions (.contains b))
                                            ["DEF"]))]
                            {:label (->> signals (interpose ", ") (apply str))})))))
