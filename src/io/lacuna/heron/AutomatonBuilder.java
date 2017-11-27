package io.lacuna.heron;

import io.lacuna.bifurcan.LinearMap;
import io.lacuna.bifurcan.LinearSet;
import io.lacuna.bifurcan.Maps;
import io.lacuna.bifurcan.Sets;

import java.util.function.Function;

/**
 * @author ztellman
 */
public class AutomatonBuilder<S, T> {

  public LinearSet<State<S, T>> states, init, accept;

  public AutomatonBuilder() {
    State<S, T> s = new State();

    init = LinearSet.of(s);
    accept = init.clone();
    states = init.clone();
  }

  private AutomatonBuilder(LinearSet<State<S, T>> states, LinearSet<State<S, T>> init, LinearSet<State<S, T>> accept) {
    this.states = states;
    this.init = init;
    this.accept = accept;
  }

  ///

  public AutomatonBuilder match(S signal) {
    State<S, T> newState = new State<>();
    states.add(newState);
    accept.forEach(s -> s.transition(signal, newState));
    accept = LinearSet.of(newState);

    return this;
  }

  public AutomatonBuilder matchAll(Iterable<S> signals) {
    signals.forEach(this::match);

    return this;
  }

  public AutomatonBuilder tag(T tag) {
    accept.forEach(s -> s.tag(tag));

    return this;
  }

  public AutomatonBuilder concat(AutomatonBuilder<S, T> builder) {
    builder = builder.clone();

    for (State<S, T> a : accept) {
      for (State<S, T> b : builder.init) {
        a.epsilonTransition(b);
      }
    }

    builder.states.forEach(states::add);
    accept = builder.accept;

    return this;
  }

  ///

  @Override
  public AutomatonBuilder<S, T> clone() {
    LinearMap<State<S, T>, State<S, T>> clonedStates = states.stream().collect(Maps.linearCollector(s -> s, State::clone));
    Function<State<S, T>, State<S, T>> cloned = s -> clonedStates.get(s).get();
    clonedStates.values().forEach(s -> s.remap(cloned));

    return new AutomatonBuilder<>(
            LinearSet.from(clonedStates.values()),
            init.stream().map(cloned).collect(Sets.linearCollector()),
            accept.stream().map(cloned).collect(Sets.linearCollector()));
  }
}
