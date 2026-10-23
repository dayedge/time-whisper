package org.github.dayedge.timewhisper.text

import org.github.dayedge.timewhisper.CandidateKind
import org.github.dayedge.timewhisper.DateOrder
import org.github.dayedge.timewhisper.Frequency
import org.github.dayedge.timewhisper.Granularity

/**
 * Polish, a first draft. The sections are in the same order as in [English]. Polish inflects, so a row lists every form
 * people type for one meaning: the cases a sentence puts a word in ("piątek", "w piątku"), and its abbreviations.
 * Every form is also found typed without its accents ("sroda"), through the conventions.
 */
internal object Polish : Lexicon() {
    override val language = "pl"
    override val spellingLanguage = "pl"

    override val conventions = Conventions(
        dateOrder = DateOrder.DAY_MONTH,
        // "05.10" is the fifth of October; "5/10" is read too.
        dateSeparators = "./",
        minuteSeparators = ":.",
        // The 24-hour clock is the normal one, so "7:30" is half past seven in the morning. "5pm" is used now and then as well.
        meridiemSuffixes = mapOf("am" to Meridiem.AM, "pm" to Meridiem.PM),
        minutesAreExact = true,
        // A lone hour is still read by habit: "o 5" is five in the afternoon.
        afternoonHours = 1..7,
        decimalSeparators = ".,",
        ordinalMarks = "-?(?:go|ego|tego)",
        halfCountsBack = true,
        unaccented = "ąa ćc ęe łl ńn óo śs źz żz",
    )

    /**
     * The hours 1 to 20, each as the feminine ordinal it is said with, in the cases a sentence puts it in: "piąta",
     * "o piątej", "na piątą". The first five are also the feminine ordinals of a weekday ("w pierwszą środę").
     */
    private val hours = listOf(
        "pierwsza pierwszej pierwszą", "druga drugiej drugą", "trzecia trzeciej trzecią", "czwarta czwartej czwartą",
        "piąta piątej piątą", "szósta szóstej szóstą", "siódma siódmej siódmą", "ósma ósmej ósmą", "dziewiąta dziewiątej dziewiątą",
        "dziesiąta dziesiątej dziesiątą", "jedenasta jedenastej jedenastą", "dwunasta dwunastej dwunastą",
        "trzynasta trzynastej trzynastą", "czternasta czternastej czternastą", "piętnasta piętnastej piętnastą",
        "szesnasta szesnastej szesnastą", "siedemnasta siedemnastej siedemnastą", "osiemnasta osiemnastej osiemnastą",
        "dziewiętnasta dziewiętnastej dziewiętnastą", "dwudziesta dwudziestej dwudziestą",
    )

