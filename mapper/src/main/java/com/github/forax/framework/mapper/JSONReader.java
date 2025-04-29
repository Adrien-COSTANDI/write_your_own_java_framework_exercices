package com.github.forax.framework.mapper;

import java.beans.PropertyDescriptor;
import java.lang.reflect.Type;
import java.util.*;
import java.util.function.Function;
import java.util.function.Supplier;

import static java.util.stream.Collectors.toMap;

public class JSONReader {
//  public <T> T parseJSON(String text, Class<T> beanClass) {
//    Objects.requireNonNull(text);
//    Objects.requireNonNull(beanClass);
//
//    var beanInfo = Utils.beanInfo(beanClass);
//    var properties = Arrays.stream(beanInfo.getPropertyDescriptors()).toList();
////    properties.stream().filter(p -> p.getWriteMethod().getName().equals("set")).findAny();
//
//    var instance = Utils.newInstance(Utils.defaultConstructor(beanClass));
//
//    Arrays.stream(text.split(","))
//            .map(keyValueStr -> keyValueStr.trim().split(":"))
//            .filter(keyValue -> keyValue.length == 2)
//            .map(keyValue -> {
//              String key = keyValue[0].trim();
//              String value = keyValue[1].trim();
//
//              var m2 = Pattern.compile("(.*)[ \n\t}\\]]?").matcher(value);
//              if (!m2.find()) {
//                throw new AssertionError("Invalid JSON, a value is missing");
//              }
//              value = m2.group(1);
//
//              var m = Pattern.compile("\"([^\"]*)\"[ \t]*").matcher(key);
//              if (!m.find()) {
//                throw new AssertionError("Invalid JSON, a key is missing");
//              }
//              key = m.group(1);
//
//              var methodName = "set" + Pattern.compile("^.").matcher(key).replaceFirst(matchResult -> matchResult.group().toUpperCase(Locale.ROOT));
//              System.out.println("methodName = " + methodName);
//              System.out.println("value = " + value);
//
//
//              try {
//                var type = determineType(value);
//                System.out.println("type = " + type);
//                var method = beanClass.getMethod(methodName, type);
//
//                System.out.println("method = " + method);
//
//                return new ParamMethod(method, value, type);
//              } catch (NoSuchMethodException e) {
//                throw new IllegalStateException(e);
//              }
//
//            })
//            .forEach(paramMethod -> Utils.invokeMethod(instance, paramMethod.method(), cast(paramMethod.type(), paramMethod.value())));
////            .flatMap(str -> Arrays.stream(str.split(":")))
////            .map(String::trim)
////            .forEach(x -> System.out.println("str: " + x));
//
//    return beanClass.cast(instance);
//
////    return properties.stream()
////            .filter(p -> !p.getName().equals("class"))
////            .map(property -> {
////              var setter = property.getWriteMethod();
////
////            });
//
//  }

  //
//
//  private Object cast(Class<?> type, String value) {
//    if (type.equals(String.class)) {
//      return value.substring(1, value.length() - 1);
//    } else if (type.equals(int.class)) {
//      return Integer.parseInt(value);
//    } else if (type.equals(double.class)) {
//      return Double.parseDouble(value);
//    } else if (type.equals(boolean.class)) {
//      return Boolean.parseBoolean(value);
//    } else if (type.equals(Void.class)) {
//      return null;
//    } else {
//      throw new AssertionError("bad cast. type : " + type + ", value : " + value);
//    }
//  }
//
//
//  private Class<?> determineType(String value) {
//
//    var stringMatcher = Pattern.compile("\"([^\"]*)\"").matcher(value);
//    if (stringMatcher.find()) {
//      return String.class;
//    }
//
//
//    var arrayMatcher = Pattern.compile("\\[([^\"]*)\\]").matcher(value);
//    if (arrayMatcher.find()) {
//      return String.class;
//    }
//
//
//    try {
//      var a = Integer.parseInt(value);
//      return int.class;
//    } catch (NumberFormatException e) {
//    }
//
//    try {
//      var a = Double.parseDouble(value);
//      return double.class;
//    } catch (NumberFormatException e) {
//    }
//
//
//    if (value.equalsIgnoreCase("true") || value.equalsIgnoreCase("false")) {
//      return boolean.class;
//    }
//
//    if (value.equalsIgnoreCase("null")) {
//      return Void.class;
//    }
//
//    throw new AssertionError("type is missing for: " + "\"" + value + "\"");
//  }

