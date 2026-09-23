package ru.archlabs.chat.storage;

import ru.archlabs.chat.Message;
import ru.archlabs.chat.UserState;

import java.sql.*;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public final class H2ChatStorage implements ChatStorage {
    private final Connection connection;

    public H2ChatStorage(String jdbcUrl) {
        try {
            connection = DriverManager.getConnection(jdbcUrl);
            configureDurability();
            initializeSchema();
            resetOnlineUsers();
        } catch (SQLException exception) {
            throw new StorageException("Cannot open H2 chat storage", exception);
        }
    }

    @Override
    public void connect(String name, Instant at) {
        transaction(
                () -> {
                    var existing = findUser(name);
                    if (existing.isPresent()) {
                        if (existing.orElseThrow().online()) {
                            throw new IllegalStateException("User is already online: " + name);
                        }
                        try (var statement =
                                 connection.prepareStatement(
                                         "UPDATE users SET online = TRUE, last_connected = ? WHERE name = ?")) {
                            statement.setObject(1, utc(at));
                            statement.setString(2, name);
                            statement.executeUpdate();
                        }
                    } else {
                        try (var statement =
                                 connection.prepareStatement(
                                             "INSERT INTO users(name, online, last_connected, notifications_enabled) VALUES (?, TRUE, ?, TRUE)")) {
                            statement.setString(1, name);
                            statement.setObject(2, utc(at));
                            statement.executeUpdate();
                        }
                    }
                    return null;
                });
    }

    @Override
    public void disconnect(String name, Instant at) {
        transaction(
                () -> {
                    try (var statement =
                                 connection.prepareStatement(
                                         "UPDATE users SET online = FALSE, last_connected = ? WHERE name = ?")) {
                        statement.setObject(1, utc(at));
                        statement.setString(2, name);
                        requireUpdated(statement.executeUpdate(), name);
                    }
                    return null;
                });
    }

    @Override
    public void setNotificationsEnabled(String name, boolean enabled) {
        transaction(
                () -> {
                    try (var statement =
                                 connection.prepareStatement(
                                         "UPDATE users SET notifications_enabled = ? WHERE name = ?")) {
                        statement.setBoolean(1, enabled);
                        statement.setString(2, name);
                        requireUpdated(statement.executeUpdate(), name);
                    }
                    return null;
                });
    }

    @Override
    public Message append(String sender, String text, Instant sentAt) {
        return transaction(
                () -> {
                    try (var statement =
                                 connection.prepareStatement(
                                         "INSERT INTO messages(sender, text, sent_at) VALUES (?, ?, ?)",
                                         Statement.RETURN_GENERATED_KEYS)) {
                        statement.setString(1, sender);
                        statement.setString(2, text);
                        statement.setObject(3, utc(sentAt));
                        statement.executeUpdate();
                        try (var keys = statement.getGeneratedKeys()) {
                            if (!keys.next()) {
                                throw new SQLException("H2 did not return a message ID");
                            }
                            return new Message(keys.getLong(1), sender, text, sentAt);
                        }
                    }
                });
    }

    @Override
    public List<Message> history() {
        try (var statement =
                     connection.prepareStatement(
                             "SELECT id, sender, text, sent_at FROM messages ORDER BY id");
             var result = statement.executeQuery()) {
            var messages = new ArrayList<Message>();
            while (result.next()) {
                messages.add(
                        new Message(
                                result.getLong("id"),
                                result.getString("sender"),
                                result.getString("text"),
                                instant(result, "sent_at")));
            }
            return List.copyOf(messages);
        } catch (SQLException exception) {
            throw failure("read message history", exception);
        }
    }

    @Override
    public Optional<UserState> user(String name) {
        try {
            return findUser(name);
        } catch (SQLException exception) {
            throw failure("read user", exception);
        }
    }

    @Override
    public List<UserState> onlineUsersWithNotificationsEnabled() {
        try (var statement =
                     connection.prepareStatement(
                             "SELECT name, online, last_connected, notifications_enabled FROM users "
                                     + "WHERE online = TRUE AND notifications_enabled = TRUE ORDER BY name");
             var result = statement.executeQuery()) {
            var users = new ArrayList<UserState>();
            while (result.next()) {
                users.add(mapUser(result));
            }
            return List.copyOf(users);
        } catch (SQLException exception) {
            throw failure("read online users", exception);
        }
    }

    @Override
    public void close() {
        try {
            connection.close();
        } catch (SQLException exception) {
            throw failure("close storage", exception);
        }
    }

    private void initializeSchema() throws SQLException {
        transaction(
                () -> {
                    try (var statement = connection.createStatement()) {
                        statement.executeUpdate(
                                """
                                        CREATE TABLE IF NOT EXISTS users (
                                            name VARCHAR PRIMARY KEY,
                                            online BOOLEAN NOT NULL,
                                            last_connected TIMESTAMP(9) WITH TIME ZONE,
                                            notifications_enabled BOOLEAN NOT NULL
                                        )
                                        """);
                        statement.executeUpdate(
                                """
                                        CREATE TABLE IF NOT EXISTS messages (
                                            id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
                                            sender VARCHAR NOT NULL,
                                            text VARCHAR NOT NULL,
                                            sent_at TIMESTAMP(9) WITH TIME ZONE NOT NULL,
                                            FOREIGN KEY (sender) REFERENCES users(name)
                                        )
                                        """);
                    }
                    return null;
                });
    }

    private void configureDurability() throws SQLException {
        try (var statement = connection.createStatement()) {
            statement.executeUpdate("SET WRITE_DELAY 0");
        }
    }

    private void resetOnlineUsers() {
        transaction(
                () -> {
                    try (var statement = connection.createStatement()) {
                        statement.executeUpdate("UPDATE users SET online = FALSE");
                    }
                    return null;
                });
    }

    private Optional<UserState> findUser(String name) throws SQLException {
        try (var statement =
                     connection.prepareStatement(
                             "SELECT name, online, last_connected, notifications_enabled FROM users WHERE name = ?")) {
            statement.setString(1, name);
            try (var result = statement.executeQuery()) {
                return result.next() ? Optional.of(mapUser(result)) : Optional.empty();
            }
        }
    }

    private UserState mapUser(ResultSet result) throws SQLException {
        var lastConnected = result.getObject("last_connected", OffsetDateTime.class);
        return new UserState(
                result.getString("name"),
                result.getBoolean("online"),
                lastConnected == null ? null : lastConnected.toInstant(),
                result.getBoolean("notifications_enabled"));
    }

    private Instant instant(ResultSet result, String column) throws SQLException {
        return result.getObject(column, OffsetDateTime.class).toInstant();
    }

    private <T> T transaction(SqlSupplier<T> operation) {
        try {
            connection.setAutoCommit(false);
            try {
                var result = operation.get();
                connection.commit();
                return result;
            } catch (SQLException | RuntimeException exception) {
                connection.rollback();
                throw exception;
            } finally {
                connection.setAutoCommit(true);
            }
        } catch (SQLException exception) {
            throw failure("execute transaction", exception);
        }
    }

    private static OffsetDateTime utc(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    private static void requireUpdated(int count, String name) {
        if (count != 1) {
            throw new IllegalArgumentException("Unknown user: " + name);
        }
    }

    private static StorageException failure(String operation, SQLException exception) {
        return new StorageException("Cannot " + operation, exception);
    }

    @FunctionalInterface
    private interface SqlSupplier<T> {
        T get() throws SQLException;
    }
}
