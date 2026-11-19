package tw.idv.christorng.mynoteapp;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.Set;

/** SQLite is the source of truth while offline. A pending flag survives process termination. */
final class LocalDb extends SQLiteOpenHelper {
    static final class Note {
        String id, categoryId, content, originDeviceId, originDeviceName;
        long createdAt, updatedAt;
        Long archivedAt;
        int revision;
        boolean dirty, archivePending;

        JSONObject json() throws Exception {
            JSONObject value = new JSONObject().put("id", id).put("categoryId", categoryId)
                    .put("content", content).put("createdAt", createdAt).put("updatedAt", updatedAt)
                    .put("revision", revision);
            value.put("archivedAt", archivedAt == null ? JSONObject.NULL : archivedAt);
            return value;
        }
        static Note remote(JSONObject object) {
            Note n = new Note();
            n.id = object.optString("id");
            n.categoryId = object.optString("categoryId", "inbox");
            n.content = object.optString("content", "");
            n.originDeviceId = object.optString("originDeviceId", "");
            n.originDeviceName = object.optString("originDeviceName", "");
            n.createdAt = object.optLong("createdAt");
            n.updatedAt = object.optLong("updatedAt");
            n.archivedAt = object.isNull("archivedAt") ? null : object.optLong("archivedAt");
            n.revision = object.optInt("revision");
            return n;
        }
    }
    static final class Category {
        String id, name;
        Category(String id, String name) { this.id=id; this.name=name; }
    }

    LocalDb(Context context) { super(context.getApplicationContext(), "mynote-native.db", null, 1); }

