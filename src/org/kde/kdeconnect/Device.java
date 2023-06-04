/*
 * SPDX-FileCopyrightText: 2014 Albert Vaca Cintora <albertvaka@gmail.com>
 *
 * SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL
*/

package org.kde.kdeconnect;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.Resources;
import android.graphics.drawable.Drawable;
import android.util.Base64;
import android.util.Log;

import androidx.annotation.AnyThread;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.WorkerThread;
import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;

import org.apache.commons.collections4.MultiValuedMap;
import org.apache.commons.collections4.multimap.ArrayListValuedHashMap;
import org.apache.commons.lang3.StringUtils;
import org.kde.kdeconnect.Backends.BaseLink;
import org.kde.kdeconnect.Helpers.DeviceHelper;
import org.kde.kdeconnect.Helpers.NotificationHelper;
import org.kde.kdeconnect.Helpers.SecurityHelpers.SslHelper;
import org.kde.kdeconnect.Plugins.Plugin;
import org.kde.kdeconnect.Plugins.PluginFactory;
import org.kde.kdeconnect.UserInterface.MainActivity;
import org.kde.kdeconnect.UserInterface.PairingHandler;
import org.kde.kdeconnect_tp.R;

import java.io.IOException;
import java.security.cert.Certificate;
import java.security.cert.CertificateEncodingException;
import java.security.cert.CertificateException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.Vector;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

public class Device implements BaseLink.PacketReceiver {

    private final Context context;

    private final String deviceId;
    private String name;
    public Certificate certificate;
    private int notificationId;
    private int protocolVersion;
    private DeviceType deviceType;
    PairingHandler pairingHandler;
    private final CopyOnWriteArrayList<PairingHandler.PairingCallback> pairingCallbacks = new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<BaseLink> links = new CopyOnWriteArrayList<>();
    private DevicePacketQueue packetQueue;
    private List<String> supportedPlugins = new ArrayList<>();
    private final ConcurrentHashMap<String, Plugin> plugins = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Plugin> pluginsWithoutPermissions = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Plugin> pluginsWithoutOptionalPermissions = new ConcurrentHashMap<>();
    private MultiValuedMap<String, String> pluginsByIncomingInterface = new ArrayListValuedHashMap<>();
    private final SharedPreferences settings;
    private final CopyOnWriteArrayList<PluginsChangedListener> pluginsChangedListeners = new CopyOnWriteArrayList<>();
    private Set<String> incomingCapabilities = new HashSet<>();

    public boolean supportsPacketType(String type) {
        if (incomingCapabilities == null) {
            return true;
        } else {
            return incomingCapabilities.contains(type);
        }
    }

    public interface PluginsChangedListener {
        void onPluginsChanged(@NonNull Device device);
    }

    public enum DeviceType {
        Phone,
        Tablet,
        Computer,
        Tv;

        static public DeviceType FromString(String s) {
            if ("tablet".equals(s)) return Tablet;
            if ("phone".equals(s)) return Phone;
            if ("tv".equals(s)) return Tv;
            return Computer; //Default
        }

        @NonNull
        public String toString() {
            switch (this) {
                case Tablet:
                    return "tablet";
                case Phone:
                    return "phone";
                case Tv:
                    return "tv";
                default:
                    return "desktop";
            }
        }

        public Drawable getIcon(Context context) {
            int drawableId;
            switch (this) {
                case Phone:
                    drawableId = R.drawable.ic_device_phone_32dp;
                    break;
                case Tablet:
                    drawableId = R.drawable.ic_device_tablet_32dp;
                    break;
                case Tv:
                    drawableId = R.drawable.ic_device_tv_32dp;
                    break;
                default:
                    drawableId = R.drawable.ic_device_laptop_32dp;
            }
            return ContextCompat.getDrawable(context, drawableId);
        }
    }

    // Remembered trusted device, we need to wait for a incoming Link to communicate
    Device(@NonNull Context context, @NonNull String deviceId) throws CertificateException {

        this.context = context;

        this.settings = context.getSharedPreferences(deviceId, Context.MODE_PRIVATE);
        this.pairingHandler = new PairingHandler(this, pairingCallback, PairingHandler.PairState.Paired);

        this.deviceId = deviceId;
        this.name = settings.getString("deviceName", context.getString(R.string.unknown_device));
        this.protocolVersion = 0; //We don't know it yet
        this.deviceType = DeviceType.FromString(settings.getString("deviceType", "desktop"));
        this.certificate = SslHelper.getDeviceCertificate(context, deviceId);

        Log.i("Device","Loading trusted device: " + this.name);

        //Assume every plugin is supported until addLink is called and we can get the actual list
        supportedPlugins = new Vector<>(PluginFactory.getAvailablePlugins());

        //Do not load plugins yet, the device is not present
        //reloadPluginsFromSettings();
    }

