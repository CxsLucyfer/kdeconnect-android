/*
 * SPDX-FileCopyrightText: 2015 Vineet Garg <grg.vineet@gmail.com>
 *
 * SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL
*/

package org.kde.kdeconnect;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;

import android.util.Log;

import org.json.JSONException;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.kde.kdeconnect.Helpers.DeviceHelper;
import org.mockito.Mockito;
import org.mockito.internal.util.collections.Sets;
import org.powermock.api.mockito.PowerMockito;
import org.powermock.core.classloader.annotations.PrepareForTest;
import org.powermock.modules.junit4.PowerMockRunner;

import java.security.cert.Certificate;

@RunWith(PowerMockRunner.class)
@PrepareForTest({DeviceHelper.class, Log.class})
public class NetworkPacketTest {

    @Before
    public void setUp() {
        PowerMockito.mockStatic(DeviceHelper.class);
        PowerMockito.when(DeviceHelper.getDeviceId(any())).thenReturn("123");
        PowerMockito.when(DeviceHelper.getDeviceType(any())).thenReturn(DeviceType.Phone);

        PowerMockito.mockStatic(Log.class);
    }

    @Test
    public void testNetworkPacket() throws JSONException {
        NetworkPacket np = new NetworkPacket("com.test");

        np.set("hello", "hola");
        assertEquals(np.getString("hello", "bye"), "hola");

        np.set("hello", "");
        assertEquals(np.getString("hello", "bye"), "");

        assertEquals(np.getString("hi", "bye"), "bye");

        np.set("foo", "bar");
        String serialized = np.serialize();
        NetworkPacket np2 = NetworkPacket.unserialize(serialized);

        assertEquals(np.getLong("id"), np2.getLong("id"));
        assertEquals(np.getString("type"), np2.getString("type"));
        assertEquals(np.getJSONArray("body"), np2.getJSONArray("body"));

        String json = "{\"id\":123,\"type\":\"test\",\"body\":{\"testing\":true}}";
        np2 = NetworkPacket.unserialize(json);
        assertEquals(np2.getId(), 123);
        assertTrue(np2.getBoolean("testing"));
        assertFalse(np2.getBoolean("not_testing"));
        assertTrue(np2.getBoolean("not_testing", true));

    }

    @Test
    public void testIdentity() {
        Certificate cert = Mockito.mock(Certificate.class);

        DeviceInfo deviceInfo = new DeviceInfo("myid", cert, "myname", DeviceType.Tv, 12, Sets.newSet("ASDFG"), Sets.newSet("QWERTY"));

        NetworkPacket np = deviceInfo.toIdentityPacket();

        assertEquals(np.getInt("protocolVersion"), 12);

        DeviceInfo parsed = DeviceInfo.fromIdentityPacketAndCert(np, cert);

        assertEquals(parsed.name, deviceInfo.name);
        assertEquals(parsed.id, deviceInfo.id);
        assertEquals(parsed.type, deviceInfo.type);
        assertEquals(parsed.protocolVersion, deviceInfo.protocolVersion);
        assertEquals(parsed.incomingCapabilities, deviceInfo.incomingCapabilities);
        assertEquals(parsed.outgoingCapabilities, deviceInfo.outgoingCapabilities);


    }

}
