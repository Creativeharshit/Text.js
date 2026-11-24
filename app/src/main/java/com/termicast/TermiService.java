package com.termicast;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;

import com.termicast.server.Server;

public class TermiService extends Service {
    public static volatile Server server;
    private static final String CH = "termicast";
    public static final String ACTION_STOP = "com.termicast.action.STOP_SERVER";
    private final Object lifecycle = new Object();
    private boolean starting;
    private boolean closing;

    public IBinder onBind(Intent i) { return null; }

    public int onStartCommand(Intent i, int flags, int id) {
        if (i != null && ACTION_STOP.equals(i.getAction())) {
            synchronized (lifecycle) {
                closing = true;
                starting = false;
                if (server != null) { server.stop(); server = null; }
            }
            stopForeground(true);
            stopSelf();
            return START_NOT_STICKY;
        }
        try {
            show("SERVER STARTING...");
            synchronized (lifecycle) {
                closing = false;
                if ((server != null && server.running) || starting) {
                    if (server != null) show("SERVER ON " + Server.localIp() + ":" + server.port);
                    return START_STICKY;
                }
                starting = true;
            }
            new Thread(new Runnable() {
                    public void run() {
                        try {
                            Server s = new Server(new AndroidHost(TermiService.this));
                            s.start(8765);
                            synchronized (lifecycle) {
                                starting = false;
                                if (closing) s.stop();
                                else { server = s; show("SERVER ON " + Server.localIp() + ":" + s.port); }
                            }
                        } catch (Exception e) {
                            synchronized (lifecycle) { starting = false; }
                            show("SERVER ERROR: " + e.getMessage());
                            stopForeground(true);
                            stopSelf();
                        }
                    }
                }, "tc-start").start();
        } catch (Exception e) {
            // never crash the app
        }
        return START_STICKY;
    }

    private void show(String text) {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        Notification.Builder b;
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(new NotificationChannel(CH, "TermiCast server", NotificationManager.IMPORTANCE_LOW));
            b = new Notification.Builder(this, CH);
        } else {
            b = new Notification.Builder(this);
        }
        Intent open = new Intent(this, MainActivity.class);
        int fl = Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0;
        b.setContentTitle("TermiCast").setContentText(text).setSmallIcon(android.R.drawable.ic_media_play)
                .setOngoing(true).setContentIntent(PendingIntent.getActivity(this, 0, open, fl));
        Notification n = b.build();
        if (Build.VERSION.SDK_INT >= 34) startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        else startForeground(1, n);
    }

    public void onDestroy() {
        synchronized (lifecycle) {
            closing = true;
            starting = false;
            if (server != null) { server.stop(); server = null; }
        }
        super.onDestroy();
    }
}
