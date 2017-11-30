(ns heron.generative-test
  (:require
   [heron.core-test :refer (view)]
   [clojure.test.check.generators :as gen]
   [clojure.test.check.properties :as prop]
   [clojure.test.check.clojure-test :as ct :refer (defspec)]
   [clojure.test :refer :all])
  (:import
   [io.lacuna.bifurcan
    ISet
    IMap
    IList]
   [io.lacuna.heron
    AutomatonBuilder
    State
    Utils]))

(defn ->seq [^Iterable x]
  (when x
    (-> x .iterator iterator-seq)))

(defn gen-actions [size]
  (gen/one-of
    (concat
      [(gen/return [:match 0])
       (gen/return [:match 1])
       (gen/return [:not 0])
       (gen/return [:not 1])]
      (when (pos? size)
        (let [gen-actions' (gen/resize (quot size 2) (gen/sized gen-actions))]
          [(gen/return [:kleene])
           (gen/return [:maybe])
           (gen/tuple (gen/return :concat) (gen/vector gen-actions' 0 size))
           (gen/tuple (gen/return :union) (gen/vector gen-actions' 0 size))
           (gen/tuple (gen/return :intersection) (gen/vector gen-actions' 0 size))
           (gen/tuple (gen/return :difference) (gen/vector gen-actions' 0 size))])))))

(defn construct-automaton [actions]
  (doto
      (reduce
        (fn [^AutomatonBuilder fsm [action arg]]
          (case action
            :match        (.match fsm arg)
            :not          (.not fsm arg)
            :kleene       (.kleene fsm)
            :maybe        (.maybe fsm)
            :concat       (.concat fsm (construct-automaton arg))
            :union        (.union fsm (construct-automaton arg))
            :intersection (.intersection fsm (construct-automaton arg))
            :difference   (.difference fsm (construct-automaton arg))))
        (AutomatonBuilder.)
        actions)
    .toDFA))

(defn accepts? [^AutomatonBuilder a inputs]
  (loop [state (.init a), inputs inputs]
    (if (empty? inputs)
      (.contains (.accept a) state)
      (if-let [state' (some->
                        (or
                          (->seq (.transitions state (first inputs)))
                          (->seq (.defaultTransitions state)))
                        first)]
        (recur state' (rest inputs))
        false))))

(def iterations 1e4)

(defmacro def-generative-test [name [inputs & actions] & body]
  `(defspec ~name iterations
     (prop/for-all [~@(interleave
                        actions
                        (repeatedly
                          (fn []
                            `(gen/vector
                               (gen/resize 8
                                 (gen/sized gen-actions))
                               0 4))))
                    ~inputs (gen/vector
                              (gen/vector (gen/elements [0 1]) 0 16)
                              10 10)]
       ~@body)))

(def-generative-test test-kleene [inputs actions]
  (let [a        (construct-automaton actions)
        accepted (filter #(accepts? a %) inputs)]
    (->> inputs
      (filter #(accepts? a %))
      (every?
        (fn [s]
          (let [a' (doto a .kleene .toDFA)]
            (and (accepts? a' [])
              (accepts? a' s)
              (accepts? a' (concat s s)))))))))

(def-generative-test test-maybe [inputs actions]
  (let [a        (construct-automaton actions)
        accepted (filter #(accepts? a %) inputs)]
    (->> inputs
      (filter #(accepts? a %))
      (every?
        (fn [s]
          (let [a' (doto a .maybe .toDFA)]
            (and (accepts? a' [])
              (accepts? a' s))))))))
