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

  public void addAroundAdvice(Class<? extends Annotation> annotationClass,
                                  AroundAdvice aroundAdvice) {
    Objects.requireNonNull(annotationClass);
    Objects.requireNonNull(aroundAdvice);
    addInterceptor(annotationClass, ((instance, method, args, invocation) -> {
      Object result = null;
      aroundAdvice.before(instance, method, args);
      try {
        result = invocation.proceed(instance, method, args);
      } finally {
        aroundAdvice.after(instance, method, args, result);
      }
      return result;
    }));
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
              var interceptors = findInterceptors(method);
              var invocation = getInvocation(interceptors);
              return invocation.proceed(delegate, method, args);
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
