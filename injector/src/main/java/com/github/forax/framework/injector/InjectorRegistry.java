package com.github.forax.framework.injector;

import java.beans.PropertyDescriptor;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;

public final class InjectorRegistry {
    private final HashMap<Class<?>, Supplier<?>> map;

    public InjectorRegistry() {
        map = new HashMap<>();
    }

    public <T> void registerInstance(Class<T> cl, T object) {
        Objects.requireNonNull(cl);
        Objects.requireNonNull(object);
        registerProvider(cl, () -> object);
    }

    public <T> T lookupInstance(Class<T> cl) {
        Objects.requireNonNull(cl);
        var result = cl.cast(map.get(cl).get());
        if (result == null) {
            throw new IllegalStateException(cl + " is not found");
        }
        return result;
    }

    public <T> void registerProvider(Class<T> cl, Supplier<? extends T> supplier){
        Objects.requireNonNull(cl);
        Objects.requireNonNull(supplier);
        var result = map.putIfAbsent(cl, supplier);
        if(result != null){
            throw new IllegalStateException("a recipe for " + cl.getName() + " is already defined");
        }
    }

    public static <T> List<PropertyDescriptor> findInjectableProperties(Class<T> cl){
        Objects.requireNonNull(cl);
        var beanInfo = Utils.beanInfo(cl);
        var properties = beanInfo.getPropertyDescriptors();
        return Arrays.stream(properties)
                .filter(property -> property.getWriteMethod() != null)
                .filter(property -> property.getWriteMethod().isAnnotationPresent(Inject.class))
                .toList();
    }
}
