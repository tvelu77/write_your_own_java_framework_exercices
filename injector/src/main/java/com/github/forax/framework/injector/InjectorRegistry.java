package com.github.forax.framework.injector;

import java.util.HashMap;
import java.util.Objects;

public final class InjectorRegistry {
    private final HashMap<Class<?>, Object> map;

    public InjectorRegistry() {
        map = new HashMap<>();
    }

    public <T> void registerInstance(Class<T> cl, T object) {
        Objects.requireNonNull(cl);
        Objects.requireNonNull(object);
        map.putIfAbsent(cl, object);
    }

    public <T> T lookupInstance(Class<T> cl) {
        Objects.requireNonNull(cl);
        var result = cl.cast(map.get(cl));
        if (result == null) {
            throw new IllegalStateException(cl + " is not found");
        }
        return result;
    }
}
