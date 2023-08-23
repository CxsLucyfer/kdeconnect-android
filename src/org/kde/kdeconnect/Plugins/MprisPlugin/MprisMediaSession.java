/*
 * SPDX-FileCopyrightText: 2017 Matthijs Tijink <matthijstijink@gmail.com>
 *
 * SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL
 */

package org.kde.kdeconnect.Plugins.MprisPlugin;

import android.Manifest;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.media.AudioManager;
import android.os.Build;
import android.os.Handler;
import android.preference.PreferenceManager;
import android.service.notification.StatusBarNotification;
import android.support.v4.media.MediaMetadataCompat;
import android.support.v4.media.session.MediaSessionCompat;
import android.support.v4.media.session.PlaybackStateCompat;
import android.util.Pair;

import androidx.annotation.NonNull;
import androidx.core.app.NotificationCompat;
import androidx.core.app.TaskStackBuilder;
import androidx.core.content.ContextCompat;
import androidx.media.app.NotificationCompat.MediaStyle;

import org.kde.kdeconnect.Device;
import org.kde.kdeconnect.Helpers.NotificationHelper;
import org.kde.kdeconnect.KdeConnect;
import org.kde.kdeconnect.Plugins.NotificationsPlugin.NotificationReceiver;
import org.kde.kdeconnect.Plugins.SystemVolumePlugin.SystemVolumePlugin;
import org.kde.kdeconnect.Plugins.SystemVolumePlugin.SystemVolumeProvider;
import org.kde.kdeconnect_tp.R;

import java.util.HashSet;

/**
 * Controls the mpris media control notification
 * <p>
 * There are two parts to this:
 * - The notification (with buttons etc.)
 * - The media session (via MediaSessionCompat; for lock screen control on
 * older Android version. And in the future for lock screen album covers)
 */
