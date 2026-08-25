package com.tpn.adbautoenable;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

public class TclBootPolicyTest {
    private static final String PACKAGE_NAME = "com.tpn.adbautoenable";

    @Test
    public void protectsT653WhitelistPolicy() throws Exception {
        JSONArray policy = new JSONArray("[{"
                + "\"version\":1,\"subVersion\":4,"
                + "\"serviceStartForegroundWhiteList\":{\"packageName\":[\"com.tcl.system\"]},"
                + "\"procWhiteList\":{\"packageName\":[\"com.tcl.guard\"]},"
                + "\"unknown\":{\"preserved\":true}}]");

        assertTrue(TclBootPolicy.protect(policy, PACKAGE_NAME));
        assertTrue(TclBootPolicy.isProtected(policy, PACKAGE_NAME));
        JSONObject root = policy.getJSONObject(0);
        assertEquals(5, root.getInt("subVersion"));
        assertTrue(root.getJSONObject("unknown").getBoolean("preserved"));
    }

    @Test
    public void protectsBlacklistPolicy() throws Exception {
        JSONArray policy = new JSONArray("[{"
                + "\"serviceStartForegroundBlackList\":{\"packageName\":[\"com.tpn.adbautoenable\",\"other.app\"]}}]");

        assertTrue(TclBootPolicy.protect(policy, PACKAGE_NAME));
        assertTrue(TclBootPolicy.isProtected(policy, PACKAGE_NAME));
        assertEquals("other.app", policy.getJSONObject(0)
                .getJSONObject("serviceStartForegroundBlackList")
                .getJSONArray("packageName").getString(0));
    }

    @Test
    public void refusesAmbiguousPolicy() throws Exception {
        JSONArray policy = new JSONArray("[{"
                + "\"serviceStartForegroundWhiteList\":{\"packageName\":[]},"
                + "\"serviceStartForegroundBlackList\":{\"packageName\":[]}}]");

        assertFalse(TclBootPolicy.supports(policy));
        assertFalse(TclBootPolicy.protect(policy, PACKAGE_NAME));
    }

    @Test
    public void repeatedProtectionIsIdempotent() throws Exception {
        JSONArray policy = new JSONArray("[{"
                + "\"serviceStartForegroundWhiteList\":{\"packageName\":[\"com.tpn.adbautoenable\"]},"
                + "\"procWhiteList\":{\"packageName\":[\"com.tpn.adbautoenable\"]}}]");

        assertFalse(TclBootPolicy.protect(policy, PACKAGE_NAME));
        assertTrue(TclBootPolicy.isProtected(policy, PACKAGE_NAME));
    }
}
