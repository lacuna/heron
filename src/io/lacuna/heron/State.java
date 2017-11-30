package io.lacuna.heron;

import io.lacuna.bifurcan.*;

import java.util.Iterator;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiFunction;
import java.util.function.BinaryOperator;
import java.util.function.Function;
import java.util.function.Predicate;

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
    if (this == State.REJECT) {
      throw new IllegalStateException();
    }
    transitions.update(signal, s -> (s == null ? new LinearSet<State<S, T>>() : s).add(state));
  }

  public ISet<S> signals() {
    return transitions.keys();
  }

  public ISet<State<S, T>> transitions(S signal) {
    return transitions.get(signal).orElse(Sets.EMPTY);
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
    if (!accumulator.contains(this)) {
      accumulator.add(this);
      if (epsilonTransitions != null) {
        epsilonTransitions.forEach(s -> s.epsilonClosure(accumulator));
      }
    }
  }

  static <S, T> State<S, T> join(
          IList<State<S, T>> init,
          Predicate<IList<State<S, T>>> isReject,
          IMap<IList<State<S, T>>, State<S, T>> cache) {

    LinearList<IList<State<S, T>>> queue = new LinearList<>();

    Function<IList<State<S, T>>, State<S, T>> enqueue = pair -> {
      Optional<State<S, T>> s = cache.get(pair);
      if (s.isPresent()) {
        return s.get();
      } else {
        State<S, T> state = isReject.test(pair) ? State.REJECT : new State<>();
        cache.put(pair, state);
        if (state != State.REJECT) {
          queue.addLast(pair);
        }

        return state;
      }
    };

    BinaryOperator<ISet<State<S, T>>> join = (a, b) -> {
      if (a.size() == 0) {
        return b;
      } else if (b.size() == 0) {
        return a;
      } else {
        IList<State<S, T>> t = LinearList.of(a.elements().first(), b.elements().first());
        return LinearSet.of(enqueue.apply(t));
      }
    };

    enqueue.apply(init);

    while (queue.size() > 0) {
      IList<State<S, T>> pair = queue.popLast();

      State<S, T> joined = cache.get(pair).get();
      State<S, T> a = pair.nth(0);
      State<S, T> b = pair.nth(1);

      joined.transitions = a.transitions.merge(b.transitions, join);
      if (a.defaultTransitions != null) {
        b.transitions
                .difference(a.transitions)
                .forEach(e -> joined.transitions.put(e.key(), join.apply(e.value(), a.defaultTransitions), ISet::union));
      }
      if (b.defaultTransitions != null) {
        a.transitions
                .difference(b.transitions)
                .forEach(e -> joined.transitions.put(e.key(), join.apply(e.value(), b.defaultTransitions), ISet::union));
      }

      a.tags().union(b.tags()).forEach(joined::addTag);

      join.apply(a.defaultTransitions(), b.defaultTransitions()).forEach(joined::addDefault);
    }

    return cache.get(init).get();
  }

  static <S, T> State<S, T> merge(
          ISet<State<S, T>> init,
          Function<State<S, T>, ISet<State<S, T>>> epsilonClosure,
          IMap<ISet<State<S, T>>, State<S, T>> cache) {

    LinearList<ISet<State<S, T>>> queue = new LinearList<>();

    Function<ISet<State<S, T>>, State<S, T>> enqueue = states -> {

      states = states.stream()
              .map(epsilonClosure)
              .flatMap(ISet::stream)
              .collect(Sets.linearCollector());

      Optional<State<S, T>> s = cache.get(states);
      if (s.isPresent()) {
        return s.get();
      } else {
        State<S, T> state = states.size() == 1 && states.contains(State.REJECT) ? State.REJECT : new State<>();
        cache.put(states, state);
        if (state != State.REJECT) {
          queue.addLast(states);
        }

        return state;
      }
    };

    enqueue.apply(init);

    while (queue.size() > 0) {

      ISet<State<S, T>> states = queue.popLast();
      State<S, T> merged = cache.get(states).get();

      // merge tags
      states.stream().map(State::tags).flatMap(ISet::stream).forEach(merged::addTag);

      // merge default transitions
      ISet<State<S, T>> defaultStates = states.stream()
              .map(State::defaultTransitions)
              .flatMap(ISet::stream)
              .collect(Sets.linearCollector());
      if (defaultStates.size() > 0) {
        merged.addDefault(enqueue.apply(defaultStates));
      }

      // merge other transitions
      LinearMap<S, ISet<State<S, T>>> transitions = states.stream()
              .map(s -> s.transitions)
              .reduce((a, b) -> a.merge(b, ISet::union))
              .orElseGet(LinearMap::new);

      for (State<S, T> s : states) {
        if (s.defaultTransitions().size() > 0) {
          transitions = transitions.merge(transitions.difference(s.transitions), (a, b) -> a.union(s.defaultTransitions()));
        }
      }

      merged.transitions = Utils.mapVals(transitions, s -> LinearSet.of(enqueue.apply(s)));
    }

    return enqueue.apply(init);
  }

  State<S, T> clone(IMap<State<S, T>, State<S, T>> cache) {

    if (this == State.REJECT) {
      return this;
    }

    Optional<State<S, T>> opt = cache.get(this);
    if (opt.isPresent()) {
      return opt.get();
    } else {
      State<S, T> newState = new State<>();
      cache.put(this, newState);

      newState.transitions = Utils.mapVals(transitions, set -> Utils.map(set, s -> s.clone(cache)));
      newState.tags = tags == null ? null : tags.clone();
      newState.epsilonTransitions = Utils.map(epsilonTransitions, s -> s.clone(cache));
      newState.defaultTransitions = Utils.map(defaultTransitions, s -> s.clone(cache));

      return newState;
    }
  }

  @Override
  public String toString() {
    StringBuilder sb = new StringBuilder("state(" + id + ")[");
    if (tags().size() > 0) {
      tags.forEach(t -> sb.append(t + ", "));
      sb.delete(sb.length() - 2, sb.length());
    }
    sb.append("]");
    return sb.toString();
  }
}
