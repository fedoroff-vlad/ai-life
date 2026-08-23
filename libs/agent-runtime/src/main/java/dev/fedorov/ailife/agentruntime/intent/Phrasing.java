package dev.fedorov.ailife.agentruntime.intent;

import tools.jackson.databind.JsonNode;

import java.util.List;

/**
 * The user-facing wording of a {@link PickConfirmActRunner} flow — the one part that genuinely differs
 * per domain (delete says «Удалить … удалил», calendar says «Отменить … отменил» / «Перенести … перенёс»).
 * A delete flow gets it for free from {@link NounPhrasing} (driven by its {@link Nouns}); a domain whose
 * wording doesn't fit the delete template (calendar cancel/move) supplies its own by implementing this
 * interface and returning it from {@link TargetedActionFlow#phrasing()}.
 *
 * <p>Turn-1 methods that name a candidate receive the typed {@code T} ({@link #ambiguous}, {@link #confirm})
 * — the flow's own DTO, so it can render the summary + time itself. Turn-2 methods receive the stored
 * {@code pendingAction} node ({@link #declined}, {@link #done}, {@link #actFailed}) — the target is gone by
 * then, only the lock payload remains (its label, and any move/edit params).
 */
public interface Phrasing<T> {

    /** Blank input — "Какую задачу удалить?" / "Какое событие отменить?". */
    String askWhich();

    /** No household to search (the flow requires one and none was resolvable). */
    String noHousehold();

    /** The candidate pool came back empty — "Не нашёл задач…" / "Не нашёл предстоящих событий…". */
    String emptyPool();

    /** The LLM matched nothing / an out-of-range index — "Не нашёл такую задачу…". */
    String noMatch();

    /** The read / LLM stage soft-failed — "Не смог найти задачу для удаления…". */
    String readFailed();

    /** Resume with a {@code pendingAction} too broken to act on — "Нечего удалять…". */
    String notReady();

    /** Several plausible matches — a heading + one bulleted line per candidate. */
    String ambiguous(List<T> picks);

    /** The single resolved (and complete) target — the confirm question. {@code pick} carries any extra
     *  LLM fields a move/edit needs (the new time). */
    String confirm(T target, JsonNode pick);

    /** Resume, not affirmative — "Оставил «…» без изменений." Reads the label from {@code pending}. */
    String declined(JsonNode pending);

    /** Resume, acted OK — "Удалил задачу «…»." / "Перенёс «…» на …." Reads {@code pending}. */
    String done(JsonNode pending);

    /** Resume, the act threw — "Не смог удалить «…» — возможно, … уже удалена." Reads {@code pending}. */
    String actFailed(JsonNode pending);
}