    // Device known via an incoming connection sent to us via a Link, we don't trust it yet
    Device(@NonNull Context context, @NonNull String deviceId, @NonNull Certificate certificate, @NonNull NetworkPacket identityPacket, @NonNull BaseLink dl) {
        Log.i("Device","Creating untrusted device");

        this.context = context;

        this.settings = context.getSharedPreferences(deviceId, Context.MODE_PRIVATE);
        this.pairingHandler = new PairingHandler(this, pairingCallback, PairingHandler.PairState.NotPaired);

        this.deviceId = deviceId;
        this.certificate = certificate;

        // The following properties are read from the identityPacket in addLink since they can change in future identity packets
        this.name = context.getString(R.string.unknown_device);
        this.deviceType = DeviceType.Computer;
        this.protocolVersion = 0;

        addLink(identityPacket, dl);
    }

    public String getName() {
        return StringUtils.defaultString(name, context.getString(R.string.unknown_device));
    }

    public Drawable getIcon() {
        return deviceType.getIcon(context);
    }

    public DeviceType getDeviceType() {
        return deviceType;
    }

    public String getDeviceId() {
        return deviceId;
    }

    public Context getContext() {
        return context;
    }

    //Returns 0 if the version matches, < 0 if it is older or > 0 if it is newer
    public int compareProtocolVersion() {
        return protocolVersion - DeviceHelper.ProtocolVersion;
    }


    //
    // Pairing-related functions
    //

    public boolean isPaired() {
        return pairingHandler.getState() == PairingHandler.PairState.Paired;
    }

    public boolean isPairRequested() {
        return pairingHandler.getState() == PairingHandler.PairState.Requested;
    }

    public boolean isPairRequestedByPeer() {
        return pairingHandler.getState() == PairingHandler.PairState.RequestedByPeer;
    }

    public void addPairingCallback(PairingHandler.PairingCallback callback) {
        pairingCallbacks.add(callback);
    }

    public void removePairingCallback(PairingHandler.PairingCallback callback) {
        pairingCallbacks.remove(callback);
    }

    public void requestPairing() {
        pairingHandler.requestPairing();
    }

    public void unpair() {
        pairingHandler.unpair();
    }

    /* This method is called after accepting pair request form GUI */
    public void acceptPairing() {
        Log.i("KDE/Device", "Accepted pair request started by the other device");
        pairingHandler.acceptPairing();
    }

    /* This method is called after rejecting pairing from GUI */
    public void cancelPairing() {
        Log.i("KDE/Device", "This side cancelled the pair request");
        pairingHandler.cancelPairing();
    }

    PairingHandler.PairingCallback pairingCallback = new PairingHandler.PairingCallback() {
        @Override
        public void incomingPairRequest() {
            displayPairingNotification();
            for (PairingHandler.PairingCallback cb : pairingCallbacks) {
                cb.incomingPairRequest();
            }
        }

        @Override
        public void pairingSuccessful() {
            hidePairingNotification();

            // Store current device certificate so we can check it in the future (TOFU)
            SharedPreferences.Editor editor = context.getSharedPreferences(getDeviceId(), Context.MODE_PRIVATE).edit();
            try {
                String encodedCertificate = Base64.encodeToString(certificate.getEncoded(), 0);
                editor.putString("certificate", encodedCertificate);
            } catch(CertificateEncodingException e) {
                throw new RuntimeException(e);
            }
            editor.putString("deviceName", name);
            editor.putString("deviceType", deviceType.toString());
            editor.apply();

            // Store as trusted device
            SharedPreferences preferences = context.getSharedPreferences("trusted_devices", Context.MODE_PRIVATE);
            preferences.edit().putBoolean(deviceId, true).apply();

            reloadPluginsFromSettings();

            for (PairingHandler.PairingCallback cb : pairingCallbacks) {
                cb.pairingSuccessful();
            }
        }

        @Override
        public void pairingFailed(String error) {
            hidePairingNotification();
            for (PairingHandler.PairingCallback cb : pairingCallbacks) {
                cb.pairingFailed(error);
            }
        }

        @Override
        public void unpaired() {
            SharedPreferences preferences = context.getSharedPreferences("trusted_devices", Context.MODE_PRIVATE);
            preferences.edit().remove(deviceId).apply();

            SharedPreferences devicePreferences = context.getSharedPreferences(deviceId, Context.MODE_PRIVATE);
            devicePreferences.edit().clear().apply();

            for (PairingHandler.PairingCallback cb : pairingCallbacks) {
                cb.unpaired();
            }

            reloadPluginsFromSettings();
        }
    };

