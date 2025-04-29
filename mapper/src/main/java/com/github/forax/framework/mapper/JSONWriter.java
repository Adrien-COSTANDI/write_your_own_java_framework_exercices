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
  private final HashMap<Class<?>, Function<Object, String>> configurations = new HashMap<>();

  @FunctionalInterface
  private interface Generator {
    String generate(JSONWriter writer, Object bean);
  }

  /**
   * this is used as a cache for the properties of a given class.
   * then we can use it to retrieve the computed properties from the given class.
   */
  private static final ClassValue<List<Generator>> PROPERTIES_CLASS_VALUE = new ClassValue<>() {
    @Override
    protected List<Generator> computeValue(Class<?> type) {
      List<PropertyDescriptor> properties;

      if (type.isRecord()) {
        properties = recordProperties(type);
      } else {
        var beanInfo = Utils.beanInfo(type);
        properties = Arrays.stream(beanInfo.getPropertyDescriptors()).toList();
      }

      return properties.stream()
              .filter(p -> !p.getName().equals("class"))
              .<Generator>map(property -> {
                var getter = property.getReadMethod();
                var jsonProperty = getter.getAnnotation(JSONProperty.class);
                var keyName = jsonProperty == null ? property.getName() : jsonProperty.value();
                var key = "\"" + keyName + "\": ";
                return (writer, beanInstance) -> key + writer.toJSON(Utils.invokeMethod(beanInstance, getter));
              })
              .toList();
    }
  };

  private static List<PropertyDescriptor> recordProperties(Class<?> type) {
    return Arrays.stream(type.getRecordComponents())
            .map(recordComponent -> {
              try {
                return new PropertyDescriptor(recordComponent.getName(), recordComponent.getAccessor(), null);
              } catch (IntrospectionException e) {
                throw new RuntimeException(e);
              }
            })
            .toList();
  }


  public String toJSON(Object o) {
    return switch (o) {
      case null -> "null";
      case String s -> "\"" + s + "\"";
      case Double d -> d + "";
      case Integer i -> i + "";
      case Boolean b -> b + "";
      case Object a -> objectToJson(a);
      // default -> throw new UnsupportedOperationException("unknown JSON type");
    };

  }

  public <T> void configure(Class<T> type, Function<T, String> func) {
    Objects.requireNonNull(type);
    Objects.requireNonNull(func);
    var test = configurations.putIfAbsent(type, obj -> func.apply(type.cast(obj)));
    if (test != null) {
      throw new IllegalStateException("Cannot override configuration for " + type.getName());
    }
  }

  private String objectToJson(Object o) {
    var type = o.getClass();
    var parser = configurations.get(type);
    if (parser != null) {
      return parser.apply(o);
    }
    var generators = PROPERTIES_CLASS_VALUE.get(type);
    return generators.stream()
            .map(gen -> gen.generate(this, o))
            .collect(Collectors.joining(", ", "{", "}"));
  }

}
