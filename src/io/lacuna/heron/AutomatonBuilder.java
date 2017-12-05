package io.lacuna.heron;

import io.lacuna.bifurcan.*;
import io.lacuna.bifurcan.IMap.IEntry;

import java.util.Objects;
import java.util.function.BiFunction;
import java.util.function.BinaryOperator;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static io.lacuna.heron.Utils.zipMap;

/**
 * @param <S> the signals that trigger transitions between states
 * @param <T> the tags applied to states
 */
public class AutomatonBuilder<S, T> {

  private static final Object OTHER_SIGNAL = new Object();

  public State<S, T> init;
  public LinearSet<State<S, T>> states, accept;
  boolean deterministic = true;

  public AutomatonBuilder() {
    State<S, T> s = new State();

    init = s;
    accept = LinearSet.of(s);
    states = accept.clone();
  }

  private AutomatonBuilder(State<S, T> init, LinearSet<State<S, T>> states, LinearSet<State<S, T>> accept) {
    this.init = init;
    this.states = states;
    this.accept = accept;
  }

  /// combinators

  /**
   * @param <S> the signals that trigger transitions between states
   * @param <T> the tags applied to states
   * @return an automaton that accepts any input
   */
  public static <S, T> AutomatonBuilder<S, T> any() {
    State<S, T> a = new State<>();
    State<S, T> b = new State<>();
    a.addDefault(b);

    return new AutomatonBuilder<>(a, LinearSet.of(a, b), LinearSet.of(b));
  }

  /**
   * @param <S> the signals that trigger transitions between states
   * @param <T> the tags applied to states
   * @return an automaton that rejects any input
   */
  public static <S, T> AutomatonBuilder<S, T> none() {
    State<S, T> a = new State<>();
    a.addDefault(State.REJECT);

    return new AutomatonBuilder<S, T>(a, LinearSet.of(a, State.REJECT), LinearSet.of());
  }

  /**
   * @return the current builder, with the current accept states tagged as {@code tag}
   */
  public AutomatonBuilder tag(T tag) {
    accept.forEach(s -> s.addTag(tag));

    return this;
  }

  /**
   * @return the current builder, extended to match {@code signal} after its current accept states
   */
  public AutomatonBuilder match(S signal) {
    State<S, T> newState = new State<>();
    states.add(newState);
    accept.forEach(s -> s.addTransition(signal, newState));
    accept = LinearSet.of(newState);
    deterministic = false;

    return this;
  }

  /**
   * @return the current builder, extended to match anything but {@code signal} after its current accept states
   */
  public AutomatonBuilder not(S signal) {
    State<S, T> newAccept = new State<>();
    for (State<S, T> s : accept) {
      s.addTransition(signal, State.REJECT);
      s.addDefault(newAccept);
    }

    accept = LinearSet.of(newAccept);
    states.add(newAccept).add(State.REJECT);
    deterministic = false;

    return this;
  }

  /**
   * @return the current builder, extended to match {@code builder} after its current accept states
   */
  public AutomatonBuilder<S, T> concat(AutomatonBuilder<S, T> builder) {
    builder = builder.clone();

    for (State<S, T> a : accept) {
      a.addEpsilon(builder.init);
    }

    builder.states.forEach(states::add);
    accept = builder.accept;
    deterministic = false;

    return this;
  }

  /**
   * @return the current builder, updated to match its pattern zero or one times
   */
  public AutomatonBuilder<S, T> maybe() {
    accept.add(init);
    deterministic = false;

    return this;
  }

  /**
   * @return the current builder, updated to match its pattern zero or more times
   */
  public AutomatonBuilder<S, T> kleene() {
    accept.forEach(s -> s.addEpsilon(init));
    accept.add(init);

    deterministic = false;
    toDFA();

    return this;
  }

