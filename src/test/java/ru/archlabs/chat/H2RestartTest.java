package ru.archlabs.chat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.archlabs.chat.storage.H2ChatStorage;

import java.nio.file.Path;
import java.sql.DriverManager;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

final class H2RestartTest {
    @TempDir
    Path directory;

    @Test
    void restoresStateAfterNormalRestart() {
        var url = jdbcUrl("normal");
        List<Message> expectedHistory;
        Instant beforeClose;
        try (ChatServer first = new ChatServerImpl(new H2ChatStorage(url))) {
            var ivan = first.connect("ivan", ignored -> {
            });
            ivan.setNotificationsEnabled(false);
            first.connect("andray", ignored -> {
            }).send("persisted");
            expectedHistory = ivan.history();
            beforeClose = Instant.now();
        }
        var afterClose = Instant.now();

        try (ChatServer restarted = new ChatServerImpl(new H2ChatStorage(url))) {
            var state = restarted.user("ivan");
            assertFalse(state.online());
            assertFalse(state.notificationsEnabled());
            assertFalse(state.lastConnected().isBefore(beforeClose));
            assertFalse(state.lastConnected().isAfter(afterClose));
            try (var ivan = restarted.connect("ivan", ignored -> {
            })) {
                assertEquals(expectedHistory, ivan.history());
            }
        }
    }

    @Test
    void restoresCommittedStateAfterAbnormalExit() throws Exception {
        var url = jdbcUrl("abnormal");
        ChatServer server = new ChatServerImpl(new H2ChatStorage(url));
        var writer = server.connect("ivan", ignored -> {
        });
        var expectedLastConnected = server.user("ivan").lastConnected();
        writer.setNotificationsEnabled(false);
        writer.send("before halt");
        var expectedHistory = writer.history();

        try (var connection = DriverManager.getConnection(url);
             var statement = connection.createStatement()) {
            statement.execute("SHUTDOWN IMMEDIATELY");
        }

        try (ChatServer restarted = new ChatServerImpl(new H2ChatStorage(url))) {
            var state = restarted.user("ivan");
            assertFalse(state.online());
            assertFalse(state.notificationsEnabled());
            assertEquals(expectedLastConnected, state.lastConnected());
            try (var ivan = restarted.connect("ivan", ignored -> {
            })) {
                assertEquals(expectedHistory, ivan.history());
            }
        }
    }

    private String jdbcUrl(String database) {
        return "jdbc:h2:file:"
                + directory.resolve(database).toAbsolutePath()
                + ";DB_CLOSE_ON_EXIT=FALSE";
    }
}
