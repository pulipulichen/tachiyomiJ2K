package eu.kanade.tachiyomi.data.sync

import android.content.Context
import android.util.AtomicFile
import android.util.Base64
import eu.kanade.tachiyomi.data.database.DatabaseHelper
import eu.kanade.tachiyomi.data.database.models.Category
import eu.kanade.tachiyomi.data.database.models.ChapterImpl
import eu.kanade.tachiyomi.data.database.models.Manga
import eu.kanade.tachiyomi.data.database.models.MangaCategory
import eu.kanade.tachiyomi.data.library.CustomMangaManager
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import timber.log.Timber
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.File
import java.util.UUID

/**
 * Per-device, last-change register synchronization.
 *
 * Each device owns one Drive appData file; other devices only read it. There is
 * no shared mutable manifest and no race to overwrite another device's data.
 * Local changes are journaled before upload. Imports use stable source+URL keys,
 * never device-local SQLite primary keys. Remote writes do not become new local
 * events (no echo loop).
 *
 * Concurrent edits of the same field/entity resolve deterministically by
 * Lamport counter + device ID. Different manga/chapters merge independently.
 * Deletion from the library is represented by a favorite=false tombstone.
 */
class CloudSyncEngine(
    private val context: Context,
    private val drive: GoogleDriveSyncClient,
) {
    private val db: DatabaseHelper = Injekt.get()
    private val customManga: CustomMangaManager = Injekt.get()
    private val config = CloudSyncConfig(context)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val journalFile = AtomicFile(File(context.filesDir, "cloud-sync-journal-v1.json"))

    @Serializable
    data class MangaData(
        val source: Long,
        val url: String,
        val title: String,
        val author: String? = null,
        val artist: String? = null,
        val description: String? = null,
        val genre: String? = null,
        val thumbnail: String? = null,
        val status: Int = 0,
        val favorite: Boolean = true,
        val customTitle: String? = null,
        val customAuthor: String? = null,
        val customArtist: String? = null,
        val customDescription: String? = null,
        val customGenre: List<String>? = null,
        val customStatus: Int? = null,
        val categories: List<String> = emptyList(),
    )

    @Serializable
    data class ChapterData(
        val source: Long,
        val mangaUrl: String,
        val url: String,
        val name: String,
        val chapterNumber: Float = -1f,
        val read: Boolean = false,
        val bookmark: Boolean = false,
        val lastPage: Int = 0,
        val pagesLeft: Int = 0,
    )

    @Serializable
    data class Event(
        val key: String,
        val deviceId: String,
        val clock: Long,
        val manga: MangaData? = null,
        val chapter: ChapterData? = null,
    )

    @Serializable
    data class Envelope(
        val schemaVersion: Int = 1,
        val deviceId: String,
        val events: List<Event> = emptyList(),
    )

    @Serializable
    data class Journal(
        val schemaVersion: Int = 1,
        val accountId: String,
        val deviceId: String = UUID.randomUUID().toString(),
        var clock: Long = 0L,
        val baseline: MutableMap<String, String> = mutableMapOf(),
        val events: MutableMap<String, Event> = mutableMapOf(),
    )

    /**
     * One invocation may fetch multiple Drive files, but can only modify the
     * current device's file. WorkManager is serialized with a process-wide lock.
     */
    fun sync() {
        val accountId = config.accountId ?: error("Connect Google Drive before syncing")
        val journal = load(accountId)
        val filename = "tachiyomi-j2k-sync-v1-${journal.deviceId}.json"
        val remoteFiles = drive.listFiles()
        val allEvents = mutableListOf<Event>()
        val ownFiles = remoteFiles.filter { it.name == filename }
        for (file in remoteFiles) {
            val contents = drive.readFile(file.id)
            require(contents.length < 40_000_000) { "Drive sync file too large: ${file.name}" }
            val envelope = json.decodeFromString<Envelope>(contents)
            require(envelope.schemaVersion == 1) { "Unsupported cloud sync version" }
            require(file.name == "tachiyomi-j2k-sync-v1-${envelope.deviceId}.json") {
                "Sync file name and device ID mismatch"
            }
            allEvents.addAll(envelope.events)
        }

        // Recover own history if the upload succeeded but local save did not.
        val ownEvents = allEvents.filter { it.deviceId == journal.deviceId }
        for (event in ownEvents) {
            val previous = journal.events[event.key]
            if (previous == null || isNewer(event, previous)) journal.events[event.key] = event
        }
        // An offline local edit must not get a newer clock merely because a
        // remote edit arrived while this worker was downloading Drive files.
        journal.clock = maxOf(journal.clock, ownEvents.maxOfOrNull { it.clock } ?: 0L)

        // Capture edits BEFORE merging from remote, so offline edits are not lost.
        val snapshot = snapshot().toMutableMap()
        // A reset-to-unread chapter is absent from the sparse snapshot. Emit
        // an explicit zero-progress event if the chapter still exists locally.
        for ((key, prior) in journal.baseline) {
            if (!key.contains(":c:") || key in snapshot) continue
            val previous = json.decodeFromString<Event>(prior).chapter ?: continue
            val manga = db.getManga(previous.mangaUrl, previous.source).executeAsBlocking() ?: continue
            if (!manga.favorite) continue
            val chapter = db.getChapter(previous.url, manga.id ?: continue).executeAsBlocking() ?: continue
            snapshot[key] = Event(
                key, "", 0,
                chapter = previous.copy(
                    name = chapter.name,
                    read = false,
                    bookmark = false,
                    lastPage = 0,
                    pagesLeft = 0,
                ),
            )
        }
        for ((key, event) in snapshot) {
            if (journal.baseline[key] != fingerprint(event)) {
                journal.clock++
                journal.events[key] = event.copy(deviceId = journal.deviceId, clock = journal.clock)
            }
        }
        // A missing previously-favorited manga is an explicit un-favorite event.
        for ((key, prior) in journal.baseline) {
            if (key.startsWith("m:") && key !in snapshot) {
                val old = json.decodeFromString<Event>(prior).manga ?: continue
                journal.clock++
                journal.events[key] = Event(key, journal.deviceId, journal.clock, manga = old.copy(favorite = false))
            }
        }
        journal.clock = maxOf(journal.clock, allEvents.maxOfOrNull { it.clock } ?: 0L)
        save(journal) // Persist outgoing events BEFORE any remote I/O.

        // Upload only this device's journal. Failure cannot cause a remote import.
        val ownJson = json.encodeToString(Envelope(deviceId = journal.deviceId, events = journal.events.values.toList()))
        if (ownFiles.isEmpty()) {
            drive.createFile(filename, ownJson)
        } else {
            drive.updateFile(ownFiles.first().id, ownJson)
        }

        // Resolve all current entities by a deterministic total order.
        val latest = mutableMapOf<String, Event>()
        for (event in allEvents + journal.events.values) {
            require(event.deviceId.isNotBlank() && event.clock >= 0) { "Invalid sync event" }
            val previous = latest[event.key]
            if (previous == null || isNewer(event, previous)) latest[event.key] = event
        }

        // Avoid re-writing unchanged titles, categories and progress on every
        // periodic run. Chapter progress must follow manga creation.
        db.inTransaction {
            latest.values.filter { it.manga != null }.sortedBy { it.key }.forEach { event ->
                if (fingerprint(event) != snapshot[event.key]?.let(::fingerprint)) applyManga(event.manga!!)
            }
            latest.values.filter { it.chapter != null }.sortedBy { it.key }.forEach { event ->
                if (fingerprint(event) != snapshot[event.key]?.let(::fingerprint)) applyChapter(event.chapter!!)
            }
        }
        // Baseline is the actual merged local state, not just the remote JSON.
        journal.baseline.clear()
        journal.baseline.putAll(snapshot().mapValues { (_, value) -> fingerprint(value) })
        journal.clock = maxOf(journal.clock, latest.values.maxOfOrNull { it.clock } ?: 0L)
        save(journal)
        Timber.i("Cloud sync complete: %d entities, %d own changes", latest.size, journal.events.size)
    }

    private fun isNewer(a: Event, b: Event): Boolean =
        a.clock > b.clock || (a.clock == b.clock && a.deviceId > b.deviceId)

    private fun fingerprint(value: Event): String = json.encodeToString(value.copy(deviceId = "", clock = 0))

    private fun key(source: Long, mangaUrl: String): String = "m:$source:${encoded(mangaUrl)}"

    private fun chapterKey(source: Long, mangaUrl: String, chapterUrl: String): String =
        "${key(source, mangaUrl)}:c:${encoded(chapterUrl)}"

    private fun encoded(url: String): String = Base64.encodeToString(
        url.toByteArray(Charsets.UTF_8),
        Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING,
    )

    private fun snapshot(): Map<String, Event> {
        val result = mutableMapOf<String, Event>()
        val mangas = db.getFavoriteMangas().executeAsBlocking()
        for (manga in mangas) {
            val id = manga.id ?: continue
            val categoryNames = db.getCategoriesForManga(manga).executeAsBlocking()
                .filter { (it.id ?: 0) > 0 && !it.isDynamic }.map { it.name }.sorted()
            val custom = customManga.getCustomInfo(manga)
            val mangaData = MangaData(
                source = manga.source,
                url = manga.url,
                title = manga.originalTitle,
                author = manga.originalAuthor,
                artist = manga.originalArtist,
                description = manga.originalDescription,
                genre = manga.originalGenre,
                thumbnail = manga.thumbnail_url,
                status = manga.originalStatus,
                customTitle = custom?.title,
                customAuthor = custom?.author,
                customArtist = custom?.artist,
                customDescription = custom?.description,
                customGenre = custom?.genre?.toList(),
                customStatus = custom?.status,
                categories = categoryNames,
            )
            val mangaKey = key(manga.source, manga.url)
            result[mangaKey] = Event(mangaKey, "", 0, manga = mangaData)
            for (chapter in db.getChapters(id).executeAsBlocking()) {
                // Unopened chapters contain no user progress and need not be synced.
                if (!chapter.read && !chapter.bookmark && chapter.last_page_read == 0) continue
                val chapterId = chapterKey(manga.source, manga.url, chapter.url)
                result[chapterId] = Event(
                    chapterId, "", 0,
                    chapter = ChapterData(
                        source = manga.source,
                        mangaUrl = manga.url,
                        url = chapter.url,
                        name = chapter.name,
                        chapterNumber = chapter.chapter_number,
                        read = chapter.read,
                        bookmark = chapter.bookmark,
                        lastPage = chapter.last_page_read,
                        pagesLeft = chapter.pages_left,
                    ),
                )
            }
        }
        return result
    }

    private fun applyManga(data: MangaData) {
        var manga = db.getManga(data.url, data.source).executeAsBlocking()
        if (manga == null) {
            if (!data.favorite) return
            manga = Manga.create(data.url, data.title, data.source).apply {
                favorite = true
                author = data.author
                artist = data.artist
                description = data.description
                genre = data.genre
                status = data.status
                thumbnail_url = data.thumbnail
                date_added = System.currentTimeMillis()
            }
            manga.id = db.insertManga(manga).executeAsBlocking().insertedId()
        } else if (manga.favorite != data.favorite) {
            manga.favorite = data.favorite
            db.updateMangaFavorite(manga).executeAsBlocking()
        }
        if (!data.favorite) return
        val id = manga.id ?: error("Manga insert did not return an ID")
        customManga.saveMangaInfo(
            CustomMangaManager.MangaJson(
                id = id,
                title = data.customTitle,
                author = data.customAuthor,
                artist = data.customArtist,
                description = data.customDescription,
                genre = data.customGenre?.toTypedArray(),
                status = data.customStatus,
            ),
        )
        val existingCategories = db.getCategories().executeAsBlocking().toMutableList()
        val wanted = data.categories.map { name ->
            existingCategories.find { it.name == name } ?: Category.create(name).also { category ->
                category.id = db.insertCategory(category).executeAsBlocking().insertedId()?.toInt()
                existingCategories.add(category)
            }
        }
        val mappings = wanted.map { category -> MangaCategory.create(manga, category) }
        db.setMangaCategories(mappings, listOf(manga))
    }

    private fun applyChapter(data: ChapterData) {
        val manga = db.getManga(data.mangaUrl, data.source).executeAsBlocking() ?: return
        if (!manga.favorite) return
        val chapter = db.getChapter(data.url, manga.id ?: return).executeAsBlocking()
        if (chapter == null) {
            val created = ChapterImpl().apply {
                manga_id = manga.id
                url = data.url
                name = data.name
                chapter_number = data.chapterNumber
                read = data.read
                bookmark = data.bookmark
                last_page_read = data.lastPage
                pages_left = data.pagesLeft
            }
            db.insertChapter(created).executeAsBlocking()
        } else {
            if (chapter.read == data.read && chapter.bookmark == data.bookmark &&
                chapter.last_page_read == data.lastPage && chapter.pages_left == data.pagesLeft
            ) return
            chapter.read = data.read
            chapter.bookmark = data.bookmark
            chapter.last_page_read = data.lastPage
            chapter.pages_left = data.pagesLeft
            db.updateChapterProgress(chapter).executeAsBlocking()
        }
    }

    private fun load(accountId: String): Journal {
        val file = journalFile.baseFile
        if (!file.exists()) return Journal(accountId = accountId)
        val state = json.decodeFromString<Journal>(journalFile.openRead().bufferedReader().use { it.readText() })
        require(state.schemaVersion == 1) { "Unsupported local sync journal version" }
        require(state.accountId == accountId) {
            "Sync journal belongs to another Google account. Export a backup before changing accounts."
        }
        return state
    }

    private fun save(journal: Journal) {
        val bytes = json.encodeToString(journal).toByteArray(Charsets.UTF_8)
        val stream = journalFile.startWrite()
        try {
            stream.write(bytes)
            journalFile.finishWrite(stream)
        } catch (error: Exception) {
            journalFile.failWrite(stream)
            throw error
        }
    }
}
