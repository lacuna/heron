package io.lacuna.heron;

import io.lacuna.bifurcan.*;

import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;

/**
 * @param <T> the tags applied to the state
 * @param <S> the signals that cause transitions to another state
 */
public class State<S, T> {

  private static final AtomicLong COUNTER = new AtomicLong();

  public static final State REJECT = new State();

  final long id = COUNTER.incrementAndGet();

  private LinearMap<S, ISet<State<S, T>>> transitions;
  private LinearSet<State<S, T>> epsilonTransitions = null;
  private LinearSet<State<S, T>> defaultTransitions = null;
  private LinearSet<T> tags = null;

  public State() {
    this(new LinearMap<>(), null, null, null);
  }

  State(LinearMap<S, ISet<State<S, T>>> transitions,
        LinearSet<T> tags,
        LinearSet<State<S, T>> epsilonTransitions,
        LinearSet<State<S, T>> defaultTransitions) {
    this.transitions = transitions;
    this.tags = tags;
    this.defaultTransitions = defaultTransitions;
    this.epsilonTransitions = epsilonTransitions;
  }

  public void addTag(T t) {
    if (tags == null) {
      tags = new LinearSet<>();
    }
    tags.add(t);
  }

  public void addEpsilon(State<S, T> state) {
    if (epsilonTransitions == null) {
      epsilonTransitions = new LinearSet<>();
    }
    epsilonTransitions.add(state);
  }

  public void addDefault(State<S, T> state) {
    if (defaultTransitions == null) {
      defaultTransitions = new LinearSet<>();
    }
    defaultTransitions.add(state);
  }

  public void addTransition(S signal, State<S, T> state) {
    transitions.update(signal, s -> (s == null ? new LinearSet<State<S, T>>() : s).add(state));
  }

  public ISet<S> signals() {
    return transitions.keys();
  }

  public ISet<State<S, T>> transition(S signal) {
    return transitions.get(signal).get();
  }

  public ISet<S> signals(State<S, T> state) {
    return transitions.stream()
            .filter(e -> e.value().contains(state))
            .map(e -> e.key())
            .collect(Sets.linearCollector());
  }

  public ISet<State<S, T>> downstreamStates() {
    return transitions.values().stream()
            .flatMap(ISet::stream)
            .collect(Sets.linearCollector());
  }

  public ISet<T> tags() {
    return tags == null ? Sets.EMPTY : tags;
  }

  public ISet<State<S, T>> defaultTransitions() {
    return defaultTransitions == null ? Sets.EMPTY : defaultTransitions;
  }

  public ISet<State<S, T>> epsilonTransitions() {
    return epsilonTransitions == null ? Sets.EMPTY : epsilonTransitions;
  }

  ////

  public static <S, T> ISet<State<S, T>> epsilonClosure(State<S, T> state) {
    LinearSet<State<S, T>> accumulator = new LinearSet<>();
    state.epsilonClosure(accumulator);
    return accumulator;
  }

  private void epsilonClosure(LinearSet<State<S, T>> accumulator) {
    accumulator.add(this);
    if (epsilonTransitions != null) {
      epsilonTransitions.forEach(s -> s.epsilonClosure(accumulator));
    }
  }

  static <S, T> State<S, T> merge(
          ISet<State<S, T>> states,
          Function<State<S, T>, ISet<State<S, T>>> epsilonClosure,
          IMap<ISet<State<S, T>>, State<S, T>> cache) {

    if (states.contains(State.REJECT)) {
      return State.REJECT;
    }

    // expand states per epsilon transitions
    states = states.stream()
            .map(epsilonClosure)
            .flatMap(ISet::stream)
            .collect(Sets.linearCollector());

    if (cache.contains(states)) {
      return cache.get(states).get();
    }

    State<S, T> merged = new State<>();
    cache.put(states, merged);

    // merge default transitions
    ISet<State<S, T>> defaultStates = states.stream()
            .map(State::defaultTransitions)
            .flatMap(ISet::stream)
            .collect(Sets.linearCollector());
    if (defaultStates.size() > 0) {
      merged.addDefault(merge(defaultStates, epsilonClosure, cache));
    }

    // merge other transitions
    states.stream()
            .map(s -> s.transitions)
            .reduce((a, b) -> a.merge(b, ISet::union))
            .get()
            .forEach(e -> merged.addTransition(e.key(), merge(e.value(), epsilonClosure, cache)));

    return merged;
  }

  State<S, T> clone(Function<State<S, T>, State<S, T>> generator) {

    State<S, T> state = generator.apply(this);
    state.transitions = Utils.mapVals(transitions, s -> Utils.map(s, generator));
    state.tags = tags == null ? null : tags.clone();
    state.epsilonTransitions = Utils.map(epsilonTransitions, generator);
    state.defaultTransitions = Utils.map(defaultTransitions, generator);

    return state;
  }

  @Override
  public String toString() {
    StringBuilder sb = new StringBuilder("state(" + id + ")[");
    if (tags != null) {
      tags.forEach(t -> sb.append(t + ", "));
    }
    sb.delete(sb.length() - 2, sb.length());
    sb.append("]");
    return sb.toString();
  }
}
