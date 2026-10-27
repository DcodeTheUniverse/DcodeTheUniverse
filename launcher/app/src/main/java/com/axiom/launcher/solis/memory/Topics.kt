package com.axiom.launcher.solis.memory

/**
 * A small built-in lexicon linking everyday words to topics ("allergic", "vegan" → diet), so a memory can be
 * found by what it is about and not only by its exact words: "what can't I eat?" finds "allergic to peanuts".
 * Runs on the phone with no model call; Solis adds its own tags (synonyms, categories) when it remembers.
 */
object Topics {
    private val RAW: Map<String, String> = mapOf(
        "family" to "family wife husband partner spouse girlfriend boyfriend son daughter kid kids child children mom mum mother " +
            "dad father brother sister parent parents grandma grandmother grandpa grandfather baby aunt uncle cousin niece nephew",
        "diet" to "diet allergy allergies allergic vegan vegetarian gluten lactose peanut nut dairy kosher halal intolerant intolerance " +
            "food eat eating meal meals cuisine restaurant breakfast lunch dinner spicy sugar coffee tea drink alcohol",
        "health" to "health doctor medication medicine pill prescription dentist therapy therapist asthma diabetes hospital symptom " +
            "sick illness injury migraine glasses vaccine",
        "fitness" to "fitness gym run running runner workout exercise yoga pilates cycling swim swimming marathon steps training " +
            "climbing hike hiking tennis football soccer",
        "work" to "work job boss manager colleague coworker office meeting client project deadline team standup career employer " +
            "company interview",
        "money" to "money bank budget salary rent mortgage invoice payment bill tax savings invest investment credit debit expense loan",
        "home" to "home house apartment flat address landlord neighbor neighbour garden kitchen moving",
        "travel" to "travel trip flight airport hotel vacation holiday passport visa booking airline luggage abroad",
        "transport" to "car drive driving commute bus train metro subway taxi uber parking bicycle bike scooter",
        "music" to "music song artist band album playlist spotify concert genre singer",
        "media" to "movie film series show netflix book novel author podcast game reading",
        "tech" to "code coding repo repository github bug app api kotlin python java javascript server deploy android laptop computer software",
        "pets" to "pet dog cat vet puppy kitten",
        "schedule" to "morning evening night wake alarm routine weekend weekday schedule calendar birthday anniversary appointment",
        "contact" to "email phone number whatsapp signal telegram",
        "learning" to "study course class exam school university college lesson language learn teacher homework",
        "shopping" to "buy shop shopping store order grocery groceries amazon delivery",
        "likes" to "love loves prefer prefers preference favorite favourite enjoy enjoys hate hates dislike dislikes",
    )

    val names: Set<String> get() = RAW.keys

    /** Stemmed word → its topics. */
    private val INDEX: Map<String, Set<String>> by lazy {
        val m = HashMap<String, MutableSet<String>>()
        RAW.forEach { (topic, words) -> MemoryIndex.tokenize(words).forEach { m.getOrPut(it) { HashSet() } += topic } }
        m
    }

    /** Topics [text] is about, most-mentioned first. */
    fun of(text: String, max: Int = 3): List<String> =
        MemoryIndex.tokenize(text).flatMap { INDEX[it].orEmpty() }.groupingBy { it }.eachCount()
            .entries.sortedByDescending { it.value }.take(max).map { it.key }

    /** Topic words to add to a search for [query] (those it doesn't already contain). */
    fun expand(query: String): List<String> {
        val toks = MemoryIndex.tokenize(query).toSet()
        return of(query, 4).filter { MemoryIndex.tokenize(it).none { t -> t in toks } }
    }
}
