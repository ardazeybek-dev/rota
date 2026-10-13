package dev.rota

/** Ready-made rota files: used by `rota init`, the web playground and the tests. */
object Examples {
    data class Example(val id: String, val title: String, val source: String)

    val ONCALL = Example(
        "oncall", "Platform on-call (5 engineers, 4 weeks)",
        """
        # Primary/secondary on-call for a platform team.
        rota "Platform on-call — November"
        period 2026-11-02 .. 2026-11-29
        timezone Europe/Berlin
        skip 2026-11-26                       # company offsite, pager is covered by the vendor

        shift primary   weekdays 09:00-18:00
        shift night     weekdays 18:00-09:00  requires pager
        shift weekend   weekends 09:00-21:00  requires pager

        # history = shifts worked last month, so the fairness carries over
        person Ana      skills pager                       history 9
        person Bilal    skills pager  avoid fri            history 8
        person Chen     skills pager  off 2026-11-09..2026-11-13  history 6
        person Dara     share 0.5                          history 4   # part-time, no pager
        person Emre     skills pager  prefer weekend       history 10

        max-per-day 1
        max-per-week 3
        min-rest 8h
        """.trimIndent(),
    )

    val FLAT = Example(
        "flat", "Flat chores (4 flatmates, a month)",
        """
        # Who takes the bins out, cleans the kitchen and does the big Saturday clean?
        rota "Flat 3B chores"
        period 2026-11

        shift bins      mon,thu   19:00-19:30
        shift kitchen   daily     20:00-20:45
        shift bathroom  sat       11:00-12:30
        shift big-clean sun       10:00-13:00 needs 2

        person Lea      avoid sun
        person Marco    off 2026-11-14..2026-11-22   # visiting family
        person Noor     prefer kitchen
        person Sam      share 0.5                    # sublets the small room, half rent

        max-per-week 4
        weight spread 40
        """.trimIndent(),
    )

    val CAFE = Example(
        "cafe", "Café weekend staff (barista skills, 6 weeks)",
        """
        rota "Bean There — weekend staff"
        period 2026-11-07 for 6 weeks
        timezone Europe/Lisbon

        shift open   sat,sun  07:00-13:00 needs 2 requires barista
        shift close  sat,sun  13:00-19:00 needs 2
        shift prep   fri      16:00-20:00

        person Inês    skills barista            max 9
        person Tomás   skills barista  avoid open
        person Rui     skills barista  off 2026-11-21..2026-11-29
        person Beatriz
        person Joana                   prefer close
        person Kai     skills barista  share 0.5

        max-per-day 1
        max-per-week 2
        """.trimIndent(),
    )

    val IMPOSSIBLE = Example(
        "impossible", "Holiday week that cannot be staffed (see why)",
        """
        # Two people are away in the same week: rota proves which shifts cannot be filled.
        rota "Support desk — holiday week"
        period 2026-12-21 .. 2026-12-27

        shift early  weekdays 07:00-15:00 requires support
        shift late   weekdays 15:00-23:00 requires support
        shift sat    sat      10:00-16:00

        person Aiko   skills support  off 2026-12-24..2026-12-27
        person Bruno  skills support  off 2026-12-21..2026-12-23
        person Clara  skills support
        person Dev

        max-per-day 1
        max-per-week 4
        """.trimIndent(),
    )

    val all = listOf(ONCALL, FLAT, CAFE, IMPOSSIBLE)
}
