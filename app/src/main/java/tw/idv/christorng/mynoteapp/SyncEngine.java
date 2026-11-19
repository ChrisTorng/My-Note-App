package tw.idv.christorng.mynoteapp;

import org.json.JSONObject;

/** Foreground sync only: no persistent service, receiver, polling daemon, or wake lock. */
final class SyncEngine {
    private final ApiClient api;
    private final LocalDb db;
    SyncEngine(ApiClient api, LocalDb db) { this.api=api; this.db=db; }

    void run() throws Exception {
        for (LocalDb.Note note:db.dirtyNotes()) {
            if (note.content.trim().isEmpty()) continue;
            api.request("/note", "PUT", note.json());
            db.clearDirty(note.id, note.revision);
        }
        for (LocalDb.Note note:db.pendingArchives()) {
            JSONObject req=new JSONObject().put("id",note.id).put("archived",note.archivedAt!=null);
            api.request("/archive", "PATCH", req);
            db.clearArchive(note.id,note.archivedAt!=null);
        }
        for (String id:db.pendingDeletes()) {
            api.request("/note", "DELETE", new JSONObject().put("id",id));
            db.clearDelete(id);
        }
        JSONObject remote=api.request("/state?version="+api.remoteVersion(),"GET",null);
        if (!remote.optBoolean("unchanged",false)) {
            db.merge(remote);
            api.setRemoteVersion(remote.optInt("version",0));
        }
    }
}
