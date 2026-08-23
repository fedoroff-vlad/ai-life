package dev.fedorov.ailife.agentruntime.intent;

/**
 * The three Russian noun forms {@link PickConfirmActRunner} interpolates into its shared confirm-act
 * wording, so a domain names its target once ("задача" / "трата" / "заметка") instead of hand-writing the
 * same six sentences. All three forms are feminine in the current consumers, so the fixed verb agreement
 * in the templates ("уже удалена") holds; a future non-feminine target would need a wording hook, not a
 * fourth form.
 *
 * @param accusative     e.g. "задачу" — "Удалить <b>задачу</b> «…»?", "Какую <b>задачу</b> удалить?"
 * @param genitivePlural e.g. "задач"  — "Не нашёл <b>задач</b>, которые можно удалить."
 * @param nominative     e.g. "задача" — "…возможно, <b>задача</b> уже удалена."
 */
public record Nouns(String accusative, String genitivePlural, String nominative) {
}
