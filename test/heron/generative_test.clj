(ns heron.generative-test
  (:require
   [heron.core-test :refer (view)]
   [clojure.test.check.generators :as gen]
   [clojure.test.check.properties :as prop]
   [clojure.test.check.clojure-test :as ct :refer (defspec)]
   [clojure.set :as set]
   [clojure.test :refer :all])
  (:import
   [io.lacuna.bifurcan
    LinearSet
    LinearList
    Sets
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

(def iterations 1e6)

(defmacro def-generative-test [name [inputs & actions] & body]
  `(defspec ~name iterations
     (prop/for-all [~inputs (gen/vector
                              (gen/vector (gen/elements [0 1]) 0 16)
                              0 20)
                    ~@(interleave
                        actions
                        (repeatedly
                          (fn []
                            `(gen/vector
                               (gen/resize 4
                                 (gen/sized gen-actions))
                               0 4))))]
       ~@body)))

(def-generative-test test-minimize [inputs actions]
  (let [a        (construct-automaton actions)
        a'       (doto (.clone a) .minimize)
        accepted (filter #(accepts? a %) inputs)]
    (= (filter #(accepts? a %) inputs)
      (filter #(accepts? a' %) inputs))))

(def-generative-test test-kleene [inputs actions]
  (let [a        (construct-automaton actions)
        a'       (doto (-> a .clone .kleene) .toDFA)
        accepted (filter #(accepts? a %) inputs)]
    #_(prn (-> a .states .size))
    (->> inputs
      (filter #(accepts? a %))
      (every?
        (fn [s]
          (and (accepts? a' [])
            (accepts? a' s)
            (accepts? a' (concat s s))))))))

(def-generative-test test-maybe [inputs actions]
  (let [a        (construct-automaton actions)
        a'       (doto (-> a .clone .maybe) .toDFA)
        accepted (filter #(accepts? a %) inputs)]
    #_(prn (-> a .states .size))
    (->> inputs
      (filter #(accepts? a %))
      (every?
        (fn [s]
          (and (accepts? a' [])
            (accepts? a' s)))))))

(def-generative-test test-union [inputs a b]
  (let [a      (construct-automaton a)
        b      (construct-automaton b)
        i-a    (set (filter #(accepts? a %) inputs))
        i-b    (set (filter #(accepts? b %) inputs))
        merged (.union a b)]
    #_(prn (-> merged .states .size))
    (->> inputs
      (filter #(accepts? merged %))
      set
      (= (set/union i-a i-b)))))

(def-generative-test test-intersection [inputs a b]
  (let [a      (construct-automaton a)
        b      (construct-automaton b)
        i-a    (set (filter #(accepts? a %) inputs))
        i-b    (set (filter #(accepts? b %) inputs))
        merged (.intersection a b)]
    #_(prn (-> merged .states .size))
    (->> inputs
      (filter #(accepts? merged %))
      set
      (= (set/intersection i-a i-b)))))

(def-generative-test test-difference [inputs a b]
  (let [a      (construct-automaton a)
        b      (construct-automaton b)
        i-a    (set (filter #(accepts? a %) inputs))
        i-b    (set (filter #(accepts? b %) inputs))
        merged (.difference a b)]
    #_(prn (-> merged .states .size))
    (->> inputs
      (filter #(accepts? merged %))
      set
      (= (set/difference i-a i-b)))))
