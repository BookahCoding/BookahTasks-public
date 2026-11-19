package dev.bookah.tasks.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.util.concurrent.atomic.AtomicLong

/** The phone's copy of the lists and their tasks: two small tables in plain SQLite (no code generation needed). The raw item text is what is stored. */
class SqliteTaskStore(context: Context, name: String = "tasks.db") : TaskStore {
    private val helper = object : SQLiteOpenHelper(context.applicationContext, name, null, 4) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE lists(id INTEGER PRIMARY KEY AUTOINCREMENT, href TEXT NOT NULL UNIQUE, name TEXT NOT NULL, " +
                    "color TEXT NOT NULL, kind INTEGER NOT NULL, editable INTEGER NOT NULL, visible INTEGER NOT NULL, sync_token TEXT, sort INTEGER NOT NULL, " +
                    "show_events INTEGER NOT NULL DEFAULT 1)",
            )
            db.execSQL("CREATE TABLE objects(href TEXT PRIMARY KEY, list_id INTEGER NOT NULL, etag TEXT NOT NULL, ics TEXT NOT NULL)")
            db.execSQL("CREATE INDEX objects_list ON objects(list_id)")
            db.execSQL(OUTBOX_SQL)
            createEvents(db)
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            // each step on its own, so a phone several versions behind gets every step in order
            if (oldVersion < 2) db.execSQL(OUTBOX_SQL)                                              // already has after_href
            if (oldVersion == 2) db.execSQL("ALTER TABLE outbox ADD COLUMN after_href TEXT")
            if (oldVersion < 4) {
                db.execSQL("ALTER TABLE lists ADD COLUMN show_events INTEGER NOT NULL DEFAULT 1")
                createEvents(db)
                db.execSQL("UPDATE lists SET sync_token=NULL WHERE kind=${ListKind.Events.ordinal}")    // never synced before: start with a full fetch
            }
        }

        /** An older app version installed over this one: keep the data as it is (the newer tables and columns are simply not used). */
        override fun onDowngrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {}

        /** Phase 5: events of event-only calendars, with the span of days each one can touch (epoch days; see [EventSpan]). */
        private fun createEvents(db: SQLiteDatabase) {
            db.execSQL("CREATE TABLE events(href TEXT PRIMARY KEY, list_id INTEGER NOT NULL, etag TEXT NOT NULL, ics TEXT NOT NULL, span_from INTEGER NOT NULL, span_to INTEGER NOT NULL)")
            db.execSQL("CREATE INDEX events_list ON events(list_id)")
            db.execSQL("CREATE INDEX events_span ON events(span_to, span_from)")
        }
    }
    private val ver = AtomicLong(0)
    private val evVer = AtomicLong(0)

    override fun setShowEvents(listId: Long, show: Boolean) {
        db.update("lists", ContentValues().apply { put("show_events", if (show) 1 else 0) }, "id=?", arrayOf(listId.toString()))
        evVer.incrementAndGet()
    }

    override fun eventObjects(listId: Long) = query("SELECT href, list_id, etag, ics FROM events WHERE list_id=?", arrayOf(listId.toString()))

    /** A row too large to read back (about 2 MB on Android) would break the whole query and blank the strip: such rows are left out. */
    override fun eventsTouching(fromDay: Long, toDay: Long) =
        query("SELECT href, list_id, etag, ics FROM events WHERE span_to>=? AND span_from<=? AND length(ics)<=?", arrayOf(fromDay.toString(), toDay.toString(), SyncEngine.MAX_EVENT_CHARS.toString()))

    override fun eventEtags(listId: Long): Map<String, String> {
        val out = HashMap<String, String>()
        db.rawQuery("SELECT href, etag FROM events WHERE list_id=?", arrayOf(listId.toString())).use { c -> while (c.moveToNext()) out[c.getString(0)] = c.getString(1) }
        return out
    }

    override fun upsertEvents(listId: Long, objects: List<RemoteObject>, spans: List<Pair<Long, Long>>?) {
        val spans = spans ?: objects.map { EventSpan.of(it.ics) }          // worked out before the transaction: parsing takes a moment
        db.beginTransaction()
        try {
            objects.forEachIndexed { i, o ->
                val v = ContentValues().apply {
                    put("href", o.href); put("list_id", listId); put("etag", o.etag); put("ics", o.ics)
                    put("span_from", spans[i].first); put("span_to", spans[i].second)
                }
                db.insertWithOnConflict("events", null, v, SQLiteDatabase.CONFLICT_REPLACE)
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        evVer.incrementAndGet()
    }

    override fun deleteEvents(listId: Long, hrefs: List<String>) {
        db.beginTransaction()
        try {
            for (h in hrefs) db.delete("events", "href=? AND list_id=?", arrayOf(h, listId.toString()))
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        evVer.incrementAndGet()
    }

    override fun clearEvents(listId: Long) { db.delete("events", "list_id=?", arrayOf(listId.toString())); evVer.incrementAndGet() }
    override fun eventsVersion() = evVer.get()
    private val db: SQLiteDatabase get() = helper.writableDatabase

    override fun replaceLists(remote: List<RemoteList>): List<TaskList> {
        db.beginTransaction()
        try {
            val known = lists().associateBy { it.href }
            val keep = HashSet<String>()
            for (r in remote) {
                keep.add(r.href)
                val old = known[r.href]
                if (old == null) {
                    val v = ContentValues().apply {
                        put("href", r.href); put("name", r.name); put("color", r.colorHex ?: DEFAULT_COLOR)
                        put("kind", r.kind.ordinal); put("editable", if (r.canWrite) 1 else 0); put("visible", 1); put("sort", r.order)
                    }
                    db.insert("lists", null, v)
                } else {
                    // a calendar that starts or stops holding tasks: its rows belong in the other table now, so the old ones go and a full fetch follows
                    val kindMoved = old.kind != r.kind && (old.kind == ListKind.Events || r.kind == ListKind.Events)
                    val v = ContentValues().apply {
                        put("name", r.name); put("color", r.colorHex ?: old.colorHex); put("kind", r.kind.ordinal)
                        put("editable", if (r.canWrite) 1 else 0); put("sort", r.order)
                        if (kindMoved) putNull("sync_token")
                    }
                    db.update("lists", v, "id=?", arrayOf(old.id.toString()))
                    if (kindMoved) {
                        db.delete("objects", "list_id=?", arrayOf(old.id.toString()))
                        db.delete("events", "list_id=?", arrayOf(old.id.toString()))
                    }
                }
            }
            for (old in known.values) if (old.href !in keep) {
                db.delete("objects", "list_id=?", arrayOf(old.id.toString()))
                db.delete("events", "list_id=?", arrayOf(old.id.toString()))
                db.delete("outbox", "list_id=?", arrayOf(old.id.toString()))
                db.delete("lists", "id=?", arrayOf(old.id.toString()))
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        ver.incrementAndGet()
        evVer.incrementAndGet()
        return lists()
    }

    override fun lists(): List<TaskList> {
        val out = ArrayList<TaskList>()
        db.rawQuery("SELECT id, href, name, color, kind, editable, visible, sync_token, sort, show_events FROM lists ORDER BY sort, id", null).use { c ->
            while (c.moveToNext()) {
                out.add(
                    TaskList(
                        id = c.getLong(0), href = c.getString(1), name = c.getString(2), colorHex = c.getString(3),
                        kind = ListKind.entries[c.getInt(4)], editable = c.getInt(5) == 1, visible = c.getInt(6) == 1,
                        syncToken = if (c.isNull(7)) null else c.getString(7), order = c.getInt(8), showEvents = c.getInt(9) == 1,
                    ),
                )
            }
        }
        return out
    }

    override fun setVisible(listId: Long, visible: Boolean) {
        db.update("lists", ContentValues().apply { put("visible", if (visible) 1 else 0) }, "id=?", arrayOf(listId.toString()))
        ver.incrementAndGet()
    }

    override fun setSyncToken(listId: Long, token: String?) {
        db.update("lists", ContentValues().apply { if (token == null) putNull("sync_token") else put("sync_token", token) }, "id=?", arrayOf(listId.toString()))
    }

    override fun objects(listId: Long) = query("SELECT href, list_id, etag, ics FROM objects WHERE list_id=?", arrayOf(listId.toString()))
    override fun allObjects() = query("SELECT href, list_id, etag, ics FROM objects", null)
    override fun objectByHref(href: String) = query("SELECT href, list_id, etag, ics FROM objects WHERE href=?", arrayOf(href)).firstOrNull()

    private fun query(sql: String, args: Array<String>?): List<StoredObject> {
        val out = ArrayList<StoredObject>()
        db.rawQuery(sql, args).use { c -> while (c.moveToNext()) out.add(StoredObject(c.getString(0), c.getLong(1), c.getString(2), c.getString(3))) }
        return out
    }

    override fun upsertObjects(listId: Long, objects: List<RemoteObject>) {
        db.beginTransaction()
        try {
            for (o in objects) {
                val v = ContentValues().apply { put("href", o.href); put("list_id", listId); put("etag", o.etag); put("ics", o.ics) }
                db.insertWithOnConflict("objects", null, v, SQLiteDatabase.CONFLICT_REPLACE)
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        ver.incrementAndGet()
    }

    override fun deleteObjects(listId: Long, hrefs: List<String>) {
        db.beginTransaction()
        try {
            for (h in hrefs) db.delete("objects", "href=? AND list_id=?", arrayOf(h, listId.toString()))
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        ver.incrementAndGet()
    }

    override fun clearObjects(listId: Long) {
        db.delete("objects", "list_id=?", arrayOf(listId.toString()))
        ver.incrementAndGet()
    }

    override fun objectCount(listId: Long): Int =
        db.rawQuery("SELECT COUNT(*) FROM objects WHERE list_id=?", arrayOf(listId.toString())).use { c -> if (c.moveToFirst()) c.getInt(0) else 0 }

    override fun outboxAll(): List<OutboxItem> {
        val out = ArrayList<OutboxItem>()
        db.rawQuery(
            "SELECT id, kind, list_id, href, base_etag, ics, created_at, attempts, state, last_code, server_etag, server_ics, after_href FROM outbox ORDER BY id", null,
        ).use { c ->
            while (c.moveToNext()) {
                out.add(
                    OutboxItem(
                        id = c.getLong(0), kind = OutboxKind.entries[c.getInt(1)], listId = c.getLong(2), href = c.getString(3),
                        baseEtag = if (c.isNull(4)) null else c.getString(4), ics = if (c.isNull(5)) null else c.getString(5),
                        createdAtMs = c.getLong(6), attempts = c.getInt(7), state = OutboxState.entries[c.getInt(8)], lastCode = c.getInt(9),
                        serverEtag = if (c.isNull(10)) null else c.getString(10), serverIcs = if (c.isNull(11)) null else c.getString(11),
                        after = if (c.isNull(12)) null else c.getString(12),
                    ),
                )
            }
        }
        return out
    }

    private fun values(item: OutboxItem) = ContentValues().apply {
        put("kind", item.kind.ordinal); put("list_id", item.listId); put("href", item.href)
        if (item.baseEtag == null) putNull("base_etag") else put("base_etag", item.baseEtag)
        if (item.ics == null) putNull("ics") else put("ics", item.ics)
        put("created_at", item.createdAtMs); put("attempts", item.attempts); put("state", item.state.ordinal); put("last_code", item.lastCode)
        if (item.serverEtag == null) putNull("server_etag") else put("server_etag", item.serverEtag)
        if (item.serverIcs == null) putNull("server_ics") else put("server_ics", item.serverIcs)
        if (item.after == null) putNull("after_href") else put("after_href", item.after)
    }

    override fun outboxInsert(item: OutboxItem): Long { val id = db.insertOrThrow("outbox", null, values(item)); ver.incrementAndGet(); return id }
    override fun outboxUpdate(item: OutboxItem) { db.update("outbox", values(item), "id=?", arrayOf(item.id.toString())); ver.incrementAndGet() }
    override fun outboxRemove(id: Long) { db.delete("outbox", "id=?", arrayOf(id.toString())); ver.incrementAndGet() }

    override fun version() = ver.get()

    override fun clearAll() {
        db.delete("objects", null, null)
        db.delete("events", null, null)
        db.delete("outbox", null, null)
        db.delete("lists", null, null)
        ver.incrementAndGet()
        evVer.incrementAndGet()
    }
}

private const val OUTBOX_SQL =
    "CREATE TABLE outbox(id INTEGER PRIMARY KEY AUTOINCREMENT, kind INTEGER NOT NULL, list_id INTEGER NOT NULL, href TEXT NOT NULL, " +
        "base_etag TEXT, ics TEXT, created_at INTEGER NOT NULL, attempts INTEGER NOT NULL, state INTEGER NOT NULL, last_code INTEGER NOT NULL, " +
        "server_etag TEXT, server_ics TEXT, after_href TEXT)"
