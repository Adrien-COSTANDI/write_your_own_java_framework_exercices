package com.github.forax.framework.mapper;

import java.lang.reflect.Method;

public record ParamMethod(Method method, String value, Class<?> type) {
}