    //
    // Notification related methods used during pairing
    //

    public void displayPairingNotification() {

        hidePairingNotification();

        notificationId = (int) System.currentTimeMillis();

        Intent intent = new Intent(getContext(), MainActivity.class);
        intent.putExtra(MainActivity.EXTRA_DEVICE_ID, getDeviceId());
        intent.putExtra(MainActivity.PAIR_REQUEST_STATUS, MainActivity.PAIRING_PENDING);
        PendingIntent pendingIntent = PendingIntent.getActivity(getContext(), 1, intent, PendingIntent.FLAG_CANCEL_CURRENT | PendingIntent.FLAG_MUTABLE);

        Intent acceptIntent = new Intent(getContext(), MainActivity.class);
        Intent rejectIntent = new Intent(getContext(), MainActivity.class);

        acceptIntent.putExtra(MainActivity.EXTRA_DEVICE_ID, getDeviceId());
        acceptIntent.putExtra(MainActivity.PAIR_REQUEST_STATUS, MainActivity.PAIRING_ACCEPTED);

        rejectIntent.putExtra(MainActivity.EXTRA_DEVICE_ID, getDeviceId());
        rejectIntent.putExtra(MainActivity.PAIR_REQUEST_STATUS, MainActivity.PAIRING_REJECTED);

        PendingIntent acceptedPendingIntent = PendingIntent.getActivity(getContext(), 2, acceptIntent, PendingIntent.FLAG_ONE_SHOT | PendingIntent.FLAG_MUTABLE);
        PendingIntent rejectedPendingIntent = PendingIntent.getActivity(getContext(), 4, rejectIntent, PendingIntent.FLAG_ONE_SHOT | PendingIntent.FLAG_MUTABLE);

        Resources res = getContext().getResources();

        final NotificationManager notificationManager = ContextCompat.getSystemService(getContext(), NotificationManager.class);

        String verificationKeyShort = SslHelper.getVerificationKey(SslHelper.certificate, certificate).substring(8);

        Notification noti = new NotificationCompat.Builder(getContext(), NotificationHelper.Channels.DEFAULT)
                .setContentTitle(res.getString(R.string.pairing_request_from, getName()))
                .setContentText(res.getString(R.string.pairing_verification_code, verificationKeyShort))
                .setTicker(res.getString(R.string.pair_requested))
                .setSmallIcon(R.drawable.ic_notification)
                .setContentIntent(pendingIntent)
                .addAction(R.drawable.ic_accept_pairing_24dp, res.getString(R.string.pairing_accept), acceptedPendingIntent)
                .addAction(R.drawable.ic_reject_pairing_24dp, res.getString(R.string.pairing_reject), rejectedPendingIntent)
                .setAutoCancel(true)
                .setDefaults(Notification.DEFAULT_ALL)
                .build();

        NotificationHelper.notifyCompat(notificationManager, notificationId, noti);
    }

    public void hidePairingNotification() {
        final NotificationManager notificationManager = ContextCompat.getSystemService(getContext(),
                NotificationManager.class);
        notificationManager.cancel(notificationId);
    }

    //
    // Link-related functions
    //

    public boolean isReachable() {
        return !links.isEmpty();
    }

    public void addLink(NetworkPacket identityPacket, BaseLink link) {
        if (links.isEmpty()) {
            packetQueue = new DevicePacketQueue(this);
        }
        //FilesHelper.LogOpenFileCount();
        links.add(link);
        link.addPacketReceiver(this);

        this.protocolVersion = identityPacket.getInt("protocolVersion");

        if (identityPacket.has("deviceName")) {
            this.name = identityPacket.getString("deviceName", this.name);
            SharedPreferences.Editor editor = settings.edit();
            editor.putString("deviceName", this.name);
            editor.apply();
        }

        if (identityPacket.has("deviceType")) {
            this.deviceType = DeviceType.FromString(identityPacket.getString("deviceType", "desktop"));
        }

        Log.i("KDE/Device", "addLink " + link.getLinkProvider().getName() + " -> " + getName() + " active links: " + links.size());

        Set<String> outgoingCapabilities = identityPacket.getStringSet("outgoingCapabilities", null);
        Set<String> incomingCapabilities = identityPacket.getStringSet("incomingCapabilities", null);

        if (incomingCapabilities != null && outgoingCapabilities != null) {
            supportedPlugins = new Vector<>(PluginFactory.pluginsForCapabilities(incomingCapabilities, outgoingCapabilities));
        } else {
            supportedPlugins = new Vector<>(PluginFactory.getAvailablePlugins());
        }
        this.incomingCapabilities = incomingCapabilities;

        reloadPluginsFromSettings();

    }

