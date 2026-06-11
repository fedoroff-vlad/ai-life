package dev.fedorov.ailife.tg.bot;

import dev.fedorov.ailife.contracts.agent.MessageScope;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.telegram.telegrambots.client.okhttp.OkHttpTelegramClient;
import org.telegram.telegrambots.longpolling.util.LongPollingSingleThreadUpdateConsumer;
import org.telegram.telegrambots.meta.api.methods.GetFile;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.File;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.User;
import org.telegram.telegrambots.meta.api.objects.PhotoSize;
import org.telegram.telegrambots.meta.api.objects.message.Message;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.meta.generics.TelegramClient;

import java.io.IOException;
import java.io.InputStream;
import java.util.Comparator;
import java.util.List;

/**
 * Long-polling Telegram consumer. Translates Telegram updates into
 * {@link MessageProcessor.IncomingMessage}, blocks on the reactive pipeline (we are
 * already on a polling thread), and replies via {@link TelegramClient}.
 */
public class AiLifeBot implements LongPollingSingleThreadUpdateConsumer {

    private static final Logger log = LoggerFactory.getLogger(AiLifeBot.class);

    private final TelegramClient client;
    private final MessageProcessor processor;

    public AiLifeBot(String botToken, MessageProcessor processor) {
        this(new OkHttpTelegramClient(botToken), processor);
    }

    AiLifeBot(TelegramClient client, MessageProcessor processor) {
        this.client = client;
        this.processor = processor;
    }

    @Override
    public void consume(Update update) {
        Message msg = update.getMessage();
        if (msg == null) {
            return;
        }
        User from = msg.getFrom();
        if (from == null || from.getIsBot()) {
            return;
        }
        // Only text and photo messages are supported today; everything else (stickers,
        // voice, documents, …) is ignored until its own processing path lands.
        if (!msg.hasText() && !msg.hasPhoto()) {
            return;
        }

        try {
            // For a photo message the text is in the caption (may be null).
            MessageProcessor.IncomingPhoto photo = msg.hasPhoto() ? downloadLargestPhoto(msg) : null;
            String text = msg.hasPhoto() ? msg.getCaption() : msg.getText();

            var incoming = new MessageProcessor.IncomingMessage(
                    from.getId(),
                    displayNameOf(from),
                    from.getLanguageCode(),
                    text,
                    scopeFor(msg),
                    String.valueOf(msg.getMessageId()),
                    photo);

            var response = processor.process(incoming).block();
            String reply = response != null && response.text() != null
                    ? response.text()
                    : "(no response)";
            send(msg.getChatId(), reply);
        } catch (Exception e) {
            log.error("Failed to handle update {}", update.getUpdateId(), e);
            send(msg.getChatId(), "Sorry, something broke. Please try again.");
        }
    }

    /**
     * Downloads the highest-resolution variant of an inbound photo. Telegram delivers a photo as a
     * list of {@link PhotoSize} thumbnails plus the original; we pick the largest by file size,
     * resolve its file path via {@code getFile}, then stream the bytes. The bytes are read fully
     * into memory — fine for receipts; media-service enforces the hard size cap.
     */
    private MessageProcessor.IncomingPhoto downloadLargestPhoto(Message msg)
            throws TelegramApiException, IOException {
        List<PhotoSize> sizes = msg.getPhoto();
        PhotoSize largest = sizes.stream()
                .max(Comparator.comparingInt(p -> p.getFileSize() == null ? 0 : p.getFileSize()))
                .orElseThrow(() -> new IllegalStateException("photo message had no sizes"));

        File tgFile = client.execute(GetFile.builder().fileId(largest.getFileId()).build());
        String path = tgFile.getFilePath();
        byte[] bytes;
        try (InputStream in = client.downloadFileAsStream(tgFile)) {
            bytes = in.readAllBytes();
        }
        return new MessageProcessor.IncomingPhoto(bytes, mimeFromPath(path), filenameFromPath(path, largest.getFileId()));
    }

    private static String mimeFromPath(String path) {
        String lower = path == null ? "" : path.toLowerCase();
        if (lower.endsWith(".png")) return "image/png";
        if (lower.endsWith(".webp")) return "image/webp";
        // Telegram re-encodes photos to JPEG; default accordingly.
        return "image/jpeg";
    }

    private static String filenameFromPath(String path, String fileId) {
        if (path != null) {
            int slash = path.lastIndexOf('/');
            String name = slash >= 0 ? path.substring(slash + 1) : path;
            if (!name.isBlank()) return name;
        }
        return "photo-" + fileId + ".jpg";
    }

    private void send(Long chatId, String text) {
        try {
            client.execute(SendMessage.builder()
                    .chatId(chatId)
                    .text(text)
                    .build());
        } catch (TelegramApiException e) {
            log.error("Failed to send reply to chat {}", chatId, e);
        }
    }

    private static String displayNameOf(User from) {
        if (from.getFirstName() != null && from.getLastName() != null) {
            return from.getFirstName() + " " + from.getLastName();
        }
        if (from.getFirstName() != null) {
            return from.getFirstName();
        }
        if (from.getUserName() != null) {
            return from.getUserName();
        }
        return "user-" + from.getId();
    }

    private static MessageScope scopeFor(Message msg) {
        return msg.isGroupMessage() || msg.isSuperGroupMessage()
                ? MessageScope.GROUP_CHAT
                : MessageScope.PRIVATE;
    }
}
