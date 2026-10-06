package ru.archlabs.chat.http;

import jakarta.servlet.AsyncContext;
import jakarta.servlet.AsyncEvent;
import jakarta.servlet.AsyncListener;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSessionBindingEvent;
import jakarta.servlet.http.HttpSessionBindingListener;
import ru.archlabs.chat.ChatSession;
import ru.archlabs.chat.Message;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

final class HttpChatSession implements HttpSessionBindingListener, AutoCloseable {
    // ponytail: in-memory unbounded buffer; cap and disconnect slow clients for production.
    private final List<Message> inbox = new ArrayList<>();
    private final Consumer<HttpChatSession> onClose;
    private ChatSession chat;
    private AsyncContext pending;
    private boolean closed;

    HttpChatSession(Consumer<HttpChatSession> onClose) {
        this.onClose = onClose;
    }

    void attach(ChatSession chat) {
        this.chat = chat;
    }

    ChatSession chat() {
        return chat;
    }

    synchronized boolean isClosed() {
        return closed;
    }

    void accept(Message message) {
        AsyncContext waiting;
        synchronized (this) {
            if (closed) {
                throw new IllegalStateException("Connection is closed");
            }
            inbox.add(message);
            waiting = pending;
            if (waiting != null) {
                pending = null;
                reply(waiting, 200, drain());
            }
        }
    }

    void poll(HttpServletRequest request, long timeoutMillis) {
        synchronized (this) {
            if (closed) {
                throw new ChatServlet.BadRequest(401, "Connection is closed");
            }
            if (pending != null) {
                throw new ChatServlet.BadRequest(409, "A notification request is already waiting");
            }
            var context = request.startAsync();
            context.setTimeout(timeoutMillis);
            context.addListener(new AsyncListener() {
                @Override
                public void onTimeout(AsyncEvent event) {
                    finish(context, 204);
                }

                @Override
                public void onError(AsyncEvent event) {
                    synchronized (HttpChatSession.this) {
                        if (pending == context) {
                            pending = null;
                        }
                    }
                }

                @Override
                public void onComplete(AsyncEvent event) {
                }

                @Override
                public void onStartAsync(AsyncEvent event) {
                }
            });
            if (inbox.isEmpty()) {
                pending = context;
            } else {
                reply(context, 200, drain());
            }
        }
    }

    private void finish(AsyncContext context, int status) {
        synchronized (this) {
            if (pending != context) {
                // Jetty may reject dispatch from a sender after expiration has begun.
                // The timeout thread can still dispatch the response already chosen by that sender.
                if (context.getRequest().getAttribute(ChatServlet.POLL_REPLY) != null) {
                    dispatch(context);
                }
                return;
            }
            pending = null;
            reply(context, status, List.of());
        }
    }

    private List<Message> drain() {
        var messages = List.copyOf(inbox);
        inbox.clear();
        return messages;
    }

    private static void reply(AsyncContext context, int status, List<Message> messages) {
        context.getRequest().setAttribute(ChatServlet.POLL_REPLY, new PollReply(status, messages));
        dispatch(context);
    }

    private static void dispatch(AsyncContext context) {
        try {
            // Only schedules the servlet; network writes happen there, outside this lock.
            context.dispatch();
        } catch (IllegalStateException ignored) {
            // A racing timeout retries on its own thread; a failed connection has no ACK.
        }
    }

    @Override
    public void valueUnbound(HttpSessionBindingEvent event) {
        close();
    }

    @Override
    public void close() {
        AsyncContext waiting;
        synchronized (this) {
            if (closed) {
                return;
            }
            closed = true;
            inbox.clear();
            waiting = pending;
            pending = null;
            if (waiting != null) {
                reply(waiting, 401, List.of());
            }
        }
        try {
            chat.close();
        } finally {
            onClose.accept(this);
        }
    }

    record PollReply(int status, List<Message> messages) {
    }
}
