package io.lacuna.heron;

import io.lacuna.bifurcan.*;

import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * @param <T> the tags applied to the state
 * @param <S> the signals that cause transitions to another state
 */
public class State<S, T> {

  public static final State REJECT = new State();

  public LinearMap<State<S, T>, LinearSet<S>> transitions;
  public LinearSet<State<S, T>> epsilonTransitions = null;
  public State<S, T> defaultTransition = null;
  public LinearSet<T> tags = null;

  public State() {
    this(new LinearMap<>(), null, null, null);
  }

  State(LinearMap<State<S, T>, LinearSet<S>> transitions,
        LinearSet<T> tags,
        LinearSet<State<S, T>> epsilonTransitions,
        State<S, T> defaultTransition) {
    this.transitions = transitions;
    this.tags = tags;
    this.defaultTransition = defaultTransition;
    this.epsilonTransitions = epsilonTransitions;
  }

  public void tag(T t) {
    if (tags == null) {
      tags = new LinearSet<>();
    }
    tags.add(t);
  }

  public void epsilonTransition(State<S, T> state) {
    if (epsilonTransitions == null) {
      epsilonTransitions = new LinearSet<>();
    }
    epsilonTransitions.add(state);
  }

  public void transition(S signal, State<S, T> state) {
    transitions.update(state, s -> s == null ? LinearSet.of(signal) : s.add(signal));
  }

  public void defaultTransition(State<S, T> state) {
    defaultTransition = state;
  }

  ////

  void remap(Function<State<S, T>, State<S, T>> f) {

    transitions = transitions.entries()
            .stream()
            .collect(Maps.linearCollector(e -> f.apply(e.key()), e -> e.value()));

    if (epsilonTransitions != null) {
      epsilonTransitions = epsilonTransitions
              .stream()
              .map(f)
              .collect(Sets.linearCollector());
    }

    if (defaultTransition != null) {
      defaultTransition = f.apply(defaultTransition);
    }
  }

  @Override
  protected State<S, T> clone() {
    return new State<>(
            transitions.clone(),
            tags != null ? tags.clone() : null,
            epsilonTransitions != null ? epsilonTransitions.clone() : null,
            defaultTransition);
  }
}
