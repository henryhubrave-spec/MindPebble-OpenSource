package org.diy.liferecorder;

import android.content.ContentValues;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import org.json.JSONObject;

/** Version 13: indexed queue policy, durable cleanup and incremental search revisions. */
final class WorkflowSchema {
    static void create(SQLiteDatabase db) {
        db.execSQL("ALTER TABLE jobs ADD COLUMN automatic INTEGER NOT NULL DEFAULT 0");
        // Parse exact JSON fields once; matching JSON text with LIKE is ambiguous.
        try (Cursor rows = db.rawQuery("SELECT id,input FROM jobs", null)) {
            while (rows.moveToNext()) {
                try {
                    if (!new JSONObject(rows.getString(1)).optBoolean("automatic")) continue;
                    ContentValues value = new ContentValues();
                    value.put("automatic", 1);
                    db.update("jobs", value, "id=?", new String[]{rows.getString(0)});
                } catch (org.json.JSONException ignored) { /* Malformed jobs cannot execute. */ }
            }
        }
        db.execSQL("CREATE INDEX jobs_queue ON jobs(status,type,created)");
        db.execSQL("CREATE INDEX jobs_source ON jobs(source)");
        db.execSQL("CREATE INDEX facts_source_state ON facts(source,state)");
        db.execSQL("CREATE INDEX tasks_source ON tasks(source)");
        db.execSQL("CREATE TABLE pending_file_deletes(path TEXT PRIMARY KEY)");
        db.execSQL("CREATE TABLE search_clock(id INTEGER PRIMARY KEY CHECK(id=1),version INTEGER NOT NULL)");
        db.execSQL("INSERT INTO search_clock VALUES(1,1)");
        // Keep a latest version per source, including deletions, until the index catches up.
        db.execSQL("CREATE TABLE search_changes(source TEXT PRIMARY KEY,version INTEGER NOT NULL)");
        db.execSQL("CREATE INDEX search_version ON search_changes(version)");
        db.execSQL("INSERT INTO search_changes SELECT id,1 FROM audio");
        trigger(db, "search_audio_insert", "AFTER INSERT ON audio", "NEW.id");
        trigger(db, "search_audio_delete", "AFTER DELETE ON audio", "OLD.id");
        trigger(db, "search_audio_update", "AFTER UPDATE OF title,created,transcript,speech_json,transcript_revision ON audio", "NEW.id");
        trigger(db, "search_fact_insert", "AFTER INSERT ON facts", "NEW.source");
        trigger(db, "search_fact_delete", "AFTER DELETE ON facts", "OLD.source");
        trigger(db, "search_fact_update", "AFTER UPDATE ON facts", "OLD.source", "NEW.source");
    }

    private static void trigger(SQLiteDatabase db, String name, String event, String... sources) {
        StringBuilder sql = new StringBuilder("CREATE TRIGGER ").append(name).append(' ').append(event)
            .append(" BEGIN UPDATE search_clock SET version=version+1 WHERE id=1;");
        for (String source : sources) {
            sql.append("INSERT OR REPLACE INTO search_changes SELECT ").append(source)
                .append(",version FROM search_clock WHERE id=1 AND ").append(source).append(" IS NOT NULL;");
        }
        db.execSQL(sql.append(" END").toString());
    }
}
