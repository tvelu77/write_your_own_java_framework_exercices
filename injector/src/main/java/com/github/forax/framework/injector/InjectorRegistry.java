package com.github.forax.framework.injector;

import java.util.HashMap;
import java.util.Objects;
import java.util.function.Supplier;

public final class InjectorRegistry {
    private final HashMap<Class<?>, Object> map;

    public InjectorRegistry() {
        map = new HashMap<>();
    }

    public <T> void registerInstance(Class<T> cl, T object) {
        Objects.requireNonNull(cl);
        Objects.requireNonNull(object);
        var result = map.putIfAbsent(cl, object);
        if(result != null){
            throw new IllegalStateException("an instance for " + cl.getName() + " is already defined");
        }
    }

    public <T> T lookupInstance(Class<T> cl) {
        Objects.requireNonNull(cl);
        var result = cl.cast(map.get(cl));
        if (result == null) {
            throw new IllegalStateException(cl + " is not found");
        }
        return result;
    }

    public <T> void registerProvider(Class<T> cl, Supplier<T> supplier){

    }
}
