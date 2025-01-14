package com.github.forax.framework.injector;

import java.beans.PropertyDescriptor;
import java.lang.reflect.Constructor;
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
        var test = map.get(cl);
        if(test == null){
            throw new IllegalStateException("cannot get cl");
        }
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

    // package private for the test unit
    static <T> List<PropertyDescriptor> findInjectableProperties(Class<T> cl) {
        Objects.requireNonNull(cl);
        var beanInfo = Utils.beanInfo(cl);
        var properties = beanInfo.getPropertyDescriptors();
        return Arrays.stream(properties)
                .filter(property -> {
                    var setter = property.getWriteMethod();
                    return setter != null && setter.isAnnotationPresent(Inject.class);
                })
                .toList();
    }
    public <T> void registerProviderClass(Class<T> cl, Class<? extends T> providerCl) {
        Objects.requireNonNull(cl);
        Objects.requireNonNull(providerCl);
        var constructor = findInjectableConstructor(providerCl);
        var parameterTypes = constructor.getParameterTypes();
        var properties = findInjectableProperties(providerCl);
        registerProvider(cl, () -> {
            var args = Arrays.stream(parameterTypes)
                    .map(this::lookupInstance)
                    .toArray();
            var instance = Utils.newInstance(constructor, args);
            for (var property : properties) {
                var setter = property.getWriteMethod();
                var value = lookupInstance(setter.getParameterTypes()[0]);
                Utils.invokeMethod(instance, setter, value);
            }
            return cl.cast(instance);
        });
    }

    private static Constructor<?> findInjectableConstructor(Class<?> providerCl){
        return Arrays.stream(providerCl.getConstructors())
                .filter(constructor -> constructor.isAnnotationPresent(Inject.class))
                .reduce((c1, c2) -> {
                    throw new IllegalStateException("multiple constructors annotated " + c1 + " " + c2);
                })
                .orElseGet(() -> Utils.defaultConstructor(providerCl));
    }

    public void registerProviderClass(Class<?> providerClass){
        Objects.requireNonNull(providerClass);
        registerProviderClass0(providerClass);
    }

    private <T> void registerProviderClass0(Class<T> type){
        registerProviderClass(type, type);
    }
}
