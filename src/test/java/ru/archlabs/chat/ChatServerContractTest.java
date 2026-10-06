package ru.archlabs.chat;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import io.prometheus.metrics.model.registry.PrometheusRegistry;
import ru.archlabs.chat.storage.ChatStorage;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.LongStream;

import static org.junit.jupiter.api.Assertions.*;

abstract class ChatServerContractTest {
    private ChatServer server;

    abstract ChatStorage createStorage();

    @BeforeEach
    void setUp() {
        server = new ChatServerImpl(createStorage());
    }

    @AfterEach
    void tearDown() {
        server.close();
    }

    @Test
    void collectsChatMetrics() {
        server.connect("sender", ignored -> {}).send("hello");

        var names = PrometheusRegistry.defaultRegistry.scrape().stream()
                .map(metric -> metric.getMetadata().getName()).toList();
        // Counter names in the registry omit the exported _total suffix.
        assertTrue(names.contains("chat_requests"));
        assertTrue(names.contains("chat_notifications"));
    }

    @Test
    void tracksConnectionAndDisconnection() {
        var ivan = server.connect("ivan", ignored -> {
        });

        var connected = server.user("ivan");
        assertTrue(connected.online());
        assertTrue(connected.notificationsEnabled());
        assertNotNull(connected.lastConnected());

        ivan.close();

        var disconnected = server.user("ivan");
        assertFalse(disconnected.online());
        assertNotNull(disconnected.lastConnected());
    }

    @Test
    void deliversToEveryOnlineUserWithNotificationsIncludingSender() {
        var ivanInbox = new ArrayList<Message>();
        var andrayInbox = new ArrayList<Message>();
        server.connect("ivan", ivanInbox::add);
        var andray = server.connect("andray", andrayInbox::add);

        andray.send("hello");

        assertEquals(1, ivanInbox.size());
        assertEquals("andray", ivanInbox.getFirst().sender());
        assertEquals("hello", ivanInbox.getFirst().text());
        assertEquals(ivanInbox, andrayInbox);
    }

    @Test
    void skipsOfflineAndNotificationsDisabledUsers() {
        var offlineInbox = new ArrayList<Message>();
        var silentInbox = new ArrayList<Message>();
        var offline = server.connect("offline", offlineInbox::add);
        var silent = server.connect("silent", silentInbox::add);
        var sender = server.connect("sender", ignored -> {
        });
        offline.close();
        silent.setNotificationsEnabled(false);

        sender.send("hello");

        assertTrue(offlineInbox.isEmpty());
        assertTrue(silentInbox.isEmpty());
    }

    @Test
    void disabledUserCanReadCompleteOrderedHistory() {
        var ivan = server.connect("ivan", ignored -> {
        });
        var andray = server.connect("andray", ignored -> {
        });
        ivan.setNotificationsEnabled(false);

        andray.send("first");
        ivan.send("second");

        var history = ivan.history();
        assertEquals(List.of(1L, 2L), history.stream().map(Message::id).toList());
        assertEquals(List.of("first", "second"), history.stream().map(Message::text).toList());
    }

    @Test
    void concurrentClientsDoNotLoseMessages() throws Exception {
        var reader = server.connect("reader", ignored -> {
        });
        reader.setNotificationsEnabled(false);
        var barrier = new CyclicBarrier(2);

        try (var pool = Executors.newFixedThreadPool(2)) {
            var ivan =
                    pool.submit(
                            () -> {
                                sendMessages("ivan", barrier);
                                return null;
                            });
            var andray =
                    pool.submit(
                            () -> {
                                sendMessages("andray", barrier);
                                return null;
                            });
            ivan.get(5, TimeUnit.SECONDS);
            andray.get(5, TimeUnit.SECONDS);
        }

        var history = reader.history();
        assertEquals(200, history.size());
        assertEquals(
                LongStream.rangeClosed(1, 200).boxed().toList(),
                history.stream().map(Message::id).toList());
        assertEquals(100, history.stream().filter(message -> message.sender().equals("ivan")).count());
        assertEquals(100, history.stream().filter(message -> message.sender().equals("andray")).count());
    }

    private void sendMessages(String username, CyclicBarrier barrier) throws Exception {
        try (var session = server.connect(username, ignored -> {
        })) {
            barrier.await(2, TimeUnit.SECONDS);
            for (int i = 0; i < 100; i++) {
                session.send(username + "-" + i);
            }
        }
    }
}
