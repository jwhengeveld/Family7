package nl.family7.core.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * De catalogus gaat tussen twee sessies via JSON naar schijf. Wat terugkomt
 * moet precies zijn wat erin ging, en een kapotte kaart mag de rest niet
 * meenemen.
 */
class CatalogJsonTest {

    private val item = ProgramItem(
        id = "/plus/programmas/de-kracht",
        slug = "de-kracht",
        title = "De kracht van blijdschap",
        thumbnailUrl = "https://www.family7.nl/a.jpg",
        badge = "Nieuw",
        url = "https://www.family7.nl/plus/programmas/de-kracht",
        description = "Met \"aanhalingstekens\" en é-tekens",
        nodeId = "4711"
    )

    @Test
    fun `rijen komen ongeschonden terug`() {
        val rows = listOf(
            CategoryRow(id = "nieuw", title = "Nieuw toegevoegd", moreUrl = "https://x/nieuw", items = listOf(item)),
            CategoryRow(id = "leeg", title = "Leeg")
        )

        assertEquals(rows, CatalogJson.decodeRows(CatalogJson.encodeRows(rows)))
    }

    @Test
    fun `losse programma's komen ongeschonden terug`() {
        assertEquals(listOf(item), CatalogJson.decodeItems(CatalogJson.encodeItems(listOf(item))))
    }

    @Test
    fun `een kaart zonder slug wordt overgeslagen`() {
        val json = """[{"title":"zonder slug"},{"slug":"wel","title":"Wel"}]"""

        val items = CatalogJson.decodeItems(json)

        assertEquals(1, items.size)
        assertEquals("wel", items.single().slug)
        assertTrue(items.single().id.isNotEmpty())
    }
}