  /**
   * @return the current builder, updated to match everything but its current pattern
   */
  public AutomatonBuilder<S, T> complement() {

    toDFA();

    State<S, T> newAccept = new State<>();
    newAccept.addDefault(newAccept);

    states.remove(State.REJECT);

    for (State<S, T> state : states) {
      if (state.defaultTransitions().size() == 0
              || state.defaultTransitions().contains(State.REJECT)) {
        state.defaultTransitions = LinearSet.of(newAccept);
      }

      state.transitions = Utils.mapVals(
              state.transitions,
              s -> s.contains(State.REJECT) ? s.remove(State.REJECT).add(newAccept) : s);
    }

    states.add(newAccept);
    accept = states.difference(accept).add(newAccept);

    deterministic = false;
    toDFA();

    return this;
  }

  /**
   * @return the current builder, modified to match either its current pattern or the pattern specified by {@code builder}
   */
  public AutomatonBuilder<S, T> union(AutomatonBuilder<S, T> builder) {

    toDFA();
    AutomatonBuilder<S, T> b = builder.clone();
    b.toDFA();

    LinearMap<IList<State<S, T>>, State<S, T>> cache = new LinearMap<>();

    this.init = State.join(
            LinearList.of(init, b.init),
            t -> State.REJECT == t.nth(0) && State.REJECT == t.nth(1),
            cache);

    this.states = states.union(b.states).union(LinearSet.from(cache.values()));

    this.accept = cache.stream()
            .filter(e -> accept.contains(e.key().nth(0)) || b.accept.contains(e.key().nth(1)))
            .map(IEntry::value)
            .collect(Sets.linearCollector())
            .union(this.accept)
            .union(b.accept);

    deterministic = false;
    toDFA();

    return this;
  }

  /**
   * @return the current builder, modified to match both its current pattern and the pattern specified by {@code builder}
   */
  public AutomatonBuilder<S, T> intersection(AutomatonBuilder<S, T> builder) {

    toDFA();
    AutomatonBuilder<S, T> b = builder.clone();
    b.toDFA();

    LinearMap<IList<State<S, T>>, State<S, T>> cache = new LinearMap<>();

    this.init = State.join(
            LinearList.of(init, b.init),
            t -> State.REJECT == t.nth(0) || State.REJECT == t.nth(1),
            cache);

    this.states = this.states.union(b.states).union(LinearSet.from(cache.values()));

    this.accept = cache.stream()
            .filter(e -> accept.contains(e.key().nth(0)) && b.accept.contains(e.key().nth(1)))
            .map(IEntry::value)
            .collect(Sets.linearCollector());

    deterministic = false;
    toDFA();

    return this;
  }

  /**
   * @return the current builder, modified to match its current pattern, less the pattern specified by {@code builder}
   */
  public AutomatonBuilder<S, T> difference(AutomatonBuilder<S, T> builder) {

    toDFA();
    AutomatonBuilder<S, T> b = builder.clone();
    b.toDFA();

    LinearMap<IList<State<S, T>>, State<S, T>> cache = new LinearMap<>();

    this.init = State.join(
            LinearList.of(init, b.init),
            t -> State.REJECT == t.nth(0),
            cache);

    this.states = this.states.union(b.states).union(LinearSet.from(cache.values()));

    this.accept = cache.entries().stream()
            .filter(e -> accept.contains(e.key().nth(0)) && !b.accept.contains(e.key().nth(1)))
            .map(IEntry::value)
            .collect(Sets.linearCollector())
            .union(this.accept);

    deterministic = false;
    toDFA();

    return this;
  }

  @Override
  public AutomatonBuilder<S, T> clone() {
    LinearMap<State<S, T>, State<S, T>> cache = new LinearMap<>();

    return new AutomatonBuilder<>(
            init.clone(cache),
            Utils.map(states, s -> s.clone(cache)),
            Utils.map(accept, s -> s.clone(cache)));
  }

  ///

