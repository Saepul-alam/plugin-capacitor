package com.wahana.plugingps;

import com.getcapacitor.JSObject;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

public class WahanaPluginTest {

    private WahanaPlugin implementation;

    @Before
    public void setUp() {
        implementation = new WahanaPlugin();
    }

    @Test
    public void testCalculateStatus_AllValid() {
        JSObject status = implementation.calculateStatus(true, true, false);
        Assert.assertTrue(status.getBool("enabled"));
        Assert.assertTrue(status.getBool("granted"));
        Assert.assertFalse(status.getBool("isMock"));
        Assert.assertTrue(status.getBool("usable"));
    }

    @Test
    public void testCalculateStatus_GpsDisabled() {
        JSObject status = implementation.calculateStatus(false, true, false);
        Assert.assertFalse(status.getBool("enabled"));
        Assert.assertTrue(status.getBool("granted"));
        Assert.assertFalse(status.getBool("isMock"));
        Assert.assertFalse(status.getBool("usable"));
    }

    @Test
    public void testCalculateStatus_PermissionDenied() {
        JSObject status = implementation.calculateStatus(true, false, false);
        Assert.assertTrue(status.getBool("enabled"));
        Assert.assertFalse(status.getBool("granted"));
        Assert.assertFalse(status.getBool("isMock"));
        Assert.assertFalse(status.getBool("usable"));
    }

    @Test
    public void testCalculateStatus_MockDetected() {
        JSObject status = implementation.calculateStatus(true, true, true);
        Assert.assertTrue(status.getBool("enabled"));
        Assert.assertTrue(status.getBool("granted"));
        Assert.assertTrue(status.getBool("isMock"));
        Assert.assertFalse(status.getBool("usable"));
    }

    @Test
    public void testIsLocationMock_NullLocation() {
        Assert.assertFalse(implementation.isLocationMock(null));
    }
}
