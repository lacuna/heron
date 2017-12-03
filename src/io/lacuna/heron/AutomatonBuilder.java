package io.lacuna.heron;

import io.lacuna.bifurcan.*;
import io.lacuna.bifurcan.IMap.IEntry;

import java.util.Objects;
import java.util.function.BiFunction;
import java.util.function.BinaryOperator;
import java.util.function.Function;

/**
 * @author ztellman
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

  public static <S, T> AutomatonBuilder<S, T> any() {
    State<S, T> a = new State<>();
    State<S, T> b = new State<>();
    a.addDefault(b);

    return new AutomatonBuilder<>(a, LinearSet.of(a, b), LinearSet.of(b));
  }

  public static <S, T> AutomatonBuilder<S, T> none() {
    State<S, T> a = new State<>();
    a.addDefault(State.REJECT);

    return new AutomatonBuilder<S, T>(a, LinearSet.of(a, State.REJECT), LinearSet.of());
  }

  public AutomatonBuilder tag(T tag) {
    accept.forEach(s -> s.addTag(tag));

    return this;
  }

  public AutomatonBuilder match(S signal) {
    State<S, T> newState = new State<>();
    states.add(newState);
    accept.forEach(s -> s.addTransition(signal, newState));
    accept = LinearSet.of(newState);
    deterministic = false;

    return this;
  }

  public AutomatonBuilder not(S signal) {
    State<S, T> newAccept = new State<>();
    for (State<S, T> s : accept) {
      s.addTransition(signal, State.REJECT);
      s.addDefault(newAccept);
    }

    accept = LinearSet.of(newAccept);
    states.add(newAccept);
    states.add(State.REJECT);
    deterministic = false;

    return this;
  }

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

  public AutomatonBuilder<S, T> maybe() {
    accept.add(init);
    deterministic = false;

    return this;
  }

  public AutomatonBuilder<S, T> kleene() {
    accept.forEach(s -> s.addEpsilon(init));
    accept.add(init);
    deterministic = false;

    toDFA();

    return this;
  }

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

    if (accept.contains(State.REJECT)) {
      throw new IllegalStateException();
    }

    deterministic = false;
    toDFA();

    return this;
  }

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

    if (accept.contains(State.REJECT)) {
      throw new IllegalStateException();
    }

    deterministic = false;
    toDFA();

    return this;
  }

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

    if (accept.contains(State.REJECT)) {
      throw new IllegalStateException();
    }

    deterministic = false;
    toDFA();

    return this;
  }

  ///

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

  private ISet alphabet() {
    LinearSet alphabet = new LinearSet().add(OTHER_SIGNAL);
    for (State<S, T> s : states) {
      s.transitions.keys().forEach(alphabet::add);
    }
    return alphabet;
  }

  private BiFunction<ISet<State<S, T>>, Object, ISet<State<S, T>>> inverseTransitions() {

    ISet alphabet = alphabet();

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

  public IMap<State<S, T>, ISet<State<S, T>>> equivalentStates() {

    toDFA();

    BiFunction<ISet<State<S, T>>, Object, ISet<State<S, T>>> inverseTransitions = inverseTransitions();

    ISet alphabet = alphabet();

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
        ISet<State<S, T>> p = inverseTransitions.apply(dst, signal).intersection(src);
        if (p.size() > 0 && p.size() < src.size()) {
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

  ///

  @Override
  public AutomatonBuilder<S, T> clone() {
    LinearMap<State<S, T>, State<S, T>> cache = new LinearMap<>();

    return new AutomatonBuilder<>(
            init.clone(cache),
            Utils.map(states, s -> s.clone(cache)),
            Utils.map(accept, s -> s.clone(cache)));
  }
}
