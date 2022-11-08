package com.github.forax.framework.orm;

import org.h2.command.Prepared;

import javax.sql.DataSource;
import java.beans.BeanInfo;
import java.beans.PropertyDescriptor;
import java.io.Serial;
import java.lang.reflect.Constructor;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static java.util.function.Predicate.not;

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

  // TODO

  private static final ThreadLocal<Connection> CONNECTION_THREAD_LOCAL = new ThreadLocal<>();

  public static void transaction(DataSource dataSource,
                                 TransactionBlock block) throws SQLException {

    Objects.requireNonNull(dataSource);
    Objects.requireNonNull(block);
    try (var connection = dataSource.getConnection()) {
      connection.setAutoCommit(false);
      CONNECTION_THREAD_LOCAL.set(connection);
      try {
        block.run();
        connection.commit();
      } catch (SQLException | RuntimeException e) {
        var cause = (e instanceof UncheckedSQLException unchecked)? unchecked.getCause(): e;
        try {
          connection.rollback();
        } catch (SQLException e2) {
          cause.addSuppressed(e2);
        }
        throw Utils.rethrow(cause);
      } finally {
        CONNECTION_THREAD_LOCAL.remove();
      }
    }
  }

  public static <R extends Repository<T, ID>, T, ID> R createRepository(Class<R> repositoryType) {
    Objects.requireNonNull(repositoryType);
    var beanType = findBeanTypeFromRepository(repositoryType);
    var beanInfo = Utils.beanInfo(beanType);
    var tableName = findTableName(beanType);
    var constructor = Utils.defaultConstructor(beanType);
    return repositoryType.cast(Proxy.newProxyInstance(repositoryType.getClassLoader(),
            new Class<?>[] { repositoryType },
            (proxy, method, args) -> {
              var connection = currentConnection();
              var name = method.getName();
              if (CONNECTION_THREAD_LOCAL.get() == null) {
                throw new IllegalStateException("no connection available");
              }
              try {
                return switch (name) {
                  case "findAll" -> {
                    var query = "SELECT * FROM " + tableName;
                    yield findAll(connection, query, beanInfo, constructor);
                  }
                  case "save" -> save(connection, tableName, beanInfo, args[0], null);
                  case "equals", "hashCode", "toString" ->
                          throw new UnsupportedOperationException("not supported " + method);
                  default -> throw new IllegalStateException("unknown method " + method);
                };
              } catch (SQLException e) {
                throw new UncheckedSQLException(e);
              }
            }
    ));
  }

  static Connection currentConnection() {
    var connection = CONNECTION_THREAD_LOCAL.get();
    if (connection == null) {
      throw new IllegalStateException("no connection available");
    }
    return connection;
  }

  public static void createTable(Class<?> beanClass) throws SQLException {
    Objects.requireNonNull(beanClass);
    var connection = currentConnection();
    var tableName = findTableName(beanClass);
    var beanInfo = Utils.beanInfo(beanClass);
    var joiner = new StringJoiner(",\n");
    var id = (String) null;
    for (var property : beanInfo.getPropertyDescriptors()) {
      if (property.getName().equals("class")) {
        continue;
      }
      var columnName = findColumnName(property);
      if (isId(property)) {
        if (id != null) {
          throw new IllegalStateException("multiple id defined" + id + " " + columnName);
        }
        id = columnName;
      }
      var line = columnName + " " + findColumnType(property);
      joiner.add(line);
    }
    if (id != null) {
      joiner.add("PRIMARY KEY (" + id + ")");
    }
    var text = "CREATE TABLE " + tableName + "(\n" + joiner.toString() + ");";
    try (var statement = connection.createStatement()) {
      statement.execute(text);
    }
    connection.commit();
  }

  private static String findColumnType(PropertyDescriptor property) {
    var type = property.getPropertyType();
    var mapping = TYPE_MAPPING.get(type);
    if (mapping == null) {
      throw new IllegalStateException("unknown property type " + property);
    }
    var nullable = type.isPrimitive() ? " NOT NULL" : "";
    var generatedValue = property.getReadMethod().isAnnotationPresent(GeneratedValue.class);
    var autoIncrement = generatedValue ? " AUTO_INCREMENT" : "";
    return mapping + nullable + autoIncrement;
  }

  static String findTableName(Class<?> beanClass) {
    var table = beanClass.getAnnotation(Table.class);
    var name = table == null ? beanClass.getSimpleName() : table.value();
    return name.toUpperCase(Locale.ROOT);
  }

  static String findColumnName(PropertyDescriptor property) {
    var column = property.getReadMethod().getAnnotation(Column.class);
    var name = column == null ? property.getName() : column.value();
    return name.toUpperCase(Locale.ROOT);
  }

  static Object toEntityClass(ResultSet resultSet,
                              BeanInfo beanInfo,
                              Constructor<?> constructor) throws SQLException {
    var instance = Utils.newInstance(constructor);
    var properties = beanInfo.getPropertyDescriptors();
    for (var property : properties) {
      var name = property.getName();
      if (name.equals("class")) {
        continue;
      }
      var value = resultSet.getObject(name);
      Utils.invokeMethod(instance, property.getWriteMethod(), value);
    }
    return instance;
  }

  static List<?> findAll(Connection connection,
                              String query,
                              BeanInfo beanInfo,
                              Constructor<?> constructor) throws SQLException {
    var list = new ArrayList<>();
    try (var statement = connection.prepareStatement(query)) {
      try (var resultSet = statement.executeQuery()) {
        while (resultSet.next()) {
          var instance = toEntityClass(resultSet, beanInfo, constructor);
          list.add(instance);
        }
      }
    }
    return list;
  }

  static Object save(Connection connection,
                     String tableName,
                     BeanInfo beanInfo,
                     Object bean,
                     String idProperty) throws SQLException {
    var query = createSaveQuery(tableName, beanInfo);
    try (var statement = connection.prepareStatement(query)) {
      var index = 1;
      for (var property : beanInfo.getPropertyDescriptors()) {
        if (property.getName().equals("class")) {
          continue;
        }
        var getter = property.getReadMethod();
        var value = Utils.invokeMethod(bean, getter);
        statement.setObject(index++, value);
      }
      statement.executeUpdate();
    }
    return bean;
  }

  static String createSaveQuery(String tableName,
                                                   BeanInfo beanInfo) {
    var properties = beanInfo.getPropertyDescriptors();
    var columnNames = Arrays.stream(properties)
            .map(PropertyDescriptor::getName)
            .filter(not("class"::equals))
            .collect(Collectors.joining(", "));
    var jokers = String.join(", ",
            Collections.nCopies(properties.length - 1, "?"));

    var query = """
            INSERT INTO %s (%s) VALUES (%s);\
            """.formatted(
                    tableName,
                    columnNames,
                    jokers
                );
    return query;
  }

  private static boolean isId(PropertyDescriptor property) {
    return property.getReadMethod().isAnnotationPresent(Id.class);
  }



}
