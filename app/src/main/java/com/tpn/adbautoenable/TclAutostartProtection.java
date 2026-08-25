package com.tpn.adbautoenable;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.util.Log;

import org.json.JSONArray;

final class TclAutostartProtection {
    private static final String TAG = "ADBAutoEnable";
    private static final String MODULE = "AppBootPolicy";
    private static final String[] AUTHORITIES = {
            "com.tcl.providers.config",
            "com.tcl.config.ConfigProvider"
    };
    private static final String[] PROJECTION = {"project_id", "config_content"};

    private TclAutostartProtection() {
    }

    static Result apply(ContentResolver resolver, String packageName) {
        for (String authority : AUTHORITIES) {
            Uri uri = Uri.parse("content://" + authority + "/" + MODULE);
            JSONArray policy = readPolicy(resolver, uri);
            if (policy == null) {
                continue;
            }
            if (!TclBootPolicy.supports(policy)) {
                Log.w(TAG, "TCL AppBootPolicy schema is unsupported at " + authority);
                return Result.unsupported(authority);
            }

            boolean changed = TclBootPolicy.protect(policy, packageName);
            if (changed) {
                ContentValues values = new ContentValues();
                values.put("config_content", policy.toString());
                try {
                    int rows = resolver.update(uri, values, null, null);
                    if (rows <= 0) {
                        Log.e(TAG, "TCL AppBootPolicy update changed " + rows + " rows");
                        return Result.failed(authority);
                    }
                } catch (Exception e) {
                    Log.e(TAG, "TCL AppBootPolicy update failed at " + authority, e);
                    return Result.failed(authority);
                }
            }

            JSONArray verified = readPolicy(resolver, uri);
            if (verified != null && TclBootPolicy.isProtected(verified, packageName)) {
                Log.i(TAG, "TCL AppBootPolicy protection "
                        + (changed ? "applied" : "verified") + " at " + authority);
                return Result.success(authority, changed);
            }

            Log.e(TAG, "TCL AppBootPolicy verification failed at " + authority);
            return Result.failed(authority);
        }

        Log.i(TAG, "No supported TCL AppBootPolicy provider detected");
        return Result.unavailable();
    }

    private static JSONArray readPolicy(ContentResolver resolver, Uri uri) {
        try (Cursor cursor = resolver.query(uri, PROJECTION, null, null, null)) {
            if (cursor == null || !cursor.moveToFirst()) {
                return null;
            }
            int contentIndex = cursor.getColumnIndex("config_content");
            if (contentIndex < 0) {
                return null;
            }
            String content = cursor.getString(contentIndex);
            return content != null ? new JSONArray(content) : null;
        } catch (Exception e) {
            Log.d(TAG, "TCL policy provider unavailable at " + uri + ": " + e.getMessage());
            return null;
        }
    }

    static final class Result {
        final boolean available;
        final boolean success;
        final boolean changed;
        final String authority;

        private Result(boolean available, boolean success, boolean changed, String authority) {
            this.available = available;
            this.success = success;
            this.changed = changed;
            this.authority = authority;
        }

        static Result success(String authority, boolean changed) {
            return new Result(true, true, changed, authority);
        }

        static Result failed(String authority) {
            return new Result(true, false, false, authority);
        }

        static Result unsupported(String authority) {
            return new Result(true, false, false, authority);
        }

        static Result unavailable() {
            return new Result(false, false, false, null);
        }
    }
}
