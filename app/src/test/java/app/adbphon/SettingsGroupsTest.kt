package app.adbphon

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsGroupsTest {
    @Test fun everyItemInExactlyOneKnownGroup() {
        val keys = SetGroups.ITEMS.map { it.first }
        assertEquals("ключи пунктов без повторов", keys.size, keys.toSet().size)
        for ((k, g) in SetGroups.ITEMS) assertTrue("$k: группа $g", g in SetGroups.ORDER)
        // сумма пунктов по группам = все пункты (каждый пункт ровно в одной группе)
        assertEquals(keys.size, SetGroups.ORDER.sumOf { SetGroups.itemsOf(it).size })
        for (g in SetGroups.ORDER) assertTrue("группа $g не пустая", SetGroups.itemsOf(g).isNotEmpty())
    }

    @Test fun updatesAndBackupsFirst() {
        assertEquals(SetGroups.UPD, SetGroups.ORDER.first())
        // set2: первый пункт первой группы: четыре большие кнопки (обновления, копия, восстановление, о программе)
        assertEquals(SetGroups.HERO, SetGroups.itemsOf(SetGroups.UPD).first())
        for (k in listOf(SetGroups.HERO, "upd_auto", "versions", "backup", "copies")) assertEquals(SetGroups.UPD, SetGroups.groupOf(k))
        assertEquals(SetGroups.CLOUD, SetGroups.groupOf("cmod"))
        assertEquals(SetGroups.SYS, SetGroups.groupOf("log"))
    }

    private val titles = mapOf(
        SetGroups.HERO to listOf("Проверить обновления", "Проверять обновления раз в день"),
        "backup" to listOf("Резервная копия", "Папка для копий", "Создать копию", "Восстановить…"),
        "copies" to listOf("Копии и версии", "Сохранённые копии и версии..."),
        "theme" to listOf("Тема"),
        "log" to listOf("Вести журнал", "Показать журнал"),
    )

    @Test fun searchByPartOfName() {
        assertEquals(listOf(SetGroups.HERO, "copies"), SetGroups.search("обновл", titles))   // set2: синоним «верси» находит «Копии и версии»
        assertEquals(listOf("backup", "copies"), SetGroups.search("КОПИ", titles))
        assertEquals(listOf("copies"), SetGroups.search("сохраненные", titles))   // ё как е
        assertEquals(listOf("backup"), SetGroups.search("папка коп", titles))      // все слова запроса
        assertEquals(listOf("log"), SetGroups.search("  журнал  ", titles))
        assertTrue(SetGroups.search("т", titles).isEmpty())                           // одна буква: ничего
        assertTrue(SetGroups.search("нет такого", titles).isEmpty())
    }

    // set2: синонимы как в «Меню приложений»
    private val titles2 = mapOf(
        SetGroups.HERO to listOf("Проверить обновления", "Резервная копия", "Восстановить из копии", "О программе"),
        "backup" to listOf("Резервная копия", "Создать копию"),
        "theme" to listOf("Тема"),
        "lang" to listOf("Язык"),
        "font" to listOf("Размер шрифта"),
        "ui_size" to listOf("Размер интерфейса"),
        "log" to listOf("Вести журнал"),
    )

    @Test fun searchBySynonyms() {
        assertEquals(listOf(SetGroups.HERO, "backup"), SetGroups.search("бекап", titles2))
        assertEquals(listOf(SetGroups.HERO, "backup"), SetGroups.search("backup", titles2))
        assertEquals(listOf(SetGroups.HERO), SetGroups.search("обновить", titles2))
        assertEquals(listOf(SetGroups.HERO), SetGroups.search("update", titles2))
        assertEquals(listOf("lang"), SetGroups.search("language", titles2))
        assertEquals(listOf("lang"), SetGroups.search("мова", titles2))
        assertEquals(listOf("theme"), SetGroups.search("theme", titles2))
        assertEquals(listOf("font", "ui_size"), SetGroups.search("шрифт", titles2))   // «шрифт» и «размер» одна группа
        assertEquals(listOf("ui_size"), SetGroups.search("размер интерф", titles2))
        assertEquals(listOf("log"), SetGroups.search("log", titles2))
    }

    @Test fun bestTitleShowsMatchingName() {
        val hero = titles2.getValue(SetGroups.HERO)
        assertEquals("О программе", SetGroups.bestTitle("о программе", hero))
        assertEquals("Резервная копия", SetGroups.bestTitle("backup", hero))
        assertEquals("Проверить обновления", SetGroups.bestTitle("нет такого", hero))
    }

    @Test fun resultsInGroupOrder() {
        val r = SetGroups.search("а", titles + ("x" to listOf("а")))   // одна буква: пусто
        assertTrue(r.isEmpty())
        val all = SetGroups.search("ть", titles)   // «Проверить», «Создать», «Показать»
        assertEquals(all.sortedBy { k -> SetGroups.ITEMS.indexOfFirst { it.first == k } + 1000 * SetGroups.ORDER.indexOf(SetGroups.groupOf(k)) }, all)
        assertNotNull(SetGroups.groupOf(all.first()))
    }
}