public class MprisMediaSession implements
        SharedPreferences.OnSharedPreferenceChangeListener,
        NotificationReceiver.NotificationListener,
        SystemVolumeProvider.ProviderStateListener {

    private final static int MPRIS_MEDIA_NOTIFICATION_ID = 0x91b70463; // echo MprisNotification | md5sum | head -c 8
    private final static String MPRIS_MEDIA_SESSION_TAG = "org.kde.kdeconnect_tp.media_session";

    private static final MprisMediaSession instance = new MprisMediaSession();

    private boolean spotifyRunning;

    public static MprisMediaSession getInstance() {
        return instance;
    }

    public static MediaSessionCompat getMediaSession() {
        return instance.mediaSession;
    }

    //Holds the device and player displayed in the notification
    private String notificationDevice = null;
    private MprisPlugin.MprisPlayer notificationPlayer = null;
    //Holds the device ids for which we can display a notification
    private final HashSet<String> mprisDevices = new HashSet<>();

    private Context context;
    private MediaSessionCompat mediaSession;

    //Callback for control via the media session API
    private final MediaSessionCompat.Callback mediaSessionCallback = new MediaSessionCompat.Callback() {
        @Override
        public void onPlay() {
            notificationPlayer.play();
        }

        @Override
        public void onPause() {
            notificationPlayer.pause();
        }

        @Override
        public void onSkipToNext() {
            notificationPlayer.next();
        }

        @Override
        public void onSkipToPrevious() {
            notificationPlayer.previous();
        }

        @Override
        public void onStop() {
            if (notificationPlayer != null) {
                notificationPlayer.stop();
            }
        }

        @Override
        public void onSeekTo(long pos) {
            notificationPlayer.setPosition((int) pos);
        }
    };

    /**
     * Called by the mpris plugin when it wants media control notifications for its device
     * <p>
     * Can be called multiple times, once for each device
     *
     * @param context The context
     * @param plugin  The mpris plugin
     * @param device  The device id
     */
    public void onCreate(Context context, MprisPlugin plugin, String device) {
        if (mprisDevices.isEmpty()) {
            SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
            prefs.registerOnSharedPreferenceChangeListener(this);
        }
        this.context = context;
        mprisDevices.add(device);

        plugin.setPlayerListUpdatedHandler("media_notification", this::updateMediaNotification);
        plugin.setPlayerStatusUpdatedHandler("media_notification", this::updateMediaNotification);

        NotificationReceiver.RunCommand(context, service -> {

            service.addListener(MprisMediaSession.this);

            boolean serviceReady = service.isConnected();

            if (serviceReady) {
                onListenerConnected(service);
            }
        });
    }

    /**
     * Called when a device disconnects/does not want notifications anymore
     * <p>
     * Can be called multiple times, once for each device
     *
     * @param plugin  The mpris plugin
     * @param device The device id
     */
    public void onDestroy(MprisPlugin plugin, String device) {
        mprisDevices.remove(device);
        plugin.removePlayerStatusUpdatedHandler("media_notification");
        plugin.removePlayerListUpdatedHandler("media_notification");
        updateMediaNotification();

        if (mprisDevices.isEmpty()) {
            SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
            prefs.unregisterOnSharedPreferenceChangeListener(this);
        }
    }

    /**
     * Updates which device+player we're going to use in the notification
     * <p>
     * Prefers playing devices/mpris players, but tries to keep displaying the same
     * player and device, while possible.
     */
    private MprisPlugin.MprisPlayer updateCurrentPlayer() {
        Pair<Device, MprisPlugin.MprisPlayer> player = findPlayer();

        //Update the last-displayed device and player
        notificationDevice = player.first == null ? null : player.first.getDeviceId();
        notificationPlayer = player.second;
        return notificationPlayer;
    }

    private Pair<Device, MprisPlugin.MprisPlayer> findPlayer() {
        //First try the previously displayed player (if still playing) or the previous displayed device (otherwise)
        if (notificationDevice != null && mprisDevices.contains(notificationDevice)) {
            Device device = KdeConnect.getInstance().getDevice(notificationDevice);

            MprisPlugin.MprisPlayer player;
            if (notificationPlayer != null && notificationPlayer.isPlaying()) {
                player = getPlayerFromDevice(device, notificationPlayer);
            } else {
                player = getPlayerFromDevice(device, null);
            }
            if (player != null) {
                return new Pair<>(device, player);
            }
        }

        // Try a different player from another device
        for (Device otherDevice : KdeConnect.getInstance().getDevices().values()) {
            MprisPlugin.MprisPlayer player = getPlayerFromDevice(otherDevice, null);
            if (player != null) {
                return new Pair<>(otherDevice, player);
            }
        }

        //So no player is playing. Try the previously displayed player again
        //  This will succeed if it's paused:
        //  that allows pausing and subsequently resuming via the notification
        if (notificationDevice != null && mprisDevices.contains(notificationDevice)) {
            Device device = KdeConnect.getInstance().getDevice(notificationDevice);

            MprisPlugin.MprisPlayer player = getPlayerFromDevice(device, notificationPlayer);
            if (player != null) {
                return new Pair<>(device, player);
            }
        }
        return new Pair<>(null, null);
    }

    private MprisPlugin.MprisPlayer getPlayerFromDevice(Device device, MprisPlugin.MprisPlayer preferredPlayer) {
        if (device == null || !mprisDevices.contains(device.getDeviceId()))
            return null;

        MprisPlugin plugin = device.getPlugin(MprisPlugin.class);

        if (plugin == null) {
            return null;
        }

        //First try the preferred player, if supplied
        if (plugin.hasPlayer(preferredPlayer) && shouldShowPlayer(preferredPlayer)) {
            return preferredPlayer;
        }

        //Otherwise, accept any playing player
        MprisPlugin.MprisPlayer player = plugin.getPlayingPlayer();
        if (shouldShowPlayer(player)) {
            return player;
        }

        return null;
    }

    private boolean shouldShowPlayer(MprisPlugin.MprisPlayer player) {
        return player != null && !(player.isSpotify() && spotifyRunning);
    }

    private void updateRemoteDeviceVolumeControl() {
        SystemVolumePlugin plugin = KdeConnect.getInstance().getDevicePlugin(notificationDevice, SystemVolumePlugin.class);
        if (plugin == null) {
            return;
        }
        SystemVolumeProvider systemVolumeProvider = SystemVolumeProvider.fromPlugin(plugin);
        systemVolumeProvider.addStateListener(this);
    }

    /**
     * Update the media control notification
     */
    private void updateMediaNotification() {

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            int permissionResult = ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS);
            if (permissionResult != PackageManager.PERMISSION_GRANTED) {
                closeMediaNotification();
                return;
            }
        }

        //If the user disabled the media notification, do not show it
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        if (!prefs.getBoolean(context.getString(R.string.mpris_notification_key), true)) {
            closeMediaNotification();
            return;
        }

        //Make sure our information is up-to-date
        MprisPlugin.MprisPlayer currentPlayer = updateCurrentPlayer();

        Device device = KdeConnect.getInstance().getDevice(notificationDevice);
        if (device == null) {
            closeMediaNotification();
            return;
        }

        //If the player disappeared (and no other playing one found), just remove the notification
        if (currentPlayer == null) {
            closeMediaNotification();
            return;
        }

        updateRemoteDeviceVolumeControl();

        MediaMetadataCompat.Builder metadata = new MediaMetadataCompat.Builder();

        metadata.putString(MediaMetadataCompat.METADATA_KEY_TITLE, currentPlayer.getTitle());

        if (!currentPlayer.getArtist().isEmpty()) {
            metadata.putString(MediaMetadataCompat.METADATA_KEY_AUTHOR, currentPlayer.getArtist());
            metadata.putString(MediaMetadataCompat.METADATA_KEY_ARTIST, currentPlayer.getArtist());
        }
        if (!currentPlayer.getAlbum().isEmpty()) {
            metadata.putString(MediaMetadataCompat.METADATA_KEY_ALBUM, currentPlayer.getAlbum());
        }
        if (currentPlayer.getLength() > 0) {
            metadata.putLong(MediaMetadataCompat.METADATA_KEY_DURATION, currentPlayer.getLength());
        }

        Bitmap albumArt = currentPlayer.getAlbumArt();
        if (albumArt != null) {
            metadata.putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, albumArt);
        }

        PlaybackStateCompat.Builder playbackState = new PlaybackStateCompat.Builder();

        if (currentPlayer.isPlaying()) {
            playbackState.setState(PlaybackStateCompat.STATE_PLAYING, currentPlayer.getPosition(), 1.0f);
        } else {
            playbackState.setState(PlaybackStateCompat.STATE_PAUSED, currentPlayer.getPosition(), 0.0f);
        }

        //Create all actions (previous/play/pause/next)
        Intent iPlay = new Intent(context, MprisMediaNotificationReceiver.class);
        iPlay.setAction(MprisMediaNotificationReceiver.ACTION_PLAY);
        iPlay.putExtra(MprisMediaNotificationReceiver.EXTRA_DEVICE_ID, notificationDevice);
        iPlay.putExtra(MprisMediaNotificationReceiver.EXTRA_MPRIS_PLAYER, currentPlayer.getPlayerName());
        PendingIntent piPlay = PendingIntent.getBroadcast(context, 0, iPlay, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_MUTABLE);
        NotificationCompat.Action.Builder aPlay = new NotificationCompat.Action.Builder(
                R.drawable.ic_play_white, context.getString(R.string.mpris_play), piPlay);

        Intent iPause = new Intent(context, MprisMediaNotificationReceiver.class);
        iPause.setAction(MprisMediaNotificationReceiver.ACTION_PAUSE);
        iPause.putExtra(MprisMediaNotificationReceiver.EXTRA_DEVICE_ID, notificationDevice);
        iPause.putExtra(MprisMediaNotificationReceiver.EXTRA_MPRIS_PLAYER, currentPlayer.getPlayerName());
        PendingIntent piPause = PendingIntent.getBroadcast(context, 0, iPause, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_MUTABLE);
        NotificationCompat.Action.Builder aPause = new NotificationCompat.Action.Builder(
                R.drawable.ic_pause_white, context.getString(R.string.mpris_pause), piPause);

        Intent iPrevious = new Intent(context, MprisMediaNotificationReceiver.class);
        iPrevious.setAction(MprisMediaNotificationReceiver.ACTION_PREVIOUS);
        iPrevious.putExtra(MprisMediaNotificationReceiver.EXTRA_DEVICE_ID, notificationDevice);
        iPrevious.putExtra(MprisMediaNotificationReceiver.EXTRA_MPRIS_PLAYER, currentPlayer.getPlayerName());
        PendingIntent piPrevious = PendingIntent.getBroadcast(context, 0, iPrevious, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_MUTABLE);
        NotificationCompat.Action.Builder aPrevious = new NotificationCompat.Action.Builder(
                R.drawable.ic_previous_white, context.getString(R.string.mpris_previous), piPrevious);

        Intent iNext = new Intent(context, MprisMediaNotificationReceiver.class);
        iNext.setAction(MprisMediaNotificationReceiver.ACTION_NEXT);
        iNext.putExtra(MprisMediaNotificationReceiver.EXTRA_DEVICE_ID, notificationDevice);
        iNext.putExtra(MprisMediaNotificationReceiver.EXTRA_MPRIS_PLAYER, currentPlayer.getPlayerName());
        PendingIntent piNext = PendingIntent.getBroadcast(context, 0, iNext, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_MUTABLE);
        NotificationCompat.Action.Builder aNext = new NotificationCompat.Action.Builder(
                R.drawable.ic_next_white, context.getString(R.string.mpris_next), piNext);

        Intent iOpenActivity = new Intent(context, MprisActivity.class);
        iOpenActivity.putExtra("deviceId", notificationDevice);
        iOpenActivity.putExtra("player", currentPlayer.getPlayerName());

        PendingIntent piOpenActivity = TaskStackBuilder.create(context)
                .addNextIntentWithParentStack(iOpenActivity)
                .getPendingIntent(0, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_MUTABLE);

        NotificationCompat.Builder notification = new NotificationCompat.Builder(context, NotificationHelper.Channels.MEDIA_CONTROL);

        notification
                .setAutoCancel(false)
                .setContentIntent(piOpenActivity)
                .setSmallIcon(R.drawable.ic_play_white)
                .setShowWhen(false)
                .setColor(ContextCompat.getColor(context, R.color.primary))
                .setVisibility(androidx.core.app.NotificationCompat.VISIBILITY_PUBLIC)
                .setSubText(device.getName());

        notification.setContentTitle(currentPlayer.getTitle());

        //Only set the notification body text if we have an author and/or album
        if (!currentPlayer.getArtist().isEmpty() && !currentPlayer.getAlbum().isEmpty()) {
            notification.setContentText(currentPlayer.getArtist() + " - " + currentPlayer.getAlbum() + " (" + currentPlayer.getPlayerName() + ")");
        } else if (!currentPlayer.getArtist().isEmpty()) {
            notification.setContentText(currentPlayer.getArtist() + " (" + currentPlayer.getPlayerName() + ")");
        } else if (!currentPlayer.getAlbum().isEmpty()) {
            notification.setContentText(currentPlayer.getAlbum() + " (" + currentPlayer.getPlayerName() + ")");
        } else {
            notification.setContentText(currentPlayer.getPlayerName());
        }

        if (albumArt != null) {
            notification.setLargeIcon(albumArt);
        }

        if (!currentPlayer.isPlaying()) {
            Intent iCloseNotification = new Intent(context, MprisMediaNotificationReceiver.class);
            iCloseNotification.setAction(MprisMediaNotificationReceiver.ACTION_CLOSE_NOTIFICATION);
            iCloseNotification.putExtra(MprisMediaNotificationReceiver.EXTRA_DEVICE_ID, notificationDevice);
            iCloseNotification.putExtra(MprisMediaNotificationReceiver.EXTRA_MPRIS_PLAYER, currentPlayer.getPlayerName());
            PendingIntent piCloseNotification = PendingIntent.getBroadcast(context, 0, iCloseNotification, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_MUTABLE);
            notification.setDeleteIntent(piCloseNotification);
        }

        //Add media control actions
        int numActions = 0;
        long playbackActions = 0;
        if (currentPlayer.isGoPreviousAllowed()) {
            notification.addAction(aPrevious.build());
            playbackActions |= PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS;
            ++numActions;
        }
        if (currentPlayer.isPlaying() && currentPlayer.isPauseAllowed()) {
            notification.addAction(aPause.build());
            playbackActions |= PlaybackStateCompat.ACTION_PAUSE;
            ++numActions;
        }
        if (!currentPlayer.isPlaying() && currentPlayer.isPlayAllowed()) {
            notification.addAction(aPlay.build());
            playbackActions |= PlaybackStateCompat.ACTION_PLAY;
            ++numActions;
        }
        if (currentPlayer.isGoNextAllowed()) {
            notification.addAction(aNext.build());
            playbackActions |= PlaybackStateCompat.ACTION_SKIP_TO_NEXT;
            ++numActions;
        }
        // Documentation says that this was added in Lollipop (21) but it seems to cause crashes on < Pie (28)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            if (currentPlayer.isSeekAllowed()) {
                playbackActions |= PlaybackStateCompat.ACTION_SEEK_TO;
            }
        }
        playbackState.setActions(playbackActions);

        //Only allow deletion if no music is currentPlayer
        notification.setOngoing(currentPlayer.isPlaying());

        //Use the MediaStyle notification, so it feels like other media players. That also allows adding actions
        MediaStyle mediaStyle = new MediaStyle();
        if (numActions == 1) {
            mediaStyle.setShowActionsInCompactView(0);
        } else if (numActions == 2) {
            mediaStyle.setShowActionsInCompactView(0, 1);
        } else if (numActions >= 3) {
            mediaStyle.setShowActionsInCompactView(0, 1, 2);
        }
        notification.setGroup("MprisMediaSession");

        //Display the notification
        synchronized (instance) {
            if (mediaSession == null) {
                mediaSession = new MediaSessionCompat(context, MPRIS_MEDIA_SESSION_TAG);
                mediaSession.setCallback(mediaSessionCallback, new Handler(context.getMainLooper()));
                // Deprecated flags not required in Build.VERSION_CODES.O and later
                mediaSession.setFlags(MediaSessionCompat.FLAG_HANDLES_MEDIA_BUTTONS | MediaSessionCompat.FLAG_HANDLES_TRANSPORT_CONTROLS);
            }
            mediaSession.setMetadata(metadata.build());
            mediaSession.setPlaybackState(playbackState.build());
            mediaStyle.setMediaSession(mediaSession.getSessionToken());
            notification.setStyle(mediaStyle);
            mediaSession.setActive(true);
            final NotificationManager nm = ContextCompat.getSystemService(context, NotificationManager.class);
            nm.notify(MPRIS_MEDIA_NOTIFICATION_ID, notification.build());
        }
    }

    public void closeMediaNotification() {
        //Remove the notification
        NotificationManager nm = ContextCompat.getSystemService(context, NotificationManager.class);
        nm.cancel(MPRIS_MEDIA_NOTIFICATION_ID);

        //Clear the current player and media session
        notificationPlayer = null;
        synchronized (instance) {
            if (mediaSession != null) {
                mediaSession.setPlaybackState(new PlaybackStateCompat.Builder().build());
                mediaSession.setMetadata(new MediaMetadataCompat.Builder().build());
                mediaSession.setActive(false);
                mediaSession.release();
                mediaSession = null;

                SystemVolumeProvider currentProvider = SystemVolumeProvider.getCurrentProvider();
                if (currentProvider != null) {
                    currentProvider.release();
                }
            }
        }
    }

    @Override
    public void onSharedPreferenceChanged(SharedPreferences sharedPreferences, String key) {
        updateMediaNotification();
    }

    public void playerSelected(MprisPlugin.MprisPlayer player) {
        notificationPlayer = player;
        updateMediaNotification();
    }

    @Override
    public void onNotificationPosted(StatusBarNotification n) {
        if ("com.spotify.music".equals(n.getPackageName())) {
            spotifyRunning = true;
            updateMediaNotification();
        }
    }

    @Override
    public void onNotificationRemoved(StatusBarNotification n) {
        if ("com.spotify.music".equals(n.getPackageName())) {
            spotifyRunning = false;
            updateMediaNotification();
        }
    }

    @Override
    public void onListenerConnected(NotificationReceiver service) {
        try {
            for (StatusBarNotification n : service.getActiveNotifications()) {
                if ("com.spotify.music".equals(n.getPackageName())) {
                    spotifyRunning = true;
                    updateMediaNotification();
                }
            }
        } catch(SecurityException e) {
            e.printStackTrace();
        }
    }

    @Override
    public void onProviderStateChanged(@NonNull SystemVolumeProvider volumeProvider, boolean isActive) {
        if (mediaSession == null) return;

        if (isActive) {
            mediaSession.setPlaybackToRemote(volumeProvider);
        } else {
            mediaSession.setPlaybackToLocal(AudioManager.STREAM_MUSIC);
        }
    }
}