    public void removeLink(BaseLink link) {
        //FilesHelper.LogOpenFileCount();

        link.removePacketReceiver(this);
        links.remove(link);
        Log.i("KDE/Device", "removeLink: " + link.getLinkProvider().getName() + " -> " + getName() + " active links: " + links.size());
        if (links.isEmpty()) {
            reloadPluginsFromSettings();
            if (packetQueue != null) {
                packetQueue.disconnected();
                packetQueue = null;
            }
        }
    }

    @Override
    public void onPacketReceived(@NonNull NetworkPacket np) {

        DeviceStats.countReceived(getDeviceId(), np.getType());

        if (NetworkPacket.PACKET_TYPE_PAIR.equals(np.getType())) {
            Log.i("KDE/Device", "Pair packet");
            pairingHandler.packetReceived(np);
        } else if (isPaired()) {
            // pluginsByIncomingInterface may not be built yet
            if(pluginsByIncomingInterface.isEmpty()) {
                reloadPluginsFromSettings();
            }

            Collection<String> targetPlugins = pluginsByIncomingInterface.get(np.getType());
            if (!targetPlugins.isEmpty()) { // When a key doesn't exist the multivaluemap returns an empty collection, so we don't need to check for null
                for (String pluginKey : targetPlugins) {
                    Plugin plugin = plugins.get(pluginKey);
                    try {
                        plugin.onPacketReceived(np);
                    } catch (Exception e) {
                        Log.e("KDE/Device", "Exception in " + plugin.getPluginKey() + "'s onPacketReceived()", e);
                        //try { Log.e("KDE/Device", "NetworkPacket:" + np.serialize()); } catch (Exception _) { }
                    }
                }
            } else {
                Log.w("Device", "Ignoring packet with type " + np.getType() + " because no plugin can handle it");
            }
        } else {

            //Log.e("KDE/onPacketReceived","Device not paired, will pass packet to unpairedPacketListeners");

            // If it is pair packet, it should be captured by "if" at start
            // If not and device is paired, it should be captured by isPaired
            // Else unpair, this handles the situation when one device unpairs, but other dont know like unpairing when wi-fi is off

            unpair();

            // The following code is NOT USED. It adds support for receiving packets from not trusted devices, but as of March 2023 no plugin implements "onUnpairedDevicePacketReceived".
            Collection<String> targetPlugins = pluginsByIncomingInterface.get(np.getType());
            if (!targetPlugins.isEmpty()) {
                for (String pluginKey : targetPlugins) {
                    Plugin plugin = plugins.get(pluginKey);
                    try {
                        plugin.onUnpairedDevicePacketReceived(np);
                    } catch (Exception e) {
                        Log.e("KDE/Device", "Exception in " + plugin.getDisplayName() + "'s onPacketReceived() in unPairedPacketListeners", e);
                    }
                }
            } else {
                Log.e("Device", "Ignoring packet with type " + np.getType() + " because no plugin can handle it");
            }
        }
    }

    public static abstract class SendPacketStatusCallback {
        public abstract void onSuccess();

        public abstract void onFailure(Throwable e);

        public void onPayloadProgressChanged(int percent) {
        }
    }

    private final SendPacketStatusCallback defaultCallback = new SendPacketStatusCallback() {
        @Override
        public void onSuccess() {
        }

        @Override
        public void onFailure(Throwable e) {
            Log.e("KDE/sendPacket", "Exception", e);
        }
    };

    @AnyThread
    public void sendPacket(@NonNull NetworkPacket np) {
        sendPacket(np, -1, defaultCallback);
    }

    @AnyThread
    public void sendPacket(@NonNull NetworkPacket np, int replaceID) {
        sendPacket(np, replaceID, defaultCallback);
    }

