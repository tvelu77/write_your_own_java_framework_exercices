package com.github.forax.framework.mapper;

import java.beans.IntrospectionException;
import java.beans.PropertyDescriptor;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

public final class JSONWriter {

  private interface Generator {
    String generate(JSONWriter writer, Object bean);
  }

  private static List<PropertyDescriptor> beanProperties(Class<?> type) {
    var beanInfo = Utils.beanInfo(type);
    var properties = beanInfo.getPropertyDescriptors();
    return Arrays.stream(properties)
            .filter(property -> !property.getName().equals("class"))
            .toList();
  }

  private static List<PropertyDescriptor> recordProperties(Class<?> type) {
    var recordInfo = type.getRecordComponents();
    return Arrays.stream(recordInfo)
            .map(info -> {
              try {
                return new PropertyDescriptor(info.getName(), info.getAccessor(), null);
              } catch (IntrospectionException e) {
                throw new AssertionError(e);
              }
            })
            .toList();
  }

  private static final ClassValue<List<Generator>> PROPERTIES_CLASS_VALUE = new ClassValue<List<Generator>>() {
    @Override
    protected List<Generator> computeValue(Class<?> type) {
      List<PropertyDescriptor> properties;
      if(type.isRecord()){
        properties = recordProperties(type);
      }
      else{
        properties = beanProperties(type);
      }
      return properties.stream()
              .filter(property -> !property.getName().equals("class"))
              .<Generator>map(property -> {
                var getter = property.getReadMethod();
                var annotation = getter.getAnnotation(JSONProperty.class);
                String keyName;
                if(annotation != null){
                  keyName = annotation.value();
                }
                else{
                  keyName = property.getName();
                }
                var key = "\"" + keyName + "\": ";
                return (writer, bean) -> key + writer.toJSON(Utils.invokeMethod(bean, getter));
              })
              .toList();
    }
  };

  public String toJSON(Object o) {
    return switch(o){
      case null -> "null";
      case Integer i -> "" + i;
      case Double d -> "" + d;
      case Boolean b -> "" + b;
      case String s -> "\"" + s + "\"";
      case Object ob -> {
        var fun = map.get(o.getClass());
        if(fun != null){
          yield fun.apply(ob);
        }
        var generators = PROPERTIES_CLASS_VALUE.get(o.getClass());
        yield generators.stream()
                .map(generator -> generator.generate(this, ob))
                .collect(Collectors.joining(", ", "{", "}"));
      }
    };
  }

  private static Object extractValue(PropertyDescriptor property, Object obj){
    var getter = property.getReadMethod();
    return Utils.invokeMethod(obj, getter);
  }

  private final HashMap<Class<?>, Function<Object, String>> map = new HashMap<>();

  public <T> void configure(Class<T> type, Function<T, String> fun){
    Objects.requireNonNull(type);
    Objects.requireNonNull(fun);
    var result = map.putIfAbsent(type, o -> fun.apply(type.cast(o))); // TODO : use compose or andThen
    if(result != null){
      throw new IllegalStateException("configuration for " + type.getName() + "already exists");
    }
  }
}
