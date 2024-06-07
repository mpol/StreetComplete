package de.westnordost.streetcomplete.data.osmnotes

import de.westnordost.streetcomplete.data.osm.mapdata.BoundingBox
import de.westnordost.streetcomplete.data.osm.mapdata.LatLon
import de.westnordost.streetcomplete.data.user.UserLoginSource
import de.westnordost.streetcomplete.testutils.OsmDevApi
import de.westnordost.streetcomplete.testutils.mock
import de.westnordost.streetcomplete.testutils.on
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

// other than some other APIs we are speaking to, we do not control the OSM API, so I think it is
// more effective to test with the official test API instead of mocking some imagined server
// response
class NotesApiClientTest {

    private val allowEverything = mock<UserLoginSource>()
    private val allowNothing = mock<UserLoginSource>()
    private val anonymous = mock<UserLoginSource>()

    init {
        on(allowEverything.accessToken).thenReturn(OsmDevApi.ALLOW_EVERYTHING_TOKEN)
        on(allowNothing.accessToken).thenReturn(OsmDevApi.ALLOW_NOTHING_TOKEN)
        on(anonymous.accessToken).thenReturn(null)
    }

    @Test fun `create note`(): Unit = runBlocking {
        val note = client(allowEverything).create(LatLon(83.0, 9.0), "Created note!")
        closeNote(note.id)

        assertEquals(LatLon(83.0, 9.0), note.position)
        assertEquals(Note.Status.OPEN, note.status)
        assertEquals(1, note.comments.size)

        val comment = note.comments.first()
        assertEquals("Created note!", comment.text)
        assertEquals(NoteComment.Action.OPENED, comment.action)
        assertEquals("westnordost", comment.user?.name)
    }

    @Test fun `comment note`(): Unit = runBlocking {
        var note = client(allowEverything).create(LatLon(83.0, 9.1), "Created note for comment!")
        note = client(allowEverything).comment(note.id, "First comment!")
        closeNote(note.id)

        assertEquals(2, note.comments.size)
        assertEquals("Created note for comment!", note.comments[0].text)
        assertEquals(NoteComment.Action.OPENED, note.comments[0].action)
        assertEquals("westnordost", note.comments[0].user?.name)

        assertEquals("First comment!", note.comments[1].text)
        assertEquals(NoteComment.Action.COMMENTED, note.comments[1].action)
        assertEquals("westnordost", note.comments[1].user?.name)
    }

    @Test fun `get note`(): Unit = runBlocking {
        val note = client(allowEverything).create(LatLon(83.0, 9.2), "Created note to get it!")
        val note2 = client(anonymous).get(note.id)
        closeNote(note.id)

        assertEquals(note, note2)
    }

    @Test fun `get no note`(): Unit = runBlocking {
        assertNull(client(anonymous).get(0))
    }

    @Test fun `get notes`(): Unit = runBlocking {
        val note1 = client(allowEverything).create(LatLon(83.0, 9.3), "Note a")
        val note2 = client(allowEverything).create(LatLon(83.1, 9.4), "Note b")

        val notes = client(anonymous).getAllOpen(BoundingBox(83.0, 9.3, 83.2, 9.5))

        closeNote(note1.id)
        closeNote(note2.id)

        assertTrue(notes.isNotEmpty())
    }

    private fun client(userLoginSource: UserLoginSource) =
        NotesApiClient(HttpClient(CIO), OsmDevApi.URL, userLoginSource, NotesApiParser())

    // for cleanup
    private fun closeNote(id: Long): Unit = runBlocking {
        HttpClient(CIO).post(OsmDevApi.URL + "notes/$id/close") {
            bearerAuth(OsmDevApi.ALLOW_EVERYTHING_TOKEN)
            parameter("text", "")
        }
    }
}
