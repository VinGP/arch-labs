package ru.archlabs.chat.http;

import io.prometheus.metrics.exporter.httpserver.HTTPServer;
import ru.archlabs.chat.ChatServerImpl;
import ru.archlabs.chat.storage.H2ChatStorage;

import java.util.concurrent.CountDownLatch;

public final class Main {
    private Main() {
    }

    // A shutdown hook must close resources before the JVM exits, not just wake the main thread.
    @SuppressWarnings("try")
    public static void main(String[] args) throws Exception {
        var chat = new ChatServerImpl(new H2ChatStorage("jdbc:h2:file:./data/chat;DB_CLOSE_ON_EXIT=FALSE"));
        try (var server = new JettyChatServer(chat, 8080);
             var metrics = HTTPServer.builder().hostname("127.0.0.1").port(9400).buildAndStart()) {
            server.start();
            var stopped = new CountDownLatch(1);
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                try {
                    server.close();
                } finally {
                    metrics.close();
                    stopped.countDown();
                }
            }, "chat-shutdown"));
            System.out.println("Chat: http://127.0.0.1:8080; metrics: http://127.0.0.1:9400/metrics");
            stopped.await();
        }
    }
}
