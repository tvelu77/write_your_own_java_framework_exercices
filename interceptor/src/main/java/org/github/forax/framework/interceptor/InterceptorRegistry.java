package org.github.forax.framework.interceptor;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;

public final class InterceptorRegistry {
  private final HashMap<Class<?>, List<AroundAdvice>> adviceMap = new HashMap<>();
  private final HashMap<Class<?>, List<Interceptor>> interceptorMap = new HashMap<>();

  public void addAroundAdvice(Class<?> annotationClass,
                                  AroundAdvice aroundAdvice) {
    Objects.requireNonNull(annotationClass);
    Objects.requireNonNull(aroundAdvice);
    adviceMap.computeIfAbsent(annotationClass, e -> new ArrayList<>())
            .add(aroundAdvice);
  }

  public void addInterceptor(Class<? extends Annotation> annotationClass,
                             Interceptor interceptor) {
    Objects.requireNonNull(annotationClass);
    Objects.requireNonNull(interceptor);
    interceptorMap.computeIfAbsent(annotationClass, e -> new ArrayList<>())
            .add(interceptor);
  }

  public <T> T createProxy(Class<T> type,
                                T delegate) {
    Objects.requireNonNull(type);
    Objects.requireNonNull(delegate);
    return type.cast(Proxy.newProxyInstance(type.getClassLoader(),
            new Class<?>[] { type },
            (proxy, method, args) -> {
              var advices = findAdvices(method);
              for(var advice : advices) {
                advice.before(delegate, method, args);
              }
              Object result = null;
              try {
                result = Utils.invokeMethod(delegate, method, args);
              } finally {
                for(var advice : advices) {
                  advice.after(delegate, method, args, result);
                }
              }
              return result;
            }));
  }

  List<AroundAdvice> findAdvices(Method method) {
      return Arrays.stream(method.getAnnotations())
              .flatMap(annotation -> adviceMap.getOrDefault(annotation.annotationType(),
                      List.of()).stream())
              .toList();
  }

  List<Interceptor> findInterceptors(Method method) {
    return Arrays.stream(method.getAnnotations())
            .flatMap(annotation -> interceptorMap.getOrDefault(annotation.annotationType(),
                    List.of()).stream())
            .toList();
  }

  static Invocation getInvocation(List<Interceptor> interceptorList) {
    Invocation invocation = Utils::invokeMethod;
    for(var interceptor : Utils.reverseList(interceptorList)) {
      var oldInvocation = invocation;
      invocation = (instance, method, args) ->
              interceptor.intercept(instance, method, args, oldInvocation);
    }
    return invocation;
  }
}
