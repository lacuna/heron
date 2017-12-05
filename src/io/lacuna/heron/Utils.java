package io.lacuna.heron;

import io.lacuna.bifurcan.*;

import java.util.function.Function;
import java.util.stream.Stream;

/**
 * @author ztellman
 */
public class Utils {

  public static <V> LinearSet<V> toSet(Stream<V> s) {
    return s.collect(Sets.linearCollector());
  }

  public static <K, V> LinearMap<K, V> zipMap(Stream<K> s, Function<K, V> f) {
    return s.collect(Maps.linearCollector(Function.identity(), f));
  }

  public static <U, V> Function<U, V> memoize(Function<U, V> f) {
    LinearMap<U, V> cache = new LinearMap<>();
    return (U x) -> {
      cache.update(x, y -> y == null ? f.apply(x) : y);
      return cache.get(x).get();
    };
  }

  public static <U, V> LinearSet<V> map(ISet<U> set, Function<U, V> f) {
    if (set == null) {
      return null;
    }
    return toSet(set.stream().map(f));
  }

  public static <K, U, V> IMap<K, V> mapVals(IMap<K, U> map, Function<U, V> f) {
    return map.stream()
            .collect(Maps.linearCollector(
                    e -> e.key(),
                    e -> f.apply(e.value()),
                    (int) map.size()));
  }

  public static <K, V> IMap<K, ISet<V>> groupBy(Iterable<V> vals, Function<V, K> f) {
    LinearMap<K, ISet<V>> m = new LinearMap<>();
    vals.forEach(v -> m.getOrCreate(f.apply(v), LinearSet::new).add(v));
    return m;
  }

  public static <V> IList<ISet<V>> partitionAll(Iterable<ISet<V>> sets, ISet<V> partition) {
    IList<ISet<V>> accumulator = new LinearList<>();
    for (ISet<V> set : sets) {
      if (set.containsAny(partition) && !partition.containsAll(set)) {
        accumulator.addLast(set.intersection(partition));
        accumulator.addLast(set.difference(partition));
      } else {
        accumulator.addLast(set);
      }
    }
    return accumulator;
  }
}