  // reduces any divergent transitions to a single state
  public void toDFA() {

    if (deterministic) {
      return;
    }

    LinearMap<ISet<State<S, T>>, ISet<State<S, T>>> cache = new LinearMap<>();

    this.init = State.merge(LinearSet.of(init), State::epsilonClosure, cache);

    this.accept = cache.stream()
            .filter(e -> e.key().containsAny(this.accept))
            .map(e -> e.value())
            .flatMap(ISet::stream)
            .collect(Sets.linearCollector());

    this.states = cache.values().stream()
            .flatMap(ISet::stream)
            .collect(Sets.linearCollector());

    deterministic = true;
  }

  // collapses any equivalent states
  public void minimize() {

    LinearMap<ISet<State<S, T>>, ISet<State<S, T>>> cache = new LinearMap<>();

    IMap<State<S, T>, ISet<State<S, T>>> equivalent = equivalentStates();

    this.init = State.merge(
            LinearSet.of(init),
            set -> set.stream().map(s -> equivalent.get(s).get()).reduce(ISet::union).get(),
            cache);

    this.accept = cache.stream()
            .filter(e -> e.key().containsAny(this.accept))
            .map(e -> e.value())
            .flatMap(ISet::stream)
            .collect(Sets.linearCollector());

    this.states = cache.values().stream()
            .flatMap(ISet::stream)
            .collect(Sets.linearCollector());
  }


  ISet<S> alphabet() {
    LinearSet<S> alphabet = new LinearSet<>();
    for (State<S, T> s : states) {
      s.transitions.keys().forEach(alphabet::add);
    }
    return alphabet;
  }

  // given a set of states and a signal, returns the set of states which follow that transition into the set
  private BiFunction<ISet<State<S, T>>, Object, ISet<State<S, T>>> inverseTransitions() {

    ISet alphabet = ((ISet) alphabet()).add(OTHER_SIGNAL);

    IMap<State<S, T>, IMap<Object, ISet<State<S, T>>>> result = new LinearMap<>();
    states.stream().forEach(s -> result.put(s, new LinearMap<>()));
    LinearMap<Object, ISet<State<S, T>>> reject = new LinearMap<>();
    result.put(State.REJECT, reject);

    for (State<S, T> state : states) {
      for (IEntry<S, ISet<State<S, T>>> e : state.transitions) {
        result.get(e.value().elements().first()).get()
                .getOrCreate(e.key(), LinearSet::new)
                .add(state);
      }

      if (state.defaultTransitions().size() > 0) {
        IMap<Object, ISet<State<S, T>>> t = result.get(state.defaultTransitions().elements().first()).get();
        for (Object signal : alphabet.difference(state.transitions.keys())) {
          t.getOrCreate(signal, LinearSet::new).add(state);
        }
      } else {
        for (Object signal : alphabet.difference(state.transitions.keys())) {
          reject.getOrCreate(signal, LinearSet::new).add(state);
        }
      }
    }

    return (states, signal) -> states.stream()
            .map(s -> result.get(s).get().get(signal, (ISet<State<S, T>>) Sets.EMPTY))
            .reduce(ISet::union)
            .get();
  }

