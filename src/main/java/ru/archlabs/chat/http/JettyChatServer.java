package ru.archlabs.chat.http;

import org.eclipse.jetty.ee10.servlet.ServletContextHandler;
import org.eclipse.jetty.ee10.servlet.ServletHolder;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import ru.archlabs.chat.ChatServer;

import java.time.Duration;
import java.util.Objects;

public final class JettyChatServer implements AutoCloseable {
    private final ChatServer chat;
    private final Server server = new Server();
    private final ServerConnector connector = new ServerConnector(server);
    private boolean closed;

    public JettyChatServer(ChatServer chat, int port) {
        this(chat, port, Duration.ofSeconds(25), 120);
    }

    public JettyChatServer(ChatServer chat, int port, Duration pollTimeout, int sessionTimeoutSeconds) {
        this.chat = Objects.requireNonNull(chat);
        if (port < 0 || port > 65535 || pollTimeout.toMillis() <= 0 || sessionTimeoutSeconds <= 0) {
            throw new IllegalArgumentException("Invalid port or timeout");
        }
        connector.setHost("127.0.0.1");
        connector.setPort(port);
        server.addConnector(connector);
        var context = new ServletContextHandler(ServletContextHandler.SESSIONS);
        context.setContextPath("/");
        context.getSessionHandler().setMaxInactiveInterval(sessionTimeoutSeconds);
        context.getSessionHandler().getSessionCookieConfig().setHttpOnly(true);
        var servlet = new ServletHolder(new ChatServlet(chat, pollTimeout));
        servlet.setAsyncSupported(true);
        context.addServlet(servlet, "/*");
        server.setHandler(context);
    }

    public void start() throws Exception {
        server.start();
    }

    public int port() {
        return connector.getLocalPort();
    }

    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }
        closed = true;
        try {
            server.stop();
        } catch (Exception exception) {
            throw new IllegalStateException("Cannot stop HTTP server", exception);
        } finally {
            chat.close();
        }
    }
}
