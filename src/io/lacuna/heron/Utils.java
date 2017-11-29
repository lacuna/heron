package io.lacuna.heron;

import io.lacuna.bifurcan.*;

import java.util.function.Function;

/**
 * @author ztellman
 */
public class Utils {

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
    return set.stream()
            .map(f)
            .collect(Sets.linearCollector());
  }

  public static <K, U, V> LinearMap<K, V> mapVals(LinearMap<K, U> map, Function<U, V> f) {
    return map.stream()
            .collect(Maps.linearCollector(
                    e -> e.key(),
                    e -> f.apply(e.value()),
                    (int) map.size()));
  }
}
