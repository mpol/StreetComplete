package de.westnordost.streetcomplete.data.osm.mapdata

import de.westnordost.streetcomplete.data.download.tiles.enclosingTilesRect
import de.westnordost.streetcomplete.data.download.tiles.minTileRect
import de.westnordost.streetcomplete.data.osm.geometry.ElementGeometry
import de.westnordost.streetcomplete.data.osm.geometry.ElementGeometryEntry
import de.westnordost.streetcomplete.data.osm.geometry.ElementPointGeometry
import de.westnordost.streetcomplete.util.SpatialCache

/**
 * Cache for MapDataController that uses SpatialCache for nodes (i.e. geometry) and hash maps
 * for ways, relations and their geometry.
 *
 * The [initialCapacity] is the initial capacity for nodes, the initial capacities for the other
 * element types are derived from that because the ratio is usually similar.
 *
 * Way and relation data outside the cached tiles may be cached, but will be removed on any trim
 */
class MapDataCache(
    private val tileZoom: Int,
    val maxTiles: Int,
    initialCapacity: Int,
    private val fetchMapData: (BoundingBox) -> Pair<Collection<Element>, Collection<ElementGeometryEntry>>, // used if the tile is not contained
) {
    private val spatialCache = SpatialCache(
        tileZoom,
        maxTiles,
        initialCapacity,
        { emptyList() }, // data is fetched using fetchMapData and put using spatialCache.replaceAllInBBox
        Node::id, Node::position
    )
    // initial values obtained from a spot check:
    //  approximately 80% of all elements were found to be nodes
    //  approximately every second node is part of a way
    //  more than 90% of elements are not part of a relation
    private val wayCache = HashMap<Long, Way?>(initialCapacity / 6)
    private val relationCache = HashMap<Long, Relation?>(initialCapacity / 10)
    private val wayGeometryCache = HashMap<Long, ElementGeometry?>(initialCapacity / 6)
    private val relationGeometryCache = HashMap<Long, ElementGeometry?>(initialCapacity / 10)
    private val wayIdsByNodeIdCache = HashMap<Long, MutableList<Long>>(initialCapacity / 2)
    private val relationIdsByElementKeyCache = HashMap<ElementKey, MutableList<Long>>(initialCapacity / 10)

    /**
     * Removes elements and geometries with keys in [deletedKeys] from cache and puts the
     * [updatedElements] and [updatedGeometries] into cache.
     */
    fun update(
        deletedKeys: Collection<ElementKey> = emptyList(),
        updatedElements: Iterable<Element> = emptyList(),
        updatedGeometries: Iterable<ElementGeometryEntry> = emptyList(),
        bbox: BoundingBox? = null
    ) = synchronized(this) {
        if (bbox == null) {
            spatialCache.update(
                updatedOrAdded = updatedElements.filterIsInstance<Node>(),
                deleted = deletedKeys.mapNotNull { if (it.type == ElementType.NODE) it.id else null }
            )
        } else {
            if (deletedKeys.isNotEmpty()) spatialCache.update(deleted = deletedKeys.mapNotNull { if (it.type == ElementType.NODE) it.id else null })
            spatialCache.replaceAllInBBox(updatedElements.filterIsInstance<Node>(), bbox)
        }

        deletedKeys.forEach { key ->
            when (key.type) {
                ElementType.NODE -> wayIdsByNodeIdCache.remove(key.id)
                ElementType.WAY -> {
                    wayCache.remove(key.id)?.nodeIds?.forEach { wayIdsByNodeIdCache[it]?.remove(key.id) }
                    wayGeometryCache.remove(key.id)
                }
                ElementType.RELATION -> {
                    relationCache.remove(key.id)?.members?.forEach { relationIdsByElementKeyCache[ElementKey(it.type, it.ref)]?.remove(key.id) }
                    relationGeometryCache.remove(key.id)
                }
            }
        }

        updatedGeometries.forEach {
            if (it.elementType == ElementType.WAY)
                wayGeometryCache[it.elementId] = it.geometry
            else if (it.elementType == ElementType.RELATION)
                relationGeometryCache[it.elementId] = it.geometry
        }

        val updatedWays = updatedElements.filterIsInstance<Way>()
        val updatedRelations = updatedElements.filterIsInstance<Relation>()

        updatedWays.forEach { way ->
            wayCache[way.id]?.let { oldWay ->
                // remove old way from wayIdsByNodeIdCache
                oldWay.nodeIds.forEach {
                    wayIdsByNodeIdCache[it]?.remove(way.id)
                }
            }
            wayCache[way.id] = way

            way.nodeIds.forEach {
                // add to wayIdsByNodeIdCache if node is in spatialCache
                if (spatialCache.get(it) != null) {
                    wayIdsByNodeIdCache.getOrPut(it) { ArrayList(2) }.add(way.id)
                } else {
                    // But if we already have an entry for that nodeId (cached from getWaysForNode),
                    // we definitely need to add the updated way
                    wayIdsByNodeIdCache[it]?.add(way.id)
                }
            }
        }

        if (updatedRelations.isEmpty())
            return // no need to create way and relation id lists

        // for adding relations to relationIdsByElementKeyCache we want the element to be
        // in spatialCache, or have a node / member in spatialCache (same reasoning as for ways)
        val (wayIds, relationIds) = getWayAndRelationIdsWithElementsInSpatialCache()

        updatedRelations.forEach { relation ->
            relationCache[relation.id]?.let { oldRelation ->
                // remove old relation from relationIdsByElementKeyCache
                oldRelation.members.forEach {
                    relationIdsByElementKeyCache[ElementKey(it.type, it.ref)]?.remove(relation.id)
                }
            }
            relationCache[relation.id] = relation

            relation.members.forEach {
                val memberKey = ElementKey(it.type, it.ref)
                // add to relationIdsByElementKeyCache if member is in spatialCache or has nodes or
                // members in spatialCache
                if ((it.type == ElementType.NODE && spatialCache.get(it.ref) != null)
                        || (it.ref in wayIds && it.type == ElementType.WAY)
                        || (it.ref in relationIds && it.type == ElementType.RELATION)) {
                    relationIdsByElementKeyCache.getOrPut(memberKey) { ArrayList(2) }.add(relation.id)
                } else {
                    // But if we already have an entry for that elementKey (cached from getRelationsForElement),
                    // we definitely need to add the updated relation
                    relationIdsByElementKeyCache[memberKey]?.add(relation.id)
                }
            }
        }
    }

    /**
     * Gets the element with the given [type] and [id] from cache. If the element is not cached,
     * [fetch] is called, and the result is cached and then returned.
     */
    fun getElement(
        type: ElementType,
        id: Long,
        fetch: (ElementType, Long) -> Element?
    ): Element? = synchronized(this) {
        val element = when (type) {
            ElementType.NODE -> spatialCache.get(id)
            ElementType.WAY -> wayCache.getOrPutIfNotNull(id) { fetch(type, id) as? Way }
            ElementType.RELATION -> relationCache.getOrPutIfNotNull(id) { fetch(type, id) as? Relation }
        }
        return element ?: fetch(type, id)
    }

    /**
     * Gets the geometry of the element with the given [type] and [id] from cache. If the geometry
     * is not cached, [fetch] is called, and the result is cached and then returned.
     */
    fun getGeometry(
        type: ElementType,
        id: Long,
        fetch: (ElementType, Long) -> ElementGeometry?
    ): ElementGeometry? = synchronized(this)  {
        val geometry = when (type) {
            ElementType.NODE -> spatialCache.get(id)?.let { ElementPointGeometry(it.position) }
            ElementType.WAY -> wayGeometryCache.getOrPutIfNotNull(id) { fetch(type, id) }
            ElementType.RELATION -> relationGeometryCache.getOrPutIfNotNull(id) { fetch(type, id) }
        }
        return geometry ?: fetch(type, id)
    }

    /**
     * Gets the elements with the given [keys] from cache. If any of the elements are not
     * cached, [fetch] is called for the missing elements. The fetched elements are cached and the
     * complete list is returned.
     * Note that the elements are returned in no particular order.
     */
    fun getElements(
        keys: Collection<ElementKey>,
        fetch: (Collection<ElementKey>) -> List<Element>
    ): List<Element> = synchronized(this) {
        val nodeIds = keys.mapNotNull { if (it.type == ElementType.NODE) it.id else null }

        val cachedNodes = spatialCache.getAll(nodeIds)
        val cachedWaysAndRelations = keys.mapNotNull { key ->
            when (key.type) {
                ElementType.WAY -> wayCache[key.id]
                ElementType.RELATION -> relationCache[key.id]
                else -> null
            }
        }
        val cachedElements = cachedNodes + cachedWaysAndRelations

        // exit early if everything is cached
        if (keys.size == cachedElements.size) return cachedElements

        // otherwise, fetch the rest & save to cache
        val cachedKeys = cachedElements.map { ElementKey(it.type, it.id) }.toSet()
        val keysToFetch = keys.filterNot { it in cachedKeys }
        val fetchedElements = fetch(keysToFetch)
        for (element in fetchedElements) {
            when (element.type) {
                ElementType.WAY -> wayCache[element.id] = element as Way
                ElementType.RELATION -> relationCache[element.id] = element as Relation
                else -> Unit
            }
        }
        return cachedElements + fetchedElements
    }

    /** Gets the nodes with the given [ids] from cache. If any of the nodes are not cached, [fetch]
     *  is called for the missing nodes. */
    fun getNodes(ids: Collection<Long>, fetch: (Collection<Long>) -> List<Node>): List<Node> = synchronized(this) {
        val cachedNodes = spatialCache.getAll(ids)
        if (ids.size == cachedNodes.size) return cachedNodes

        // not all in cache: must fetch the rest from db
        val cachedNodeIds = cachedNodes.map { it.id }.toSet()
        val missingNodeIds = ids.filterNot { it in cachedNodeIds }
        val fetchedNodes = fetch(missingNodeIds)
        return cachedNodes + fetchedNodes
    }

    /** Gets the ways with the given [ids] from cache. If any of the ways are not cached, [fetch]
     *  is called for the missing ways. The fetched ways are cached and the complete list is
     *  returned. */
    fun getWays(ids: Collection<Long>, fetch: (Collection<Long>) -> List<Way>): List<Way> {
        val wayKeys = ids.map { ElementKey(ElementType.WAY, it) }
        return getElements(wayKeys) { keys -> fetch(keys.map { it.id }) }.filterIsInstance<Way>()
    }

    /** Gets the relations with the given [ids] from cache. If any of the relations are not cached,
     *  [fetch] is called for the missing relations. The fetched relations are cached and the
     *  complete list is returned. */
    fun getRelations(ids: Collection<Long>, fetch: (Collection<Long>) -> List<Relation>): List<Relation> {
        val relationKeys = ids.map { ElementKey(ElementType.RELATION, it) }
        return getElements(relationKeys) { keys -> fetch(keys.map { it.id }) }.filterIsInstance<Relation>()
    }

    /**
     * Gets the geometries of the elements with the given [keys] from cache. If any of the
     * geometries are not cached, [fetch] is called for the missing geometries. The fetched
     * geometries are cached and the complete list is returned.
     * Note that the elements are returned in no particular order.
     */
    fun getGeometries(
        keys: Collection<ElementKey>,
        fetch: (Collection<ElementKey>) -> List<ElementGeometryEntry>
    ): List<ElementGeometryEntry> = synchronized(this) {
        // the implementation here is quite identical to the implementation in getElements, only
        // that geometries and not elements are returned and thus different caches are accessed

        val nodeIds = keys.mapNotNull { if (it.type == ElementType.NODE) it.id else null }

        val cachedNodeEntries = spatialCache.getAll(nodeIds).map { it.toElementGeometryEntry() }
        val cachedWayAndRelationEntries = keys.mapNotNull { key ->
            when (key.type) {
                ElementType.WAY -> wayGeometryCache[key.id]
                ElementType.RELATION -> relationGeometryCache[key.id]
                else -> null
            }?.let { ElementGeometryEntry(key.type, key.id, it) }
        }
        val cachedEntries = cachedNodeEntries + cachedWayAndRelationEntries

        // exit early if everything is cached
        if (keys.size == cachedEntries.size) return cachedEntries

        // otherwise, fetch the rest & save to cache
        val cachedKeys = cachedEntries.map { ElementKey(it.elementType, it.elementId) }.toSet()
        val keysToFetch = keys.filterNot { it in cachedKeys }
        val fetchedEntries = fetch(keysToFetch)
        for (entry in fetchedEntries) {
            when (entry.elementType) {
                ElementType.WAY -> wayGeometryCache[entry.elementId] = entry.geometry
                ElementType.RELATION -> relationGeometryCache[entry.elementId] = entry.geometry
                else -> Unit
            }
        }
        return cachedEntries + fetchedEntries
    }

    /**
     * Gets all ways for the node with the given [id] from cache. If the list of ways is not known,
     * or any way is missing in cache, [fetch] is called and the result cached.
     */
    fun getWaysForNode(id: Long, fetch: (Long) -> List<Way>): List<Way> = synchronized(this) {
        val wayIds = wayIdsByNodeIdCache.getOrPut(id) {
            val ways = fetch(id)
            for (way in ways) { wayCache[way.id] = way }
            ways.map { it.id }.toMutableList()
        }
        return wayIds.mapNotNull { wayCache[it] }
    }

    /**
     * Gets all relations for the node with the given [id] from cache. If the list of relations is
     * not known, or any relation is missing in cache, [fetch] is called and the result cached.
     */
    fun getRelationsForNode(id: Long, fetch: (Long) -> List<Relation>) =
        getRelationsForElement(ElementType.NODE, id) { fetch(id) }

    /**
     * Gets all relations for way with the given [id] from cache. If the list of relations is not
     * known, or any relation is missing in cache, [fetch] is called and the result cached.
     */
    fun getRelationsForWay(id: Long, fetch: (Long) -> List<Relation>) =
        getRelationsForElement(ElementType.WAY, id) { fetch(id) }

    /**
     * Gets all relations for way with the given [id] from cache. If the list of relations is not
     * known, or any relation is missing in cache, [fetch] is called and the result cached.
     */
    fun getRelationsForRelation(id: Long, fetch: (Long) -> List<Relation>) =
        getRelationsForElement(ElementType.RELATION, id) { fetch(id) }

    private fun getRelationsForElement(
        type: ElementType,
        id: Long,
        fetch: () -> List<Relation>
    ): List<Relation> = synchronized(this) {
        val relationIds = relationIdsByElementKeyCache.getOrPut(ElementKey(type, id)) {
            val relations = fetch()
            for (relation in relations) { relationCache[relation.id] = relation }
            relations.map { it.id }.toMutableList()
        }
        return relationIds.mapNotNull { relationCache[it] }
    }

    /**
     * Gets all elements and geometries inside [bbox]. This returns all nodes, all ways containing
     * at least one of the nodes, and all relations containing at least one of the ways or nodes,
     * and their geometries.
     * If data is not cached, tiles containing the [bbox] are fetched from database and cached.
     */
    fun getMapDataWithGeometry(bbox: BoundingBox): MutableMapDataWithGeometry = synchronized(this) {
        val requiredTiles = bbox.enclosingTilesRect(tileZoom).asTilePosSequence().toList()
        val cachedTiles = spatialCache.getTiles()
        val tilesToFetch = requiredTiles.filterNot { it in cachedTiles }
        val tilesRectToFetch = tilesToFetch.minTileRect()

        val result = MutableMapDataWithGeometry()
        result.boundingBox = bbox
        val nodes: Collection<Node>
        if (tilesRectToFetch != null) {
            // fetch needed data
            val fetchBBox = tilesRectToFetch.asBoundingBox(tileZoom)
            val (elements, geometries) = fetchMapData(fetchBBox)

            // get nodes from spatial cache
            // this may not contain all nodes, but tiles that were cached initially might
            // get dropped when the caches are updated
            // duplicate fetch might be unnecessary in many cases, but it's very fast anyway

            nodes = HashSet<Node>(spatialCache.get(bbox))
            update(updatedElements = elements, updatedGeometries = geometries, bbox = fetchBBox)

            // return data if we need exactly what was just fetched
            if (fetchBBox == bbox) {
                val nodeGeometryEntries = elements.filterIsInstance<Node>().map { it.toElementGeometryEntry() }
                result.putAll(elements, geometries + nodeGeometryEntries)
                return result
            }

            // get nodes again, this contains the newly added nodes, but maybe not the old ones if cache was trimmed
            nodes.addAll(spatialCache.get(bbox))
        } else {
            nodes = spatialCache.get(bbox)
        }

        val wayIds = HashSet<Long>(nodes.size / 5)
        val relationIds = HashSet<Long>(nodes.size / 10)
        nodes.forEach { node ->
            wayIdsByNodeIdCache[node.id]?.let { wayIds.addAll(it) }
            relationIdsByElementKeyCache[ElementKey(ElementType.NODE, node.id)]?.let { relationIds.addAll(it) }
            result.put(node, ElementPointGeometry(node.position))
        }
        wayIds.forEach { wayId ->
            result.put(wayCache[wayId]!!, wayGeometryCache[wayId])
            relationIdsByElementKeyCache[ElementKey(ElementType.WAY, wayId)]?.let { relationIds.addAll(it) }
        }
        relationIds.forEach { relationId ->
            result.put(relationCache[relationId]!!, relationGeometryCache[relationId])
            // don't add relations of relations, because elementDao.getAll(bbox) also isn't doing that
        }

        // trim if we fetched new data, and spatialCache is full
        // trim to 90%, so trim is (probably) not immediately called on next fetch
        if (spatialCache.size >= maxTiles && tilesToFetch.isNotEmpty())
            trim((maxTiles * 9) / 10)
        return result
    }

    /** Clears the cache */
    fun clear() = synchronized(this) {
        spatialCache.clear()
        wayCache.clear()
        relationCache.clear()
        wayGeometryCache.clear()
        relationGeometryCache.clear()
        wayIdsByNodeIdCache.clear()
        relationIdsByElementKeyCache.clear()
    }

    /** Reduces cache size to the given number of non-empty [tiles], and removes all data
     *  not contained in the remaining tiles.
     */
    fun trim(tiles: Int) = synchronized(this) {
        spatialCache.trim(tiles)
        trimNonSpatialCaches()
    }

    private fun trimNonSpatialCaches() {
        synchronized(this) {
            // ways and relations with at least one element in cache should not be removed
            val (wayIds, relationIds) = getWayAndRelationIdsWithElementsInSpatialCache()

            wayCache.keys.retainAll { it in wayIds }
            relationCache.keys.retainAll { it in relationIds }
            wayGeometryCache.keys.retainAll { it in wayIds }
            relationGeometryCache.keys.retainAll { it in relationIds }

            // now clean up wayIdsByNodeIdCache and relationIdsByElementKeyCache
            wayIdsByNodeIdCache.keys.retainAll { spatialCache.get(it) != null }
            relationIdsByElementKeyCache.keys.retainAll {
                (it.type == ElementType.NODE && spatialCache.get(it.id) != null)
                    || (it.type == ElementType.WAY && it.id in wayIds)
                    || (it.type == ElementType.RELATION && it.id in relationIds)
            }
        }
    }

    private fun getWayAndRelationIdsWithElementsInSpatialCache(): Pair<Set<Long>, Set<Long>> = synchronized(this) {
        val wayIds = wayCache.values.mapNotNull { way ->
            if (way != null && way.nodeIds.any { spatialCache.get(it) != null })
                way.id
            else null
        }.toHashSet()

        fun RelationMember.isCached(): Boolean =
            type == ElementType.NODE && spatialCache.get(ref) != null
                || type == ElementType.WAY && ref in wayIds

        val relationIds = relationCache.values.mapNotNull { relation ->
            if (relation != null && relation.members.any { member ->
                    member.isCached()
                        || (member.type == ElementType.RELATION // relation of relations
                        && (relationCache[member.ref])?.members?.any { it.isCached() } == true)
                })
                relation.id
            else null
        }.toHashSet()
        wayIds to relationIds
    }

    private fun <K,V> HashMap<K, V>.getOrPutIfNotNull(key: K, valueOrNull: () -> V?): V? {
        val v = get(key)
        if (v == null)
            valueOrNull()?.let {
                put(key, it)
                return it
            }
        return v
    }

    private fun Node.toElementGeometryEntry() =
        ElementGeometryEntry(type, id, ElementPointGeometry(position))
}
