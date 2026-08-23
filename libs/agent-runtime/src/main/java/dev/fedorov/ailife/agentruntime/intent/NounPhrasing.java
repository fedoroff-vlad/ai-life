package dev.fedorov.ailife.agentruntime.intent;

import tools.jackson.databind.JsonNode;

import java.util.List;

/**
 * The default {@link Phrasing} for a <b>delete</b> flow: the shared "Удалить … удалил" wording templated on
 * a domain's {@link Nouns} (three Russian forms) + its {@link CandidateView} (the display label). This is
 * exactly what {@link PickConfirmActRunner} hardcoded in PR-1 — lifted into a Phrasing so a non-delete flow
 * (calendar cancel/move) can override the wording without touching the runner. The three delete flows get it
 * for free via {@link TargetedActionFlow#phrasing()}'s default.
 */
public final class NounPhrasing<T> implements Phrasing<T> {

    private final Nouns nouns;
    private final CandidateView<T> view;
    private final String labelField;

    public NounPhrasing(Nouns nouns, CandidateView<T> view, String labelField) {
        this.nouns = nouns;
        this.view = view;
        this.labelField = labelField;
    }

    @Override
    public String askWhich() {
        return "Какую " + nouns.accusative() + " удалить?";
    }

    @Override
    public String noHousehold() {
        return "Не знаю, к какому хозяйству относится запрос.";
    }

    @Override
    public String emptyPool() {
        return "Не нашёл " + nouns.genitivePlural() + ", которые можно удалить.";
    }

    @Override
    public String noMatch() {
        return "Не нашёл такую " + nouns.accusative() + ". Уточните, что удалить.";
    }

    @Override
    public String readFailed() {
        return "Не смог найти " + nouns.accusative() + " для удаления. Попробуйте ещё раз позже.";
    }

    @Override
    public String notReady() {
        return "Нечего удалять — повторите запрос, пожалуйста.";
    }

    @Override
    public String ambiguous(List<T> picks) {
        StringBuilder sb = new StringBuilder("Нашёл несколько подходящих " + nouns.genitivePlural() + " — какую удалить?");
        for (T p : picks) {
            sb.append("\n• ").append(view.label(p));
        }
        return sb.toString();
    }

    @Override
    public String confirm(T target, JsonNode pick) {
        return "Удалить " + nouns.accusative() + " " + view.label(target) + "? Ответьте «да», чтобы удалить.";
    }

    @Override
    public String declined(JsonNode pending) {
        return "Оставил " + label(pending) + " без изменений.";
    }

    @Override
    public String done(JsonNode pending) {
        return "Удалил " + nouns.accusative() + " " + label(pending) + ".";
    }

    @Override
    public String actFailed(JsonNode pending) {
        return "Не смог удалить " + label(pending) + " — возможно, " + nouns.nominative() + " уже удалена.";
    }

    private String label(JsonNode pending) {
        return pending.path(labelField).asString(nouns.accusative());
    }
}