    @WorkerThread
    public boolean sendPacketBlocking(@NonNull NetworkPacket np) {
        return sendPacketBlocking(np, defaultCallback);
    }

    @AnyThread
    public void sendPacket(@NonNull final NetworkPacket np, @NonNull final SendPacketStatusCallback callback) {
        sendPacket(np, -1, callback);
    }

    /**
     * Send a packet to the device asynchronously
     * @param np The packet
     * @param replaceID If positive, replaces all unsent packets with the same replaceID
     * @param callback A callback for success/failure
     */
    @AnyThread
    public void sendPacket(@NonNull final NetworkPacket np, int replaceID, @NonNull final SendPacketStatusCallback callback) {
        if (packetQueue == null) {
            callback.onFailure(new Exception("Device disconnected!"));
        } else {
            packetQueue.addPacket(np, replaceID, callback);
        }
    }

    /**
     * Check if we still have an unsent packet in the queue with the given ID.
     * If so, remove it from the queue and return it
     * @param replaceID The replace ID (must be positive)
     * @return The found packet, or null
     */
    public NetworkPacket getAndRemoveUnsentPacket(int replaceID) {
        if (packetQueue == null) {
            return null;
        } else {
            return packetQueue.getAndRemoveUnsentPacket(replaceID);
        }
    }

    @WorkerThread
    public boolean sendPacketBlocking(@NonNull final NetworkPacket np, @NonNull final SendPacketStatusCallback callback) {
        return sendPacketBlocking(np, callback, false);
    }

    /**
     * Send {@code np} over one of this device's connected {@link #links}.
     *
     * @param np                        the packet to send
     * @param callback                  a callback that can receive realtime updates
     * @param sendPayloadFromSameThread when set to true and np contains a Payload, this function
     *                                  won't return until the Payload has been received by the
     *                                  other end, or times out after 10 seconds
     * @return true if the packet was sent ok, false otherwise
     * @see BaseLink#sendPacket(NetworkPacket, SendPacketStatusCallback, boolean)
     */
    @WorkerThread
    public boolean sendPacketBlocking(@NonNull final NetworkPacket np, @NonNull final SendPacketStatusCallback callback, boolean sendPayloadFromSameThread) {

        /*
        if (!m_outgoingCapabilities.contains(np.getType()) && !NetworkPacket.protocolPacketTypes.contains(np.getType())) {
            Log.e("Device/sendPacket", "Plugin tried to send an undeclared packet: " + np.getType());
            Log.w("Device/sendPacket", "Declared outgoing packet types: " + Arrays.toString(m_outgoingCapabilities.toArray()));
        }
        */

        boolean success = false;
        for (final BaseLink link : links) {
            if (link == null) continue;
            try {
                success = link.sendPacket(np, callback, sendPayloadFromSameThread);
            } catch (IOException e) {
                e.printStackTrace();
            }
            DeviceStats.countSent(getDeviceId(), np.getType(), success);
            if (success) break;
        }

        if (!success) {
            Log.e("KDE/sendPacket", "No device link (of " + links.size() + " available) could send the packet. Packet " + np.getType() + " to " + name + " lost!");
        }

        return success;

    }
    //
    // Plugin-related functions
    //

    @Nullable
    public <T extends Plugin> T getPlugin(Class<T> pluginClass) {
        Plugin plugin = getPlugin(Plugin.getPluginKey(pluginClass));
        return (T) plugin;
    }

    @Nullable
    public Plugin getPlugin(String pluginKey) {
        return plugins.get(pluginKey);
    }

    @Nullable
    public Plugin getPluginIncludingWithoutPermissions(String pluginKey) {
        Plugin p = plugins.get(pluginKey);
        if (p == null) {
            p = pluginsWithoutPermissions.get(pluginKey);
        }
        return p;
    }

