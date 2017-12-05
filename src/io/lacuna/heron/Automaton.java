package io.lacuna.heron;

import io.lacuna.bifurcan.*;

/**
 * @param <S>
 * @param <T>
 * @author ztellman
 */
public class Automaton<S, T> {

  private final IMap<S, Integer> alphabet;
  private final IMap<Integer, T> tags;
  private final IList<IMap<Integer, Integer>> states;

  private Automaton(IMap<S, Integer> alphabet, IMap<Integer, T> tags, IList<IMap<Integer, Integer>> states) {
    this.alphabet = alphabet;
    this.tags = tags;
    this.states = states;
  }

  static <S, T> Automaton<S, T> compile(AutomatonBuilder<S, T> builder, boolean preserveSignals) {

    IMap<State<S, T>, Integer> stateRank = builder.rankOrdering();

    IMap<S, Integer> alphabet = new LinearMap<>();
    if (preserveSignals) {
      builder.alphabet().forEach(s -> alphabet.put(s, ((Number) s).intValue()));
    } else {
      int idx = 0;
      for (S s : builder.alphabet()) {
        alphabet.put(s, idx++);
      }
    }


    return null;
  }

  public ISet<S> alphabet() {
    return alphabet.keys();
  }


}
