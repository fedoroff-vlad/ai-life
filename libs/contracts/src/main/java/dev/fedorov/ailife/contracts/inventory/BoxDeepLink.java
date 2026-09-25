package dev.fedorov.ailife.contracts.inventory;

// wire-contract-exempt: not a serialised DTO — a shared literal + its parser, agreed between the label
// renderer and the gateway's /start dispatch. The wire hop it enables (gateway → hub → inventory
// show_container) travels on the existing AgentActionRequest/Result contract, asserted by
// ActionControllerScanTest + the gateway's AiLifeBotScanTest; the photographed-label half closes with
// E2EInventoryScanFlowTest (IN-f2).

/**
 * The shape of a container label's Telegram deep link — the one literal the printed sticker and the
 * front door must agree on (IN-f).
 *
 * <p>Kept here (in {@code contracts}) for the same reason as {@link
 * dev.fedorov.ailife.contracts.agent.PendingActionHints}: the producer (inventory-agent's
 * {@code BoxLabelImage}, which renders the QR) and the consumer (gateway-telegram's {@code /start}
 * dispatch) must not drift apart, and neither module may depend on the other. A prefix that drifts
 * would silently turn every already-printed sticker into an unknown family-invite token.
 *
 * <p><b>Why a prefix at all:</b> {@code /start <payload>} was already taken by family invites
 * (ADR-0001 slice 4b-i), so a box scan is told apart by its prefix rather than by new plumbing — an
 * unprefixed payload keeps today's invite behaviour exactly.
 */
public final class BoxDeepLink {

    /** Marks a {@code /start} payload as a container scan rather than a family-invite token. */
    public static final String PREFIX = "box_";

    private BoxDeepLink() {
    }

    /** The deep link a printed label encodes: {@code https://t.me/<bot>?start=box_<qrToken>}. */
    public static String of(String botUsername, String qrToken) {
        return "https://t.me/" + botUsername + "?start=" + PREFIX + qrToken;
    }

    /**
     * The container token inside a {@code /start} payload, or {@code null} when the payload is not a
     * box scan (a family invite, or anything else — those stay on their existing path).
     */
    public static String tokenOf(String startPayload) {
        if (startPayload == null || !startPayload.startsWith(PREFIX)) {
            return null;
        }
        String token = startPayload.substring(PREFIX.length()).trim();
        return token.isEmpty() ? null : token;
    }

    /**
     * The container token inside a <b>decoded QR payload</b> — the whole {@code t.me/<bot>?start=box_…}
     * URL a reader lifts off a label — or {@code null} when the image held some other code (a retail
     * barcode, someone else's QR). Used when the owner photographs the sticker instead of opening it
     * with a camera app. The bot username is deliberately not matched: a label printed before the bot
     * was renamed must still resolve to its box.
     */
    public static String tokenOfUrl(String decodedPayload) {
        if (decodedPayload == null) {
            return null;
        }
        int marker = decodedPayload.indexOf("?start=");
        if (marker < 0) {
            return null;
        }
        String payload = decodedPayload.substring(marker + "?start=".length()).trim();
        int amp = payload.indexOf('&');
        return tokenOf(amp < 0 ? payload : payload.substring(0, amp));
    }
}
