package ru.archlabs.chat;

import java.time.Instant;

public record Message(long id, String sender, String text, Instant sentAt) {
}
