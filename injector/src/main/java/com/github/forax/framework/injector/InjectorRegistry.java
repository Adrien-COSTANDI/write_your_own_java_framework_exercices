package com.github.forax.framework.injector;

import java.beans.PropertyDescriptor;
import java.lang.reflect.Constructor;
import java.util.*;
import java.util.function.Supplier;
import java.util.stream.Stream;

public final class InjectorRegistry {
    private final Map<Class<?>, Supplier<Object>> registry = new HashMap<>();

    static List<PropertyDescriptor> findInjectableProperties(Class<?> type) {
        Objects.requireNonNull(type);
        var beanInfo = Utils.beanInfo(type);

        return Arrays.stream(beanInfo.getPropertyDescriptors())
                .filter(property -> {
                    var setter = property.getWriteMethod();
                    return setter != null && setter.getAnnotation(Inject.class) != null;
                })
                .toList();
    }

    public <T> void registerInstance(Class<T> type, T instance) {
        Objects.requireNonNull(type, "type is null");
        Objects.requireNonNull(instance, "instance is null");
        var test = registry.putIfAbsent(type, () -> instance);
        if (test != null) {
            throw new IllegalStateException("Already an instance for " + type.getName());
        }
    }

    public <T> T lookupInstance(Class<T> type) {
        Objects.requireNonNull(type, "type is null");

        var supplier = registry.get(type);
        if (supplier == null) {
            throw new IllegalStateException("No supplier for class " + type.getName());
        }
        return type.cast(supplier.get());
    }

    public <T> void registerProvider(Class<T> type, Supplier<? extends T> supplier) {
        Objects.requireNonNull(type, "type is null");
        Objects.requireNonNull(supplier, "supplier is null");
        var test = registry.putIfAbsent(type, supplier::get);
        if (test != null) {
            throw new IllegalStateException("Already an instance for " + type.getName());
        }
    }

    public void registerProviderClass(Class<?> type) {
        Objects.requireNonNull(type);
        registerProvider0(type);
    }

    private <T> void registerProvider0(Class<T> type) {
        registerProviderClass(type, type);
    }

    public <T> void registerProviderClass(Class<T> type, Class<? extends T> clazz) {
        Objects.requireNonNull(type);
        Objects.requireNonNull(clazz);

        var constructors = Arrays.stream(clazz.getConstructors())
                .filter(constructor -> constructor.isAnnotationPresent(Inject.class))
                .toList();

        var constructor = switch (constructors.size()) {
            case 0 -> Utils.defaultConstructor(clazz);
            case 1 -> (Constructor<? extends T>) constructors.get(0);
            default -> throw new IllegalStateException("Multiple constructors possible");
        };

//        var constructor = Utils.defaultConstructor(clazz);


        Supplier<T> supplier  = () -> {
            var args = Arrays.stream(constructor.getParameterTypes())
                    .map(this::lookupInstance)
                    .toArray();
            var instance = Utils.newInstance(constructor, args);
            findInjectableProperties(clazz).stream()
                    .map(PropertyDescriptor::getWriteMethod)
                    .forEach(setter -> Utils.invokeMethod(instance, setter, lookupInstance(setter.getParameterTypes()[0])));

            return instance;
        };


        registerProvider(type, supplier);
    }
    // TODO
}
