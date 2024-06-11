package de.westnordost.streetcomplete.data.osm.mapdata

import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import nl.adaptivity.xmlutil.serialization.XML
import nl.adaptivity.xmlutil.serialization.XmlChildrenName
import nl.adaptivity.xmlutil.serialization.XmlSerialName

// TODO tests

class MapDataApiSerializer {
    private val xml = XML { defaultPolicy { ignoreUnknownChildren() }}

    fun parseMapData(osmXml: String, ignoreRelationTypes: Set<String?>): NodesWaysRelations =
        xml.decodeFromString<ApiOsm>(osmXml).toMapData(ignoreRelationTypes)

    fun parseElementsDiffs(diffResultXml: String, ): List<DiffElement> =
        xml.decodeFromString<ApiDiffResult>(diffResultXml).toDiffElements()

    fun serializeMapDataChanges(changes: MapDataChanges, changesetId: Long): String =
        xml.encodeToString(changes.toApiOsmChange(changesetId))
}

//region Convert OSM API data structure to our data structure

private fun ApiOsm.toMapData(ignoreRelationTypes: Set<String?>) = NodesWaysRelations(
    nodes = nodes.map { it.toNode() },
    ways = ways.map { it.toWay() },
    relations = relations.mapNotNull {
        if (it.type !in ignoreRelationTypes) it.toRelation() else null
    },
)

private fun ApiNode.toNode() = Node(
    id = id,
    position = LatLon(lat, lon),
    tags = tags.toMap(),
    version = version,
    timestampEdited = timestamp.toEpochMilliseconds()
)

private fun ApiWay.toWay() = Way(
    id = id,
    nodeIds = nodes.map { it.ref },
    tags = tags.toMap(),
    version = version,
    timestampEdited = timestamp.toEpochMilliseconds()
)

private fun ApiRelation.toRelation() = Relation(
    id = id,
    members = members.map { it.toRelationMember() },
    tags = tags.toMap(),
    version = version,
    timestampEdited = timestamp.toEpochMilliseconds()
)

private val ApiRelation.type: String? get() = tags.find { it.k == "type" }?.v

private fun ApiRelationMember.toRelationMember() = RelationMember(
    type = ElementType.valueOf(type.uppercase()),
    ref = ref,
    role = role
)

private fun List<ApiTag>.toMap(): Map<String, String> = associate { (k, v) -> k to v }

private fun ApiDiffResult.toDiffElements(): List<DiffElement> =
    nodes.map { it.toDiffElement(ElementType.NODE) } +
    ways.map { it.toDiffElement(ElementType.WAY) } +
    relations.map { it.toDiffElement(ElementType.RELATION) }

private fun ApiDiffElement.toDiffElement(type: ElementType) = DiffElement(
    type = type,
    clientId = oldId,
    serverId = newId,
    serverVersion = newVersion
)

//endregion

//region Convert our data structure to OSM API data structure

private fun MapDataChanges.toApiOsmChange(changesetId: Long) = ApiOsmChange(
    create = creations.toApiOsm(changesetId),
    modify = modifications.toApiOsm(changesetId),
    delete = deletions.toApiOsm(changesetId)
)

private fun Collection<Element>.toApiOsm(changesetId: Long): ApiOsm? =
    if (isNotEmpty()) ApiOsm(
        nodes = filterIsInstance<Node>().map { it.toApiNode(changesetId) },
        ways = filterIsInstance<Way>().map { it.toApiWay(changesetId) },
        relations = filterIsInstance<Relation>().map { it.toApiRelation(changesetId) }
    ) else null

private fun Node.toApiNode(changesetId: Long) = ApiNode(
    id = id,
    changeset = changesetId,
    version = version,
    timestamp = Instant.fromEpochMilliseconds(timestampEdited),
    lat = position.latitude,
    lon = position.longitude,
    tags = tags.toApiTags()
)

private fun Way.toApiWay(changesetId: Long) = ApiWay(
    id = id,
    changeset = changesetId,
    version = version,
    timestamp = Instant.fromEpochMilliseconds(timestampEdited),
    tags = tags.toApiTags(),
    nodes = nodeIds.map { ApiWayNode(it) }
)

private fun Relation.toApiRelation(changesetId: Long) = ApiRelation(
    id = id,
    changeset = changesetId,
    version = version,
    timestamp = Instant.fromEpochMilliseconds(timestampEdited),
    members = members.map { it.toApiRelationMember() },
    tags = tags.toApiTags()
)

private fun RelationMember.toApiRelationMember() = ApiRelationMember(
    type = type.name.lowercase(),
    ref = ref,
    role = role
)

private fun Map<String, String>.toApiTags(): List<ApiTag> = map { (k, v) -> ApiTag(k, v) }

//endregion

//region OSM API data structure

@Serializable
@XmlSerialName("diffResult")
private data class ApiDiffResult(
    @XmlChildrenName("node") val nodes: List<ApiDiffElement>,
    @XmlChildrenName("way") val ways: List<ApiDiffElement>,
    @XmlChildrenName("relation") val relations: List<ApiDiffElement>,
)

@Serializable
private data class ApiDiffElement(
    @XmlSerialName("old_id") val oldId: Long,
    @XmlSerialName("new_id") val newId: Long? = null,
    @XmlSerialName("new_version") val newVersion: Int? = null,
)

@Serializable
@XmlSerialName("osmChange")
private data class ApiOsmChange(
    @XmlSerialName("create") val create: ApiOsm? = null,
    @XmlSerialName("modify") val modify: ApiOsm? = null,
    @XmlSerialName("delete") val delete: ApiOsm? = null,
)

@Serializable
@XmlSerialName("osm")
private data class ApiOsm(
    val bounds: ApiBoundingBox? = null,
    @XmlChildrenName("node") val nodes: List<ApiNode>,
    @XmlChildrenName("way") val ways: List<ApiWay>,
    @XmlChildrenName("relation") val relations: List<ApiRelation>,
)


@Serializable
@XmlSerialName("bounds")
private data class ApiBoundingBox(
    val minlat: Double,
    val monlon: Double,
    val maxlat: Double,
    val maxlon: Double
)

@Serializable
@XmlSerialName("node")
private data class ApiNode(
    val id: Long,
    val changeset: Long? = null,
    val version: Int,
    val timestamp: Instant,
    val lat: Double,
    val lon: Double,
    val tags: List<ApiTag> = emptyList(),
)

@Serializable
@XmlSerialName("way")
private data class ApiWay(
    val id: Long,
    val changeset: Long? = null,
    val version: Int,
    val timestamp: Instant,
    val tags: List<ApiTag> = emptyList(),
    val nodes: List<ApiWayNode> = emptyList(),
)

@Serializable
@XmlSerialName("nd")
private data class ApiWayNode(val ref: Long)

@Serializable
@XmlSerialName("relation")
private data class ApiRelation(
    val id: Long,
    val changeset: Long? = null,
    val version: Int,
    val timestamp: Instant,
    val members: List<ApiRelationMember> = emptyList(),
    val tags: List<ApiTag> = emptyList(),
)

@Serializable
@XmlSerialName("member")
private data class ApiRelationMember(
    val type: String,
    val ref: Long,
    val role: String,
)

@Serializable
@XmlSerialName("tag")
private data class ApiTag(val k: String, val v: String)

// endregion
