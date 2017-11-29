package io.lacuna.heron;

import io.lacuna.bifurcan.*;

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

    return this;
  }

  public AutomatonBuilder<S, T> maybe() {
    accept.add(init);

    return this;
  }

  public AutomatonBuilder<S, T> kleene() {
    accept.forEach(s -> s.addEpsilon(init));
    accept.add(init);

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

    Function<State<S, T>, State<S, T>> generator = Utils.memoize(s -> new State<>());

    return new AutomatonBuilder<>(
            generator.apply(init),
            Utils.map(states, s -> s.clone(generator)),
            Utils.map(accept, s -> s.clone(generator)));
  }
}