    override fun LexiconBuilder.define() {
        // Calendar names. A weekday in the plural ("we wtorki") is the same day; a repeat reads it as every Tuesday.
        // Months in the nominative, genitive ("10 maja") and locative ("w maju"). The instrumental ("z Majem") is left out:
        // it is a surname far more often than a date. "sie" is left out: it is how "się" is typed without accents.
        weekdays(
            "poniedziałek poniedziałku poniedziałki pon pn", "wtorek wtorku wtorki wt", "środa środę środy śr",
            "czwartek czwartku czwartki czw", "piątek piątku piątki pt", "sobota sobotę soboty sob",
            "niedziela niedzielę niedzieli niedziele nd niedz",
        )
        months(
            "styczeń stycznia styczniu sty", "luty lutego lutym lut", "marzec marca marcu mar", "kwiecień kwietnia kwietniu kwi",
            "maj maja maju", "czerwiec czerwca czerwcu cze", "lipiec lipca lipcu lip", "sierpień sierpnia sierpniu sierp",
            "wrzesień września wrześniu wrz", "październik października październiku paź", "listopad listopada listopadzie lis list",
            "grudzień grudnia grudniu gru",
        )

        // Numbers. Counts; hours, said as feminine ordinals; and the days of a date, said in the genitive ("dziesiątego
        // maja"), now and then in the nominative ("dziesiąty maja"). Two words that make one ("dwudziestego piątego") are
        // joined by the lexer, as "forty five" is.
        counted(1, "jeden jedna jedną jedno jednego jednej", "dwa dwie", "trzy", "cztery", "pięć", "sześć", "siedem", "osiem", "dziewięć", "dziesięć") {
            copy(number = it)
        }
        counted(11, "jedenaście", "dwanaście", "trzynaście", "czternaście", "piętnaście", "szesnaście", "siedemnaście", "osiemnaście", "dziewiętnaście") {
            copy(number = it)
        }
        set(20 to "dwadzieścia", 30 to "trzydzieści", 40 to "czterdzieści", 50 to "pięćdziesiąt", 60 to "sześćdziesiąt", 90 to "dziewięćdziesiąt") {
            copy(number = it)
        }
        counted(1, *hours.toTypedArray()) { copy(hour = it) }
        counted(1, *hours.take(5).toTypedArray()) { copy(ordinal = it) }
        counted(
            1, "pierwszego pierwszy", "drugiego drugi", "trzeciego trzeci", "czwartego czwarty", "piątego piąty", "szóstego szósty",
            "siódmego siódmy", "ósmego ósmy", "dziewiątego dziewiąty", "dziesiątego dziesiąty", "jedenastego jedenasty",
            "dwunastego dwunasty", "trzynastego trzynasty", "czternastego czternasty", "piętnastego piętnasty",
            "szesnastego szesnasty", "siedemnastego siedemnasty", "osiemnastego osiemnasty", "dziewiętnastego dziewiętnasty",
            "dwudziestego dwudziesty",
        ) { copy(ordinal = it) }
        set(30 to "trzydziestego trzydziesty", -1 to "ostatni ostatniego ostatnia ostatnią ostatniej") { copy(ordinal = it) }

        // Units of the calendar, and lengths of time under a day in minutes. A day and a week are also lengths, of 1440
        // and 10080 minutes ("dzień wcześniej"); the readers take those from the unit.
        unit(Granularity.DAY, "dzień dnia dniu d", "dni")
        unit(Granularity.WEEK, "tydzień tygodnia tygodniu", "tygodnie tygodni")
        unit(Granularity.MONTH, "miesiąc miesiąca miesiącu", "miesiące miesięcy")
        unit(Granularity.YEAR, "rok roku", "lata lat")
        set(1 to "m min minut minuty minuta minutę", 15 to "kwadrans", 60 to "h godz godzin godziny godzina godzinę godzinkę") {
            copy(minutes = it)
        }

        // Where a date lies from today, or within a period. The direction agrees with its noun, so every form is listed.
        set(
            0 to "ten ta tę tym tej tego",
            1 to "przyszły przyszła przyszłą przyszłe przyszłego przyszłym przyszłej następny następna następną następne następnego następnym następnej",
            -1 to "zeszły zeszła zeszłą zeszłe zeszłego zeszłym zeszłej ubiegły ubiegłą ubiegłego ubiegłym poprzedni poprzednią poprzedniego poprzednim " +
                "ostatni ostatnią ostatniego ostatnim",
        ) { copy(direction = it) }
        set(0 to "dziś dzisiaj", 1 to "jutro jutra", 2 to "pojutrze pojutrza", -1 to "wczoraj", -2 to "przedwczoraj") { copy(relativeDay = it) }
        set(Edge.START to "początek początku początkiem", Edge.END to "koniec końca końcu końcem") { copy(edge = it) }

        // The clock in words. After a day these only qualify it ("jutro rano"); after an hour they say which half of the
        // day ("o siódmej rano"). "po południu" is two words for one meaning; a wording below makes it the single word "popołudniu".
        role(Role.PART_OF_DAY, "rano wieczorem popołudniu")
        set(Meridiem.AM to "am rano", Meridiem.PM to "pm wieczorem popołudniu") { copy(meridiem = it) }
        set(12 to "południe południa", 0 to "północ północy") { copy(namedClock = it) }
        set(30 to "wpół pół", 15 to "kwadrans") { copy(fraction = it) }

        // Repeats. "co drugi piątek" needs no role of its own: an ordinal two after "co" is every other one.
        set(
            Frequency.DAILY to "codziennie", Frequency.WEEKLY to "cotygodniowo tygodniowo",
            Frequency.MONTHLY to "comiesięcznie miesięcznie", Frequency.YEARLY to "corocznie rocznie",
        ) { copy(recurrence = it) }
        role(Role.EVERY, "co każdy każda każdą każde każdego każdej")
        role(Role.WEEKDAYS, "robocze roboczy roboczego roboczym powszednie powszedni powszedniego powszednim")
        role(Role.WEEKEND, "weekend weekendu weekendzie")

        // Small words that give a part its shape. "do" ends a range ("od 14 do 16") and counts back from an hour ("wpół do piątej").
        role(Role.IN, "za")
        role(Role.AGO, "temu")
        role(Role.FROM_NOW, "dziś dzisiaj teraz")
        role(Role.PAST, "po")
        role(Role.TO, "do")
        role(Role.RANGE, "- – — -> ~ do")
        role(Role.BEFORE, "przed wcześniej")
        role(Role.AND, "i a oraz &")
        role(Role.PLUS, "plus +")
        role(Role.MINUS, "minus bez -")
        role(Role.ALL, "cały cała całą całe")

        // Words inside a date or a time that carry no value; readers read past them. A word that only attaches a part to the
        // sentence ("od listopada", "przez 45 minut") is not listed: the tagger labels it a wrapper.
        // Joiners hold one date or time together: "pierwszy poniedziałek w maju", "piątek o 17", "o godzinie 17".
        role(Role.JOINER, "w we o na przed godzina godzinie godz")
        // INTERIM: the tagger has no Polish training sentences yet and still puts these attaching words inside the part, so
        // they are read past too. Remove them once Polish sentences teach the tagger to label them wrappers.
        role(Role.JOINER, "od przez aż między pomiędzy @ potrwa trwa zajmie zaczynając począwszy")
        // Hedges qualify a time without changing it: "około 17", "najpóźniej o 16". "najbliższy czwartek" is "czwartek",
        // the next one to come.
        role(Role.HEDGE, "około koło jakieś jakiś najpóźniej najbliższy najbliższa najbliższą najbliższe najbliższego najbliższym najbliższej")

        // Marks that force a kind, beside the shared "todo", "t:task", "type:event": "zadanie", "do zrobienia", "typ:wydarzenie".
        set(CandidateKind.TASK to "zadanie zrobienia", CandidateKind.EVENT to "wydarzenie") { copy(candidateKind = it) }
    }

    // Set phrases, each for words the readers know.
    override val wordings: List<Wording> = listOf(
        // One qualifier of an hour, or of a day.
        Wording("po południu", "popołudniu"),
        // New Year's Eve is a date with a name.
        Wording("sylwestra", "grudnia 31"), Wording("sylwester", "grudnia 31"),
        // One and a half of what follows: "półtora tygodnia", "półtorej godziny".
        Wording("półtora", "1.5"), Wording("półtorej", "1.5"),
        // "Within" a time from now, as "za" is: "w ciągu 2 tygodni".
        Wording("w ciągu", "za"),
    ) +
        // "o drugiej w nocy" is two in the morning. Only the small hours: "11 w nocy" is late evening.
        (hours.take(4).flatMap { it.split(' ') } + listOf("1", "2", "3", "4")).map { Wording("$it w nocy", "$it rano") }
}
