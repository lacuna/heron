package io.lacuna.heron;

import io.lacuna.bifurcan.*;
import io.lacuna.bifurcan.IMap.IEntry;

import java.util.Objects;
import java.util.function.Function;

/**
 * @author ztellman
 */
public class AutomatonBuilder<S, T> {

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

  private IList<ISet<State<S, T>>> splitByTags(ISet<State<S, T>> states) {
    LinearMap<ISet<T>, ISet<State<S, T>>> m = new LinearMap<>();
    for (State<S, T> s : states) {
      m.update(s.tags(), v -> (v == null ? new LinearSet<State<S, T>>() : v).add(s));
    }
    return m.values();
  }

  public void minimize() {

    LinearMap<ISet<State<S, T>>, ISet<State<S, T>>> cache = new LinearMap<>();

    IMap<State<S, T>, ISet<State<S, T>>> equivalent = equivalentStates();

    this.init = State.merge(
            LinearSet.of(init),
            set -> set.stream().map(equivalent).reduce(ISet::union).get(),
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

  public IMap<State<S, T>, ISet<State<S, T>>> equivalentStates() {

    toDFA();

    ISet<ISet<State<S, T>>> partition = LinearSet.from(splitByTags(accept).concat(splitByTags(states.difference(accept))));
    ISet<ISet<State<S, T>>> waiting = LinearSet.of(accept);

    // group states by their signals
    IMap<S, ISet<State<S, T>>> statesBySignal = new LinearMap<>();
    for (State<S, T> state : states) {
      for (S signal : state.transitions.keys()) {
        statesBySignal.update(signal, v -> (v == null ? new LinearSet<State<S, T>>() : v).add(state));
      }
    }

    for (State<S, T> state : states) {
      if (state.defaultTransitions().size() > 0) {
        for (S signal : statesBySignal.difference(state.transitions).keys()) {
          statesBySignal.update(signal, v -> v.add(state));
        }
      }
    }

    // refine partitions
    // https://en.wikipedia.org/wiki/DFA_minimization#Hopcroft.27s_algorithm
    while (waiting.size() > 0) {
      ISet<State<S, T>> a = waiting.elements().first();
      waiting.remove(a);

      for (S signal : statesBySignal.keys()) {
        ISet<State<S, T>> x = statesBySignal.get(signal).get().stream()
                .filter(s -> s.transitions(signal).containsAny(a))
                .collect(Sets.linearCollector());

        for (ISet<State<S, T>> y : LinearList.from(partition.elements())) {
          if (x.containsAny(y) && !x.containsAll(y)) {
            ISet<State<S, T>> intersect = x.intersection(y);
            ISet<State<S, T>> diff = y.difference(x);
            partition.remove(y).add(intersect).add(diff);

            if (waiting.contains(y)) {
              waiting.remove(y).add(intersect).add(diff);
            } else if (intersect.size() <= diff.size()) {
              waiting.add(intersect);
            } else {
              waiting.add(diff);
            }
          }
        }
      }
    }

    LinearMap<State<S, T>, ISet<State<S, T>>> m = new LinearMap<>();
    for (ISet<State<S, T>> set : partition) {
      for (State<S, T> state : set) {
        m.put(state, set);
      }
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
