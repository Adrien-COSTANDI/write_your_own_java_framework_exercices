package com.github.forax.framework.orm;

import javax.sql.DataSource;
import java.beans.BeanInfo;
import java.beans.Introspector;
import java.beans.PropertyDescriptor;
import java.io.Serial;
import java.lang.reflect.Constructor;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public final class ORM {

  private ORM() {
    throw new AssertionError();
  }

  @FunctionalInterface
  public interface TransactionBlock {
    void run() throws SQLException;
  }

  private static final Map<Class<?>, String> TYPE_MAPPING = Map.of(
          int.class, "INTEGER",
          Integer.class, "INTEGER",
          long.class, "BIGINT",
          Long.class, "BIGINT",
          String.class, "VARCHAR(255)"
  );

  private static Class<?> findBeanTypeFromRepository(Class<?> repositoryType) {
    var repositorySupertype = Arrays.stream(repositoryType.getGenericInterfaces())
            .flatMap(superInterface -> {
              if (superInterface instanceof ParameterizedType parameterizedType
                  && parameterizedType.getRawType() == Repository.class) {
                return Stream.of(parameterizedType);
              }
              return null;
            })
            .findFirst()
            .orElseThrow(() -> new IllegalArgumentException("invalid repository interface " + repositoryType.getName()));
    var typeArgument = repositorySupertype.getActualTypeArguments()[0];
    if (typeArgument instanceof Class<?> beanType) {
      return beanType;
    }
    throw new IllegalArgumentException("invalid type argument " + typeArgument + " for repository interface " + repositoryType.getName());
  }

  private static class UncheckedSQLException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 42L;

    private UncheckedSQLException(SQLException cause) {
      super(cause);
    }

    @Override
    public SQLException getCause() {
      return (SQLException) super.getCause();
    }
  }


  // --- do not change the code above

  private static final ThreadLocal<Connection> CONNECTION_THREAD_LOCAL = new ThreadLocal<>();

  public static void transaction(DataSource datasource, TransactionBlock transaction) throws SQLException {
    Objects.requireNonNull(datasource);
    Objects.requireNonNull(transaction);
    try (var connection = datasource.getConnection()) {
      connection.setAutoCommit(false);
      CONNECTION_THREAD_LOCAL.set(connection);
      try {
        try {
          transaction.run();
        } catch (UncheckedSQLException e) {
          throw e.getCause();
        }
        connection.commit();
      } catch (SQLException | RuntimeException e) {
        try {
          connection.rollback();
        } catch (SQLException e2) {
          e.addSuppressed(e2);
        }
        throw e;
      } finally {
        CONNECTION_THREAD_LOCAL.remove();
      }
    }
  }

  static Connection currentConnection() {
    var connection = CONNECTION_THREAD_LOCAL.get();
    if (connection == null) {
      throw new IllegalStateException("Not in a transaction");
    }
    return connection;
  }

  static String findTableName(Class<?> bean) {
    var tableAnnotation = bean.getAnnotation(Table.class);
    var name = tableAnnotation == null ? bean.getSimpleName() : tableAnnotation.value();
    return name.toUpperCase(Locale.ROOT);
  }

  static String findColumnName(PropertyDescriptor property) {
    var column = property.getReadMethod().getAnnotation(Column.class);
    return column == null ? property.getName() : column.value();
  }

  private static String createTableQuery(Class<?> bean) {
    var beanInfo = Utils.beanInfo(bean);
    var params = Arrays.stream(beanInfo.getPropertyDescriptors())
            .filter(property -> !property.getName().equals("class"))
            .map(ORM::getLineForColumn)
            .collect(Collectors.joining(",\n", "(", ")"));

    return "CREATE TABLE " + findTableName(bean) + params + ";";
  }

  private static String getLineForColumn(PropertyDescriptor property) {
// FIXME
//    if (property.getWriteMethod() == null) {
//      throw new IllegalStateException("no setter for " + property.getName());
//    }
    return findColumnName(property) + " " + TYPE_MAPPING.get(property.getPropertyType()) +
           (property.getPropertyType().isPrimitive() ? " NOT NULL" : "") +
           (property.getReadMethod().isAnnotationPresent(Id.class) ? " PRIMARY KEY" : "") +
           (property.getReadMethod().isAnnotationPresent(GeneratedValue.class) ? " AUTO_INCREMENT" : "");
  }

  public static void createTable(Class<?> bean) throws SQLException {
    Objects.requireNonNull(bean);
    var connection = currentConnection();
    try (var statement = connection.createStatement()) {
      statement.executeUpdate(createTableQuery(bean));
    }
    connection.commit();
  }

  /**
   * Creates a repository which is a class generated at runtime implemented by the proxy
   *
   * @param typeRepository the repository interface to implement
   * @param <T>            the type manipulated by the repository
   * @param <ID>           the primary key type
   * @param <REPO>         a repository (or subtype of repository)
   * @return the repository (or subtype of repository) instance generated (the proxy)
   */
  public static <T, ID, REPO extends Repository<T, ID>> REPO createRepository(Class<? extends REPO> typeRepository) {
    var beanClass = findBeanTypeFromRepository(typeRepository);
    var tableName = findTableName(beanClass);
    var beanInfo = Utils.beanInfo(beanClass);
    var defaultConstructor = Utils.defaultConstructor(beanClass);
    var idProperty = findId(beanInfo);

    return typeRepository.cast(Proxy.newProxyInstance(typeRepository.getClassLoader(), // usefull at runtime to generate the class
            new Class<?>[]{typeRepository},       // list of interfaces the generated class needs to implement
            (proxy, method, args) -> {  // how to run methods of the generated class
              var methodName = method.getName();
              var connection = currentConnection();

              if (method.getDeclaringClass() == Object.class) {
                throw new UnsupportedOperationException(methodName + " unsupported");
              }

              try {
                var query = method.getAnnotation(Query.class);
                if (query != null) {
                  return findAll(connection, query.value(), beanInfo, defaultConstructor, args);
                }
                return switch (methodName) {
                  case "findAll" -> findAll(connection, "SELECT * FROM " + tableName, beanInfo,
                          defaultConstructor); // List.of();   // for now
                  case "save" -> save(connection, tableName, beanInfo, args[0], idProperty); // List.of()
                  case "findById" -> findAll(connection,
                          "SELECT * FROM " + tableName + " WHERE " + (idProperty == null ? null :
                                  findColumnName(idProperty)) + " = ?;",
                          beanInfo, defaultConstructor, args).stream().findFirst();

                  case "equals", "hashCode", "toString" ->
                          throw new UnsupportedOperationException(methodName + " unsupported");
                  default -> {
                    if (methodName.startsWith("findBy")) {
                      var name = methodName.substring("findBy".length());
                      var propertyName = Introspector.decapitalize(name);
                      var property = findProperty(beanInfo, propertyName);
                      yield findAll(connection, """
                                      SELECT * FROM %s WHERE %s = ?\
                                      """.formatted(tableName, findColumnName(property)), beanInfo,
                              defaultConstructor, args[0]).stream().findFirst();
                    }
                    throw new IllegalStateException("unknown method " + method);
                  }
                };
              } catch (SQLException e) {
                throw new UncheckedSQLException(e);
              }
            }));
  }

  static Object toEntityClass(ResultSet resultSet, BeanInfo beanInfo,
                              Constructor<?> constructor) throws SQLException {
    var instance = Utils.newInstance(constructor);
    var properties = beanInfo.getPropertyDescriptors();
    var index = 1;
    for (PropertyDescriptor property : properties) {
      if (property.getName().equals("class")) {
        continue;
      }
      var writeMethod = property.getWriteMethod();
      if (writeMethod != null) {
        var value = resultSet.getObject(index);
        Utils.invokeMethod(instance, writeMethod, value);
      }
      index++;
    }
    return instance;
  }

  static List<?> findAll(Connection connection, String sqlQuery, BeanInfo beanInfo,
                         Constructor<?> constructor, Object... params) throws SQLException {
    var list = new ArrayList<>();
    try (var statement = connection.prepareStatement(sqlQuery)) {
      if (params != null) {
        for (int i = 0; i < params.length; i++) {
          statement.setObject(i + 1, params[i]);
        }
      }
      try (var resultSet = statement.executeQuery()) {
        while (resultSet.next()) {
          var instance = toEntityClass(resultSet, beanInfo, constructor);
          list.add(instance);
        }
      }
    }
    return list;
  }

  static String createSaveQuery(String tableName, BeanInfo beanInfo) {
    var params = Arrays.stream(beanInfo.getPropertyDescriptors())
            .map(PropertyDescriptor::getName)
            .filter(name -> !name.equals("class"))
            .collect(Collectors.joining(", ", "(", ")"));

    var values = Arrays.stream(beanInfo.getPropertyDescriptors())
            .filter(property -> !property.getName().equals("class"))
            .map(property -> "?")
            .collect(Collectors.joining(", ", "(", ")"));

    return "MERGE INTO " + tableName + " " + params + " VALUES " + values + ";";
  }

  static <T> T save(Connection connection, String tableName, BeanInfo beanInfo,
                    T instance, PropertyDescriptor idProperty) throws SQLException {
    var query = createSaveQuery(tableName, beanInfo);
    try (var statement = connection.prepareStatement(query, Statement.RETURN_GENERATED_KEYS)) {
      var index = 1;
      for (var property : beanInfo.getPropertyDescriptors()) {
        if (property.getName().equals("class")) {
          continue;
        }
        var getter = property.getReadMethod();
        var value = Utils.invokeMethod(instance, getter);
        statement.setObject(index, value);
        index++;
      }
      statement.executeUpdate();
      if (idProperty != null) { // on va checker s'il y a un auto_increment
        try (var resultSet = statement.getGeneratedKeys()) {
          if (resultSet.next()) {
            var key = resultSet.getObject(1);
            var setter = idProperty.getWriteMethod();
            Utils.invokeMethod(instance, setter, key);
          }
        }
      }
    }
    return instance;
  }

  static PropertyDescriptor findId(BeanInfo beanInfo) {
    return Arrays.stream(beanInfo.getPropertyDescriptors())
            .filter(property -> !property.getName().equals("class"))
            .filter(property -> property.getReadMethod().isAnnotationPresent(Id.class))
            .findFirst()
            .orElse(null);
  }

  static PropertyDescriptor findProperty(BeanInfo beanInfo, String name) {
    return Arrays.stream(beanInfo.getPropertyDescriptors())
            .filter(property -> property.getName().equals(name))
            .findFirst()
            .orElseThrow(() -> new IllegalStateException("no property named " + name));
  }
}
