package com.arakviel.persistence.repository;

import com.arakviel.persistence.entity.GenericEntity;
import static java.lang.StringTemplate.STR;
import com.arakviel.persistence.config.ConnectionManager;
import java.lang.reflect.Field;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

public abstract class GenericRepository<T extends GenericEntity> implements Repository<T> {

    private final String tableName;
    private final RowMapper<T> rowMapper;
    private final JdbcTemplate jdbcTemplate;

    public GenericRepository(ConnectionManager connectionManager, String tableName, RowMapper<T> rowMapper) {
        this.jdbcTemplate = connectionManager.jdbcTemplate;
        this.tableName = tableName;
        this.rowMapper = rowMapper;
    }

    // Переписати на аспекти spring Context
    @Override
    public Optional<T> findById(UUID id) {
        return findBy("id", id);
    }

    @Override
    public Optional<T> findBy(String column, Object value) {
        final String sql = STR."""
            SELECT *
              FROM \{tableName}
             WHERE \{column} = ?
        """;
        try {
            return Optional.ofNullable(jdbcTemplate.queryForObject(sql, rowMapper, value));
        } catch(EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    @Override
    public List<T> findAll() {
        final String sql = STR."""
            SELECT *
              FROM \{tableName}
        """;
        return jdbcTemplate.query(sql, rowMapper);
    }

    @Override
    public T save(final T entity) {
        List<Object> values = tableValues(entity);

        T newEntity;
        if(Objects.isNull(entity.getId())) {
            UUID newId = UUID.randomUUID();
            values.addFirst(newId);
            newEntity = insert(values);
        } else {
            values.addLast(entity.getId());

            newEntity = update(values);
        }

        return newEntity;
    }

    protected T insert(List<Object> values) {
        List<String> attributes = tableAttributes();
        String attributesString = "id, " + String.join(", ", attributes);
        String placeholders = Stream.generate(() -> "?")
            .limit(attributes.size() + 1)
            .collect(Collectors.joining(", "));
        String sql = STR."""
                INSERT INTO \{tableName} (\{attributesString})
                VALUES (\{placeholders})
        """;

        if(attributes.stream().anyMatch(a -> a.equals("created_at"))) {
            values.add(LocalDateTime.now()); // created_at
            values.add(LocalDateTime.now()); // updated_at
        }

        jdbcTemplate.update(sql, values.toArray());
        // TODO: write custom exception
        return findById((UUID) values.getFirst()).orElseThrow();
    }

    protected T update(List<Object> values) {
        List<String> attributes = tableAttributes();
        String attributesString = attributes.stream()
            .filter(a -> !a.contains("created_at"))
            .map(a -> STR."\{a} = ?")
            .collect(Collectors.joining(", "));
        String sql = STR."""
              UPDATE \{tableName}
                 SET \{attributesString}
               WHERE id = ?
        """;

        if(attributes.stream().anyMatch(a -> a.equals("updated_at"))) {
            values.add(LocalDateTime.now()); // updated_at
        }

        jdbcTemplate.update(sql, values.toArray());
        // TODO: write custom exception
        return findById((UUID) values.getFirst()).orElseThrow();
    }

    @Override
    public int delete(UUID id) {
        final String sql = STR."""
            DELETE FROM \{tableName}
                  WHERE id = ?
        """;
        return jdbcTemplate.update(sql, id);
    }

    protected List<String> tableAttributes(Class<T> tClass) {
        Field[] fields = tClass.getDeclaredFields();
        List<String> names = new ArrayList<>();

        for (Field field : fields) {
            String fieldName = convertToSnakeCase(field.getName());

            if (field.getType().isPrimitive()) {
                names.add(fieldName.toLowerCase());
            } else {
                String fieldPackage = field.getType().getPackage() != null ? field.getType().getPackage().getName() : "";
                if (!fieldName.equals("id")) {
                    if (fieldPackage.equals(tClass.getPackage().getName())) {
                        names.add(fieldName.toLowerCase() + "_id");
                    } else {
                        names.add(fieldName.toLowerCase());
                    }
                }
            }
        }

        return names;
    }

    private static String convertToSnakeCase(String input) {
        StringBuilder result = new StringBuilder();
        for (String w : input.split("(?<!(^|[A-Z]))(?=[A-Z])|(?<!^)(?=[A-Z][a-z])")) {
            result.append(w).append("_");
        }

        String s = result.toString();
        return s.toLowerCase().substring(0,s.length()-1);

    }
    protected abstract List<String> tableAttributes();
    protected abstract List<Object> tableValues(T entity);
}