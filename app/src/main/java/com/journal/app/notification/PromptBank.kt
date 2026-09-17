package com.journal.app.notification

import kotlin.random.Random

/**
 * The Georgian prompt bank.
 *
 * Prompts are grouped by intent rather than by tone: the scheduler draws from [allPrompts] so a
 * day's slots mix categories, while the categories stay separate so a future "focus mode" can
 * narrow the pool without touching the engine.
 */
object PromptBank {

    /** ზრდა — long-horizon consequences, blind spots, small immediate moves. */
    val growthPrompts: List<String> = listOf(
        "დღეს რასაც ვაკეთებ, 100 დღე რომ გავაგრძელო, სად მიმიყვანს?",
        "რა არის ის, რასაც ახლა თვალს ვარიდებ და არ მინდა შევიმჩნიო?",
        "რისიც ახლა ასე მჯერა, რა შეიძლება აღმოჩნდეს მცდარი?",
        "რა არის 1 პატარა რამ, რისი გაკეთებაც დღესვე შემიძლია უკეთესობისკენ?"
    )

    /** კრეატივი — inversion, dropped ideas, scene-setting, noticing mechanisms. */
    val creativityPrompts: List<String> = listOf(
        "ახლა რაზეც ვფიქრობ, სრულიად საპირისპიროდ რომ მივუდგე, რას ვიზამდი?",
        "რა უცნაური იდეა მომივიდა თავში ცოტა ხნის წინ და ეგრევე დავიკიდე?",
        "ეს მომენტი ფილმის (ან ანიმეს) სცენა რომ იყოს, ფონად რა მუსიკა დაედებოდა?",
        "მიმოიხედე, შენ გარშემო 2 მეტრის რადიუსში ყველაზე რთული რა ნივთი ან მექანიზმია?"
    )

    /** ფოკუსი — is this worth it, what's being avoided, what already went well. */
    val focusPrompts: List<String> = listOf(
        "რასაც ახლა ვაკეთებ მართლა რამეს მაძლევს, თუ პროსტა დროს გამყავს?",
        "რა არის დღეს ყველაზე მნიშვნელოვანი, რასაც არ ვაკეთებ და უნდა ვაკეთებდე?",
        "გაიხსენე ერთი პატარა, კარგი რამ, რაც დღეს გამოგივიდა.",
        "ამ მომენტში რა იქნებოდა „საკმარისი“?"
    )

    /** უცნაური — light observation cues, for when the reflective ones feel too heavy. */
    val oddPrompts: List<String> = listOf(
        "შეამჩნიე 1 წვრილმანი ოთახში, რომელსაც აქამდე ყურადღებას არ აქცევდი.",
        "3 სიტყვით რომ დაასათაურო, როგორი დღე გქონდა დღეს?"
    )

    /** The pool every draw comes from. */
    val allPrompts: List<String> =
        growthPrompts + creativityPrompts + focusPrompts + oddPrompts

    /** All four categories, keyed for display. */
    val categories: Map<String, List<String>> = mapOf(
        "ზრდა" to growthPrompts,
        "კრეატივი" to creativityPrompts,
        "ფოკუსი" to focusPrompts,
        "უცნაური" to oddPrompts
    )

    fun random(random: Random = Random.Default): String = allPrompts.random(random)

    /**
     * Draws a prompt that is **not** [current] — the reroll and shuffle contract. Falls back to
     * [current] only if the bank somehow holds a single prompt.
     */
    fun randomOtherThan(current: String?, random: Random = Random.Default): String {
        val candidates = allPrompts.filter { it != current }
        return if (candidates.isEmpty()) current.orEmpty() else candidates.random(random)
    }
}