  //
  private IMap<State<S, T>, ISet<State<S, T>>> equivalentStates() {

    toDFA();

    BiFunction<ISet<State<S, T>>, Object, ISet<State<S, T>>> inverseTransitions = inverseTransitions();

    ISet alphabet = ((ISet) alphabet()).add(OTHER_SIGNAL);

    IMap<Object, ISet<ISet<State<S, T>>>> waiting = new LinearMap<>();
    ISet<ISet<State<S, T>>> splitters = LinearSet.<ISet<State<S, T>>>of(accept, LinearSet.of(State.REJECT)).remove(Sets.EMPTY);
    alphabet.forEach(s -> waiting.put(s, LinearSet.from(splitters)));

    ISet<ISet<State<S, T>>> srcs = LinearSet.<ISet<State<S, T>>>of(
            accept,
            states.difference(accept).remove(State.REJECT),
            LinearSet.of(State.REJECT))
            .remove(Sets.EMPTY);

    srcs = srcs.stream()
            .map(s -> Utils.groupBy(s, State::tags).values())
            .flatMap(IList::stream)
            .collect(Sets.linearCollector());

    while (waiting.size() > 0) {
      IEntry<Object, ISet<ISet<State<S, T>>>> e = waiting.entries().first();
      Object signal = e.key();

      ISet<ISet<State<S, T>>> dsts = e.value();
      ISet<State<S, T>> dst = dsts.elements().first();
      dsts.remove(dst);

      ISet<State<S, T>> accumulator = new LinearSet<>();

      for (ISet<State<S, T>> src : LinearList.from(srcs.elements())) {
        ISet<State<S, T>> splitter = inverseTransitions.apply(dst, signal);
        if (src.containsAny(splitter) && !splitter.containsAll(src)) {
          ISet<State<S, T>> p = splitter.intersection(src);
          ISet<State<S, T>> q = src.difference(p);

          srcs.remove(src).add(p).add(q);
          if (dsts.contains(src)) {
            dsts.remove(src).add(p).add(q);
          }

          (p.size() < q.size() ? p : q).forEach(accumulator::add);
        }
      }

      if (accumulator.size() > 0) {
        for (Object s : alphabet) {
          waiting.getOrCreate(s, LinearSet::new).add(accumulator);
        }
      }

      if (dsts.size() == 0) {
        waiting.remove(signal);
      }
    }

    LinearMap<State<S, T>, ISet<State<S, T>>> m = new LinearMap<>();
    for (ISet<State<S, T>> set : srcs) {
      set.forEach(s -> m.put(s, set));
    }
    return m;
  }

  // removes any references to the specified states
  private void prune(ISet<State<S, T>> toRemove) {

    toDFA();

    states = states.difference(toRemove);

    for (State<S, T> state : states) {
      if (state.defaultTransitions().size() == 0) {
        state.transitions = state.transitions.stream()
                .filter(e -> !toRemove.containsAll(e.value()))
                .collect(Maps.linearCollector(IEntry::key, IEntry::value));
      } else {
        IMap<S, ISet<State<S, T>>> cleaned = state.transitions.stream()
                .filter(e -> toRemove.containsAll(e.value()))
                .collect(Maps.linearCollector(IEntry::key, x -> LinearSet.of((State<S, T>) State.REJECT)));
        if (cleaned.size() > 0) {
          state.transitions = state.transitions.union(cleaned);
          states.add(State.REJECT);
        }

        if (toRemove.containsAll(state.defaultTransitions())) {
          state.defaultTransitions = null;
        }
      }
    }
  }

  //
  private ISet<State<S, T>> deadStates() {

    toDFA();

    IMap<State<S, T>, ISet<State<S, T>>> downstream =
            zipMap(states.difference(accept).stream(),
                    s -> s.transitions.values().stream()
                            .flatMap(ISet::stream)
                            .collect(Sets.linearCollector())
                            .remove(s));

    ISet<State<S, T>> dead = new LinearSet<>();

    for (; ; ) {
      ISet<State<S, T>> prevDead = dead;
      dead = downstream.stream()
              .filter(e -> prevDead.containsAll(e.value()))
              .map(IEntry::key)
              .collect(Sets.linearCollector())
              .union(prevDead);

      if (prevDead.size() == dead.size()) {
        return dead;
      }
    }
  }

  IMap<State<S, T>, Integer> rankOrdering() {
    ISet<State<S, T>> states = new LinearSet<>();
    LinearList<State<S, T>> queue = LinearList.of(init);

    while (queue.size() > 0) {
      State<S, T> s = queue.popFirst();
      if (!states.contains(s)) {
        states.add(s);

        s.transitions.values().stream()
                .flatMap(ISet::stream)
                .collect(Sets.linearCollector())
                .difference(states)
                .forEach(queue::addLast);
      }
    }

    return IntStream.of((int) states.size())
            .boxed()
            .collect(Maps.linearCollector(
                    n -> states.elements().nth(n),
                    n -> n));
  }
}
