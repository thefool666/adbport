package com.tpn.adbautoenable;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

final class TclBootPolicy {
    private static final String FOREGROUND_WHITELIST = "serviceStartForegroundWhiteList";
    private static final String FOREGROUND_BLACKLIST = "serviceStartForegroundBlackList";
    private static final String PROCESS_WHITELIST = "procWhiteList";
    private static final String PACKAGE_NAMES = "packageName";
    private static final int MAX_SUB_VERSION = 0x1ff;

    private TclBootPolicy() {
    }

    static boolean supports(JSONArray policy) {
        JSONObject root = policy.optJSONObject(0);
        if (root == null) {
            return false;
        }
        return root.has(FOREGROUND_WHITELIST) ^ root.has(FOREGROUND_BLACKLIST);
    }

    static boolean protect(JSONArray policy, String packageName) {
        if (!supports(policy) || !isValidPackageName(packageName)) {
            return false;
        }

        JSONObject root = policy.optJSONObject(0);
        boolean changed;
        if (root.has(FOREGROUND_WHITELIST)) {
            changed = addPackage(root, FOREGROUND_WHITELIST, packageName);
        } else {
            changed = removePackage(root, FOREGROUND_BLACKLIST, packageName);
        }

        if (root.has(PROCESS_WHITELIST)) {
            changed = addPackage(root, PROCESS_WHITELIST, packageName) || changed;
        }

        if (changed) {
            advanceVersion(root);
        }
        return changed;
    }

    static boolean isProtected(JSONArray policy, String packageName) {
        if (!supports(policy) || !isValidPackageName(packageName)) {
            return false;
        }

        JSONObject root = policy.optJSONObject(0);
        boolean foregroundProtected;
        if (root.has(FOREGROUND_WHITELIST)) {
            foregroundProtected = containsPackage(root, FOREGROUND_WHITELIST, packageName);
        } else {
            foregroundProtected = !containsPackage(root, FOREGROUND_BLACKLIST, packageName);
        }

        return foregroundProtected
                && (!root.has(PROCESS_WHITELIST)
                || containsPackage(root, PROCESS_WHITELIST, packageName));
    }

    private static boolean addPackage(JSONObject root, String key, String packageName) {
        JSONObject section = root.optJSONObject(key);
        if (section == null) {
            return false;
        }
        JSONArray packages = section.optJSONArray(PACKAGE_NAMES);
        if (packages == null) {
            packages = new JSONArray();
            try {
                section.put(PACKAGE_NAMES, packages);
            } catch (JSONException e) {
                return false;
            }
        }
        if (contains(packages, packageName)) {
            return false;
        }
        packages.put(packageName);
        return true;
    }

    private static boolean removePackage(JSONObject root, String key, String packageName) {
        JSONObject section = root.optJSONObject(key);
        JSONArray packages = section != null ? section.optJSONArray(PACKAGE_NAMES) : null;
        if (packages == null) {
            return false;
        }

        boolean changed = false;
        for (int index = packages.length() - 1; index >= 0; index--) {
            if (packageName.equals(packages.optString(index))) {
                packages.remove(index);
                changed = true;
            }
        }
        return changed;
    }

    private static boolean containsPackage(JSONObject root, String key, String packageName) {
        JSONObject section = root.optJSONObject(key);
        JSONArray packages = section != null ? section.optJSONArray(PACKAGE_NAMES) : null;
        return packages != null && contains(packages, packageName);
    }

    private static boolean contains(JSONArray values, String expected) {
        for (int index = 0; index < values.length(); index++) {
            if (expected.equals(values.optString(index))) {
                return true;
            }
        }
        return false;
    }

    private static void advanceVersion(JSONObject root) {
        Object versionValue = root.opt("version");
        Object subVersionValue = root.opt("subVersion");
        if (!(versionValue instanceof Number) || !(subVersionValue instanceof Number)) {
            return;
        }

        int version = ((Number) versionValue).intValue();
        int subVersion = ((Number) subVersionValue).intValue();
        if (version < 0 || subVersion < 0) {
            return;
        }
        try {
            if (subVersion < MAX_SUB_VERSION) {
                root.put("subVersion", subVersion + 1);
            } else {
                root.put("version", version + 1);
                root.put("subVersion", 0);
            }
        } catch (JSONException ignored) {
            // The policy update remains valid when this optional version marker
            // cannot be advanced. ContentProvider notifications still reload it.
        }
    }

    private static boolean isValidPackageName(String packageName) {
        return packageName != null
                && packageName.matches("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+");
    }
}