  public <T> T parseJSON(String text, Class<T> beanClass) {
    return beanClass.cast(parseJSON(text, (Type) beanClass));
  }

  public Object parseJSON(String text, Type type) {
    Objects.requireNonNull(text);
    Objects.requireNonNull(type);
    var visitor = new ToyJSONParser.JSONVisitor() {
      record Context(Collector<Object> collector, Object data) {}

      private final ArrayDeque<Context> contexts = new ArrayDeque<>();
      private Object result;


      @Override
      public void value(String key, Object value) {
        var context = contexts.peek();
        assert context != null;
        context.collector.populater.populate(context.data, key, value);
      }

      private void start(String key) {
        var context = contexts.peek();
        var itemType = context == null? type: context.collector.qualifier.apply(key);
        var collector = findCollector(itemType).raw();
        var data = collector.supplier.get();
        contexts.push(new Context(collector, data));
      }

      private void end(String key) {
        var context = contexts.pop();
        var result = context.collector.finisher.apply(context.data);
        if (contexts.isEmpty()) {
          this.result = result;
        } else {
          var enclosingContext = contexts.peek();
          enclosingContext.collector.populater.populate(enclosingContext.data, key, result);
        }
      }

      @Override
      public void startObject(String key) {
        start(key);
      }
      @Override
      public void endObject(String key) {
        end(key);
      }
      @Override
      public void startArray(String key) {
        start(key);
      }
      @Override
      public void endArray(String key) {
        start(key);
      }
    };
    ToyJSONParser.parse(text, visitor);
    return visitor.result;
  }


  @FunctionalInterface
  public interface TypeMatcher {
    Optional<Collector<?>> match(Type type);
  }

  private final ArrayList<TypeMatcher> typeMatchers = new ArrayList<>();

  public void addTypeMatcher(TypeMatcher typeMatcher) {
    Objects.requireNonNull(typeMatcher);
    typeMatchers.add(typeMatcher);
  }

  private Collector<?> findCollector(Type type) {
    return typeMatchers.reversed().stream()
            .flatMap(typeMatcher -> typeMatcher.match(type).stream())
            .findFirst()
            .orElseGet(() -> Collector.bean(Utils.erase(type)));
  }


  public record Collector<B>(Function<? super String, ? extends Type> qualifier,
                             Supplier<? extends B> supplier, Collector.Populater<B> populater, Function<? super B, ?> finisher) {
    public interface Populater<B> {
      void populate(B builder, String key, Object value);
    }

    @SuppressWarnings("unchecked")
    private Collector<Object> raw() {
      return (Collector<Object>) (Collector<?>) this;
    }

    public Collector {
      Objects.requireNonNull(qualifier);
      Objects.requireNonNull(supplier);
      Objects.requireNonNull(populater);
      Objects.requireNonNull(finisher);
    }

    private static PropertyDescriptor findProperty(Map<String, PropertyDescriptor> propertyMap, String key, Class<?> beanClass) {
      var property = propertyMap.get(key);
      if (property == null) {
        throw new IllegalStateException("unknown property '" + key + "' for bean " + beanClass.getName());
      }
      return property;
    }

    public static Collector<Object> bean(Class<?> beanClass) {
      Objects.requireNonNull(beanClass);
      var beanInfo = Utils.beanInfo(beanClass);
      var propertyMap = Arrays.stream(beanInfo.getPropertyDescriptors())
              .collect(toMap(PropertyDescriptor::getName, property -> property));
      var constructor = Utils.defaultConstructor(beanClass);
      return new Collector<>(
              key -> findProperty(propertyMap, key, beanClass).getWriteMethod().getGenericParameterTypes()[0],
              () -> Utils.newInstance(constructor),
              (bean, key, value) -> Utils.invokeMethod(bean, findProperty(propertyMap, key, beanClass).getWriteMethod(), value),
              bean -> bean
      );
    }

    public static Collector<List<Object>> list(Type element) {
      Objects.requireNonNull(element);
      return new Collector<>(__ -> element, ArrayList::new, (list, key, value) -> list.add(value), List::copyOf);
    }
  }
}
