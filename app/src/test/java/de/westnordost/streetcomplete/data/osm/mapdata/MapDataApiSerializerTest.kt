package de.westnordost.streetcomplete.data.osm.mapdata

import kotlinx.datetime.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class MapDataApiSerializerTest {

    private fun nodesOsm(c: Long): String = """
        <node id="122" version="2" changeset="$c" timestamp="2019-03-15T01:52:26Z" lat="53.0098761" lon="9.0065254" />
        <node id="123" version="1" changeset="$c" timestamp="2019-03-15T01:52:25Z" lat="53.009876" lon="9.0065253">
        <tag k="emergency" v="fire_hydrant" />
        <tag k="fire_hydrant:type" v="pillar" />
        </node>
    """

    private fun waysOsm(c: Long): String = """
        <way id="336145990" version="20" changeset="$c" timestamp="2018-10-17T06:39:01Z" />
        <way id="47076097" version="2" changeset="$c" timestamp="2012-08-12T22:14:39Z">
        <nd ref="600397018" />
        <nd ref="600397019" />
        <nd ref="600397020" />
        <tag k="landuse" v="farmland" />
        <tag k="name" v="Hippiefarm" />
        </way>
    """

    private fun relationsOsm(c: Long): String = """
        <relation id="55555" version="3" changeset="$c" timestamp="2021-05-08T14:14:51Z" />
        <relation id="8379313" version="21" changeset="$c" timestamp="2023-05-08T14:14:51Z">
        <member type="node" ref="123" role="something" />
        <member type="way" ref="234" role="" />
        <member type="relation" ref="345" role="connection" />
        <tag k="network" v="rcn" />
        <tag k="route" v="bicycle" />
        </relation>
    """

    private val nodes = listOf(
        Node(
            id = 122,
            position = LatLon(53.0098761, 9.0065254),
            tags = emptyMap(),
            version = 2,
            timestampEdited = Instant.parse("2019-03-15T01:52:26Z").toEpochMilliseconds()
        ),
        Node(
            id = 123,
            position = LatLon(53.0098760, 9.0065253),
            tags = mapOf("emergency" to "fire_hydrant", "fire_hydrant:type" to "pillar"),
            version = 1,
            timestampEdited = Instant.parse("2019-03-15T01:52:25Z").toEpochMilliseconds()
        ),
    )

    private val ways = listOf(
        Way(
            id = 336145990,
            nodeIds = emptyList(),
            tags = emptyMap(),
            version = 20,
            timestampEdited = Instant.parse("2018-10-17T06:39:01Z").toEpochMilliseconds()
        ),
        Way(
            id = 47076097,
            nodeIds = listOf(600397018, 600397019, 600397020),
            tags = mapOf("landuse" to "farmland", "name" to "Hippiefarm"),
            version = 2,
            timestampEdited = Instant.parse("2012-08-12T22:14:39Z").toEpochMilliseconds()
        ),
    )

    private val relations = listOf(
        Relation(
            id = 55555,
            members = emptyList(),
            tags = emptyMap(),
            version = 3,
            timestampEdited = Instant.parse("2021-05-08T14:14:51Z").toEpochMilliseconds()
        ),
        Relation(
            id = 8379313,
            members = listOf(
                RelationMember(ElementType.NODE, 123, "something"),
                RelationMember(ElementType.WAY, 234, ""),
                RelationMember(ElementType.RELATION, 345, "connection"),
            ),
            tags = mapOf("network" to "rcn", "route" to "bicycle"),
            version = 21,
            timestampEdited = Instant.parse("2023-05-08T14:14:51Z").toEpochMilliseconds()
        )
    )

    @Test fun `parseMapData minimum`() {
        val empty = MapDataApiSerializer().parseMapData("<osm></osm>", emptySet())
        assertEquals(0, empty.size)
        assertNull(empty.boundingBox)
    }

    @Test fun `parseMapData full`() {
        val osm = """<?xml version="1.0" encoding="UTF-8"?>
            <osm version="0.6" generator="CGImap 0.9.2 (2448320 spike-08.openstreetmap.org)" copyright="OpenStreetMap and contributors" attribution="http://www.openstreetmap.org/copyright" license="http://opendatacommons.org/licenses/odbl/1-0/">
            <bounds minlat="53.0000000" minlon="9.0000000" maxlat="53.0100000" maxlon="9.0100000"/>
            ${nodesOsm(123)}
            ${waysOsm(345)}
            ${relationsOsm(567)}
            </osm>
        """

        val data = MapDataApiSerializer().parseMapData(osm, emptySet())
        assertEquals(nodes.toSet(), data.nodes.toSet())
        assertEquals(ways.toSet(), data.ways.toSet())
        assertEquals(relations.toSet(), data.relations.toSet())
        assertEquals(BoundingBox(53.0, 9.0, 53.01, 9.01), data.boundingBox)
    }

    @Test fun `parseMapData with ignored relation types`() {
        val osm = """
            <osm>
            <relation id="1" version="1" timestamp="2023-05-08T14:14:51Z">
              <tag k="type" v="route"/>
            </relation>
            </osm>
        """

        val empty = MapDataApiSerializer().parseMapData(osm, setOf("route"))
        assertEquals(0, empty.size)
    }

    @Test fun `serializeMapDataChanges minimum`() {
        assertEquals(
            "<osmChange />",
            MapDataApiSerializer().serializeMapDataChanges(MapDataChanges(), 123L)
        )
    }

    @Test fun `serializeMapDataChanges full`() {
        val osmChange = """
            <osmChange>
            <create>
            ${nodesOsm(1234)}
            ${waysOsm(1234)}
            </create>
            <modify>
            ${waysOsm(1234)}
            ${relationsOsm(1234)}
            </modify>
            <delete>
            ${nodesOsm(1234)}
            ${relationsOsm(1234)}
            </delete>
            </osmChange>
        """

        val mapDataChanges = MapDataChanges(
            creations = nodes + ways,
            modifications = ways + relations,
            deletions = nodes + relations,
        )

        assertEquals(
            osmChange.replace(Regex("[\n\r] *"), ""),
            MapDataApiSerializer().serializeMapDataChanges(mapDataChanges, 1234L)
        )
    }

    @Test fun `parseElementUpdates minimum`() {
        assertEquals(
            mapOf(),
            MapDataApiSerializer().parseElementUpdates("<diffResult></diffResult>")
        )
    }

    @Test fun `parseElementUpdates full`() {
        val diffResult = """
            <diffResult generator="OpenStreetMap Server" version="0.6">
            <node old_id="1"/>
            <way old_id="2"/>
            <relation old_id="3"/>
            <node old_id="-1" new_id="9" new_version="99" />
            <way old_id="-2" new_id="8" new_version="88" />
            <relation old_id="-3" new_id="7" new_version="77" />
            </diffResult>
        """

        val elementUpdates = mapOf(
            ElementKey(ElementType.NODE, 1) to DeleteElement,
            ElementKey(ElementType.WAY, 2) to DeleteElement,
            ElementKey(ElementType.RELATION, 3) to DeleteElement,
            ElementKey(ElementType.NODE, -1) to UpdateElement(9, 99),
            ElementKey(ElementType.WAY, -2) to UpdateElement(8, 88),
            ElementKey(ElementType.RELATION, -3) to UpdateElement(7, 77),
        )

        assertEquals(
            elementUpdates,
            MapDataApiSerializer().parseElementUpdates(diffResult)
        )
    }
}