    @Override public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE notes (id TEXT PRIMARY KEY, category_id TEXT NOT NULL, content TEXT NOT NULL, origin_device_id TEXT NOT NULL, origin_device_name TEXT NOT NULL, created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL, archived_at INTEGER, revision INTEGER NOT NULL, dirty INTEGER NOT NULL DEFAULT 0, archive_pending INTEGER NOT NULL DEFAULT 0)");
        db.execSQL("CREATE INDEX notes_category ON notes(category_id,updated_at DESC)");
        db.execSQL("CREATE TABLE categories (id TEXT PRIMARY KEY, name TEXT NOT NULL, pos INTEGER NOT NULL)");
        db.execSQL("INSERT INTO categories (id,name,pos) VALUES ('inbox','Inbox',0)");
        db.execSQL("CREATE TABLE tombstones (id TEXT PRIMARY KEY)");
    }
    @Override public void onUpgrade(SQLiteDatabase db,int oldVersion,int newVersion) { }

    private Note map(Cursor c) {
        Note n = new Note();
        n.id=c.getString(0); n.categoryId=c.getString(1); n.content=c.getString(2);
        n.originDeviceId=c.getString(3); n.originDeviceName=c.getString(4);
        n.createdAt=c.getLong(5); n.updatedAt=c.getLong(6);
        n.archivedAt=c.isNull(7)?null:c.getLong(7);
        n.revision=c.getInt(8); n.dirty=c.getInt(9)!=0; n.archivePending=c.getInt(10)!=0;
        return n;
    }
    private static final String FIELDS="id,category_id,content,origin_device_id,origin_device_name,created_at,updated_at,archived_at,revision,dirty,archive_pending";

    synchronized Note find(String id) {
        try (Cursor c=getReadableDatabase().rawQuery("SELECT "+FIELDS+" FROM notes WHERE id=?",new String[]{id})) {
            return c.moveToFirst()?map(c):null;
        }
    }
    synchronized List<Note> list(boolean archived, String category, String query) {
        List<Note> result = new ArrayList<>();
        String normalized = query.trim().toLowerCase(java.util.Locale.ROOT);
        try (Cursor c=getReadableDatabase().rawQuery("SELECT "+FIELDS+" FROM notes WHERE "
                +(archived?"archived_at IS NOT NULL":"archived_at IS NULL")+" ORDER BY updated_at DESC",null)) {
            while(c.moveToNext()) {
                Note n=map(c);
                if(!normalized.isEmpty()) {
                    SimpleDateFormat date = new SimpleDateFormat("yyyy/MM/dd (E) HH:mm", Locale.TAIWAN);
                    String searchable=n.content+" "+n.originDeviceName+" "+n.categoryId
                            +" "+date.format(new Date(n.createdAt))
                            +" "+date.format(new Date(n.updatedAt));
                    if(!searchable.toLowerCase(Locale.ROOT).contains(normalized)) continue;
                } else if (!"all".equals(category) && !n.categoryId.equals(category)) continue;
                result.add(n);
            }
        }
        return result;
    }
    synchronized List<Note> dirtyNotes() {
        List<Note> notes=new ArrayList<>();
        try(Cursor c=getReadableDatabase().rawQuery("SELECT "+FIELDS+" FROM notes WHERE dirty=1 ORDER BY updated_at",null)) {
            while(c.moveToNext()) notes.add(map(c));
        }
        return notes;
    }
    synchronized List<Note> pendingArchives() {
        List<Note> notes=new ArrayList<>();
        try(Cursor c=getReadableDatabase().rawQuery("SELECT "+FIELDS+" FROM notes WHERE archive_pending=1",null)) {
            while(c.moveToNext()) notes.add(map(c));
        }
        return notes;
    }
    synchronized List<String> pendingDeletes() {
        List<String> ids=new ArrayList<>();
        try(Cursor c=getReadableDatabase().rawQuery("SELECT id FROM tombstones",null)) {
            while(c.moveToNext()) ids.add(c.getString(0));
        }
        return ids;
    }

    private ContentValues values(Note n) {
        ContentValues v = new ContentValues();
        v.put("id",n.id);v.put("category_id",n.categoryId);v.put("content",n.content);
        v.put("origin_device_id",n.originDeviceId);v.put("origin_device_name",n.originDeviceName);
        v.put("created_at",n.createdAt);v.put("updated_at",n.updatedAt);
        if(n.archivedAt==null)v.putNull("archived_at");else v.put("archived_at",n.archivedAt);
        v.put("revision",n.revision);v.put("dirty",n.dirty?1:0);v.put("archive_pending",n.archivePending?1:0);
        return v;
    }
    synchronized void save(Note n) {
        n.dirty=true;
        getWritableDatabase().insertWithOnConflict("notes",null,values(n),SQLiteDatabase.CONFLICT_REPLACE);
    }
    synchronized void archive(String id,boolean archived) {
        ContentValues v=new ContentValues();
        if(archived)v.put("archived_at",System.currentTimeMillis());else v.putNull("archived_at");
        v.put("archive_pending",1);
        getWritableDatabase().update("notes",v,"id=?",new String[]{id});
    }
    synchronized void remove(String id) {
        SQLiteDatabase d=getWritableDatabase();
        d.beginTransaction();
        try {
            d.delete("notes","id=?",new String[]{id});
            ContentValues v=new ContentValues();v.put("id",id);
            d.insertWithOnConflict("tombstones",null,v,SQLiteDatabase.CONFLICT_IGNORE);
            d.setTransactionSuccessful();
        } finally { d.endTransaction(); }
    }
    synchronized void clearDirty(String id,int revision) {
        getWritableDatabase().execSQL("UPDATE notes SET dirty=0 WHERE id=? AND revision=?",new Object[]{id,revision});
    }
    synchronized void clearArchive(String id,boolean archived) {
        getWritableDatabase().execSQL("UPDATE notes SET archive_pending=0 WHERE id=? AND (archived_at IS NOT NULL)=?",new Object[]{id,archived?1:0});
    }
    synchronized void clearDelete(String id) { getWritableDatabase().delete("tombstones","id=?",new String[]{id}); }

    synchronized List<Category> categories() {
        List<Category> result=new ArrayList<>();
        try(Cursor c=getReadableDatabase().rawQuery("SELECT id,name FROM categories ORDER BY pos",null)) {
            while(c.moveToNext()) result.add(new Category(c.getString(0),c.getString(1)));
        }
        return result;
    }

    synchronized void merge(JSONObject state) {
        SQLiteDatabase d=getWritableDatabase();
        d.beginTransaction();
        try {
            JSONArray serverNotes=state.optJSONArray("notes");
            JSONArray serverCategories=state.optJSONArray("categories");
            Set<String> remoteIds=new HashSet<>(), deleted=new HashSet<>(), tombstones=new HashSet<>();
            JSONArray deletedIds=state.optJSONArray("deletedIds");
            if(deletedIds!=null)for(int i=0;i<deletedIds.length();i++)deleted.add(deletedIds.optString(i));
            try(Cursor c=d.rawQuery("SELECT id FROM tombstones",null)) {while(c.moveToNext())tombstones.add(c.getString(0));}
            if(serverNotes!=null)for(int i=0;i<serverNotes.length();i++) {
                JSONObject source=serverNotes.optJSONObject(i);
                if(source==null)continue;
                Note remote=Note.remote(source);
                if(remote.id.isEmpty())continue;
                remoteIds.add(remote.id);
                if(tombstones.contains(remote.id))continue;
                Note local=null;
                try(Cursor c=d.rawQuery("SELECT "+FIELDS+" FROM notes WHERE id=?",new String[]{remote.id})) {
                    if(c.moveToFirst()) local=map(c);
                }
                if(local!=null) {
                    if(local.dirty) {
                        if(!local.archivePending)local.archivedAt=remote.archivedAt;
                        d.insertWithOnConflict("notes",null,values(local),SQLiteDatabase.CONFLICT_REPLACE);
                        continue;
                    }
                    if(local.archivePending) {
                        remote.archivedAt=local.archivedAt;
                        remote.archivePending=true;
                    }
                }
                d.insertWithOnConflict("notes",null,values(remote),SQLiteDatabase.CONFLICT_REPLACE);
            }
            // A missing remote note is deleted unless there is a local edit or archive not yet uploaded.
            List<String> removeIds=new ArrayList<>();
            try(Cursor c=d.rawQuery("SELECT id,dirty,archive_pending FROM notes",null)) {
                while(c.moveToNext()) {
                    String id=c.getString(0);
                    if((!remoteIds.contains(id) && c.getInt(1)==0 && c.getInt(2)==0)
                            || (deleted.contains(id) && c.getInt(1)==0))removeIds.add(id);
                }
            }
            for(String id:removeIds)d.delete("notes","id=?",new String[]{id});
            if(serverCategories!=null) {
                d.delete("categories",null,null);
                for(int i=0;i<serverCategories.length();i++) {
                    JSONObject c=serverCategories.optJSONObject(i);
                    if(c==null)continue;
                    ContentValues values=new ContentValues();
                    values.put("id",c.optString("id"));values.put("name",c.optString("name"));values.put("pos",i);
                    d.insertWithOnConflict("categories",null,values,SQLiteDatabase.CONFLICT_REPLACE);
                }
            }
            d.setTransactionSuccessful();
        }finally{d.endTransaction();}
    }
}
