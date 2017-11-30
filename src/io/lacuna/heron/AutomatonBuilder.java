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

    return this;
  }

  public AutomatonBuilder<S, T> concat(AutomatonBuilder<S, T> builder) {
    builder = builder.clone();

    for (State<S, T> a : accept) {
      a.addEpsilon(builder.init);
    }

    builder.states.forEach(states::add);
    accept = builder.accept;

    toDFA();

    return this;
  }

  public AutomatonBuilder<S, T> maybe() {
    accept.add(init);

    toDFA();

    return this;
  }

  public AutomatonBuilder<S, T> kleene() {
    accept.forEach(s -> s.addEpsilon(init));
    accept.add(init);

    //toDFA();

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

    toDFA();

    return this;
  }

  ///

  public void toDFA() {

    LinearMap<ISet<State<S, T>>, State<S, T>> cache = new LinearMap<>();

    this.init = State.merge(LinearSet.of(init), State::epsilonClosure, cache);

    this.accept = cache.stream()
            .filter(e -> e.key().containsAny(this.accept))
            .map(e -> e.value())
            .collect(Sets.linearCollector());

    this.states = LinearSet.from(cache.values());
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