    private synchronized boolean addPlugin(final String pluginKey) {
        Plugin existing = plugins.get(pluginKey);
        if (existing != null) {

            if (!existing.isCompatible()) {
                Log.i("KDE/addPlugin", "Minimum requirements (e.g. API level) not fulfilled " + pluginKey);
                return false;
            }

            //Log.w("KDE/addPlugin","plugin already present:" + pluginKey);
            if (existing.checkOptionalPermissions()) {
                Log.i("KDE/addPlugin", "Optional Permissions OK " + pluginKey);
                pluginsWithoutOptionalPermissions.remove(pluginKey);
            } else {
                Log.e("KDE/addPlugin", "No optional permission " + pluginKey);
                pluginsWithoutOptionalPermissions.put(pluginKey, existing);
            }
            return true;
        }

        final Plugin plugin = PluginFactory.instantiatePluginForDevice(context, pluginKey, this);
        if (plugin == null) {
            Log.e("KDE/addPlugin", "could not instantiate plugin: " + pluginKey);
            return false;
        }

        if (!plugin.isCompatible()) {
            Log.i("KDE/addPlugin", "Minimum requirements (e.g. API level) not fulfilled " + pluginKey);
            return false;
        }

        plugins.put(pluginKey, plugin);

        if (!plugin.checkRequiredPermissions()) {
            Log.e("KDE/addPlugin", "No permission " + pluginKey);
            plugins.remove(pluginKey);
            pluginsWithoutPermissions.put(pluginKey, plugin);
            return false;
        } else {
            Log.i("KDE/addPlugin", "Permissions OK " + pluginKey);
            pluginsWithoutPermissions.remove(pluginKey);
            if (plugin.checkOptionalPermissions()) {
                Log.i("KDE/addPlugin", "Optional Permissions OK " + pluginKey);
                pluginsWithoutOptionalPermissions.remove(pluginKey);
            } else {
                Log.e("KDE/addPlugin", "No optional permission " + pluginKey);
                pluginsWithoutOptionalPermissions.put(pluginKey, plugin);
            }
        }

        try {
            return plugin.onCreate();
        } catch (Exception e) {
            Log.e("KDE/addPlugin", "plugin failed to load " + pluginKey, e);
            return false;
        }
    }

    private synchronized boolean removePlugin(String pluginKey) {

        Plugin plugin = plugins.remove(pluginKey);

        if (plugin == null) {
            return false;
        }

        try {
            plugin.onDestroy();
            //Log.e("removePlugin","removed " + pluginKey);
        } catch (Exception e) {
            Log.e("KDE/removePlugin", "Exception calling onDestroy for plugin " + pluginKey, e);
        }

        return true;
    }

    public void setPluginEnabled(String pluginKey, boolean value) {
        settings.edit().putBoolean(pluginKey, value).apply();
        reloadPluginsFromSettings();
    }

    public boolean isPluginEnabled(String pluginKey) {
        boolean enabledByDefault = PluginFactory.getPluginInfo(pluginKey).isEnabledByDefault();
        return settings.getBoolean(pluginKey, enabledByDefault);
    }

    public void reloadPluginsFromSettings() {
        MultiValuedMap<String, String> newPluginsByIncomingInterface = new ArrayListValuedHashMap<>();

        for (String pluginKey : supportedPlugins) {
            PluginFactory.PluginInfo pluginInfo = PluginFactory.getPluginInfo(pluginKey);

            boolean pluginEnabled = false;
            boolean listenToUnpaired = pluginInfo.listenToUnpaired();
            if ((isPaired() || listenToUnpaired) && isReachable()) {
                pluginEnabled = isPluginEnabled(pluginKey);
            }

            if (pluginEnabled) {
                boolean success = addPlugin(pluginKey);
                if (success) {
                    for (String packetType : pluginInfo.getSupportedPacketTypes()) {
                        newPluginsByIncomingInterface.put(packetType, pluginKey);
                    }
                } else {
                    removePlugin(pluginKey);
                }
            } else {
                removePlugin(pluginKey);
            }
        }

        pluginsByIncomingInterface = newPluginsByIncomingInterface;

        onPluginsChanged();
    }

    public void onPluginsChanged() {
        for (PluginsChangedListener listener : pluginsChangedListeners) {
            listener.onPluginsChanged(Device.this);
        }
    }

    public ConcurrentHashMap<String, Plugin> getLoadedPlugins() {
        return plugins;
    }

    public ConcurrentHashMap<String, Plugin> getPluginsWithoutPermissions() {
        return pluginsWithoutPermissions;
    }

    public ConcurrentHashMap<String, Plugin> getPluginsWithoutOptionalPermissions() {
        return pluginsWithoutOptionalPermissions;
    }

    public void addPluginsChangedListener(PluginsChangedListener listener) {
        pluginsChangedListeners.add(listener);
    }

    public void removePluginsChangedListener(PluginsChangedListener listener) {
        pluginsChangedListeners.remove(listener);
    }

    public void disconnect() {
        for (BaseLink link : links) {
            link.disconnect();
        }
    }

    public List<String> getSupportedPlugins() {
        return supportedPlugins;
    }

}
