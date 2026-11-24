package com.termicast;

import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.net.wifi.ScanResult;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import java.util.HashSet;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import org.json.JSONArray;
import org.json.JSONObject;

import com.termicast.server.Server;

public class MainActivity extends Activity {
    private WebView wv;
    private static final int PICK_FOLDER = 42;
    private final Map<String, BluetoothDevice> foundBluetooth = new HashMap<String, BluetoothDevice>();
    private final Handler radioHandler = new Handler(Looper.getMainLooper());
    private BroadcastReceiver bluetoothReceiver;
    private boolean receiverRegistered;

    protected void onCreate(Bundle b) {
        super.onCreate(b);
        wv = new WebView(this);
        setContentView(wv);
        WebSettings s = wv.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        wv.setWebViewClient(new WebViewClient());
        wv.setWebChromeClient(new WebChromeClient());
        wv.addJavascriptInterface(new Bridge(), "TermiBridge");
        setupBluetoothReceiver();
        wv.setBackgroundColor(0xFF05060A);
        wv.loadDataWithBaseURL(null, "<body style='background:#05060a;color:#19f5c0;font-family:monospace'>sys_init...</body>", "text/html", "utf-8", null);
        startSvc();
        new Thread(new Runnable() {
            public void run() {
                for (int i = 0; i < 40; i++) {
                    Server sv = TermiService.server;
                    if (sv != null && sv.running) {
                        final String url = "http://127.0.0.1:" + sv.port + "/";
                        runOnUiThread(new Runnable() { public void run() { wv.loadUrl(url); } });
                        return;
                    }
                    try { Thread.sleep(250); } catch (InterruptedException e) { return; }
                }
                runOnUiThread(new Runnable() {
                    public void run() {
                        wv.loadDataWithBaseURL(null, "<body style='background:#05060a;color:#ff4d6d;font-family:monospace'>SYSTEM DEACTIVATED<br>server start nahi hua. App dobara kholo.</body>", "text/html", "utf-8", null);
                    }
                });
            }
        }).start();
    }

    private void startSvc() {
        try {
            Intent i = new Intent(this, TermiService.class);
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(i); else startService(i);
        } catch (Exception e) { /* UI shows deactivated card */ }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != PICK_FOLDER || resultCode != RESULT_OK || data == null || data.getData() == null) return;
        Uri tree = data.getData();
        int flags = data.getFlags() & (Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        try { getContentResolver().takePersistableUriPermission(tree, flags); } catch (Exception ignored) { }
        Set<String> folders = new HashSet<String>(getSharedPreferences("storage", MODE_PRIVATE).getStringSet("trees", new HashSet<String>()));
        folders.add(tree.toString());
        getSharedPreferences("storage", MODE_PRIVATE).edit().putStringSet("trees", folders).apply();
        if (wv != null) wv.post(new Runnable() { public void run() { wv.evaluateJavascript("window.folderMounted && window.folderMounted()", null); } });
    }

    public void onBackPressed() {
        if (wv != null && wv.canGoBack()) wv.goBack(); else super.onBackPressed();
    }

    private String[] permsFor(String name) {
        if ("radio".equals(name)) {
            if (Build.VERSION.SDK_INT >= 33) return new String[]{"android.permission.ACCESS_FINE_LOCATION", "android.permission.NEARBY_WIFI_DEVICES", "android.permission.BLUETOOTH_SCAN", "android.permission.BLUETOOTH_CONNECT"};
            if (Build.VERSION.SDK_INT >= 31) return new String[]{"android.permission.ACCESS_FINE_LOCATION", "android.permission.BLUETOOTH_SCAN", "android.permission.BLUETOOTH_CONNECT"};
            return new String[]{"android.permission.ACCESS_FINE_LOCATION", "android.permission.BLUETOOTH", "android.permission.BLUETOOTH_ADMIN"};
        }
        if ("notifications".equals(name)) {
            return Build.VERSION.SDK_INT >= 33 ? new String[]{"android.permission.POST_NOTIFICATIONS"} : new String[0];
        }
        if (Build.VERSION.SDK_INT >= 33) return new String[]{"android.permission.READ_MEDIA_VIDEO", "android.permission.READ_MEDIA_AUDIO"};
        return new String[]{"android.permission.READ_EXTERNAL_STORAGE"};
    }

    private void setupBluetoothReceiver() {
        bluetoothReceiver = new BroadcastReceiver() {
            @Override public void onReceive(Context context, Intent intent) {
                if (intent == null || !BluetoothDevice.ACTION_FOUND.equals(intent.getAction())) return;
                try {
                    BluetoothDevice device;
                    if (Build.VERSION.SDK_INT >= 33) device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice.class);
                    else device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
                    if (device != null) synchronized (foundBluetooth) { foundBluetooth.put(device.getAddress(), device); }
                } catch (SecurityException ignored) { }
            }
        };
        try {
            IntentFilter filter = new IntentFilter(BluetoothDevice.ACTION_FOUND);
            if (Build.VERSION.SDK_INT >= 33) registerReceiver(bluetoothReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
            else registerReceiver(bluetoothReceiver, filter);
            receiverRegistered = true;
        } catch (Exception ignored) { }
    }

    private boolean hasRadioPermission(String permission) {
        return Build.VERSION.SDK_INT < 23 || checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED;
    }
    private boolean hasBluetoothScanPermission() { return Build.VERSION.SDK_INT >= 31 ? hasRadioPermission("android.permission.BLUETOOTH_SCAN") : hasRadioPermission("android.permission.BLUETOOTH_ADMIN"); }
    private boolean hasBluetoothConnectPermission() { return Build.VERSION.SDK_INT >= 31 ? hasRadioPermission("android.permission.BLUETOOTH_CONNECT") : hasRadioPermission("android.permission.BLUETOOTH"); }

    @Override protected void onDestroy() {
        try { BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter(); if (adapter != null && hasBluetoothScanPermission()) adapter.cancelDiscovery(); } catch (Exception ignored) { }
        if (receiverRegistered) try { unregisterReceiver(bluetoothReceiver); } catch (Exception ignored) { }
        super.onDestroy();
    }

    public class Bridge {
        @JavascriptInterface
        public boolean hasPerm(String name) {
            try {
                for (String p : permsFor(name)) if (checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED) return false;
                return true;
            } catch (Exception e) { return false; }
        }

        @JavascriptInterface
        public void requestPerm(final String name) {
            runOnUiThread(new Runnable() {
                public void run() {
                    try {
                        String[] p = permsFor(name);
                        if (p.length > 0) requestPermissions(p, 7);
                    } catch (Exception e) { }
                }
            });
        }

        @JavascriptInterface
        public void restartServer() { startSvc(); }

        @JavascriptInterface
        public void startServer() { startSvc(); }

        @JavascriptInterface
        public void stopServer() {
            try {
                Intent i = new Intent(MainActivity.this, TermiService.class);
                i.setAction(TermiService.ACTION_STOP);
                startService(i);
            } catch (Exception ignored) { }
        }

        @JavascriptInterface
        public void openFolderPicker() {
            runOnUiThread(new Runnable() {
                public void run() {
                    try {
                        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
                        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
                        startActivityForResult(i, PICK_FOLDER);
                    } catch (Exception ignored) { }
                }
            });
        }

        @JavascriptInterface
        public boolean startRadioScan() {
            if (!hasPerm("radio")) return false;
            boolean started = false, wifiStarted = false;
            try {
                WifiManager wifi = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
                if (wifi != null) wifiStarted = wifi.startScan();
                BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
                if (adapter != null && hasBluetoothScanPermission()) {
                    if (adapter.isDiscovering()) adapter.cancelDiscovery();
                    synchronized (foundBluetooth) { foundBluetooth.clear(); }
                    started = adapter.startDiscovery();
                    radioHandler.postDelayed(new Runnable() { public void run() {
                        try { BluetoothAdapter a = BluetoothAdapter.getDefaultAdapter(); if (a != null && a.isDiscovering()) a.cancelDiscovery(); } catch (Exception ignored) { }
                    } }, 12000);
                }
                return started || wifiStarted;
            } catch (Exception e) { return false; }
        }

        @JavascriptInterface
        public void stopRadioScan() {
            try { BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter(); if (adapter != null && hasBluetoothScanPermission()) adapter.cancelDiscovery(); }
            catch (Exception ignored) { }
        }

        @JavascriptInterface
        public String radioSnapshot() {
            try {
                if (!hasPerm("radio")) return "{\"error\":\"permission_required\"}";
                JSONArray wifiItems = new JSONArray(), bluetoothItems = new JSONArray();
                WifiManager wifi = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
                if (wifi != null) for (ScanResult r : wifi.getScanResults()) {
                    JSONObject o = new JSONObject();
                    o.put("ssid", r.SSID == null || r.SSID.length() == 0 ? "Hidden network" : r.SSID);
                    o.put("bssid", r.BSSID); o.put("level", r.level); o.put("frequency", r.frequency); wifiItems.put(o);
                }
                Map<String, BluetoothDevice> all = new HashMap<String, BluetoothDevice>();
                BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
                if (adapter != null && hasBluetoothConnectPermission())
                    for (BluetoothDevice d : adapter.getBondedDevices()) all.put(d.getAddress(), d);
                synchronized (foundBluetooth) { all.putAll(foundBluetooth); }
                for (BluetoothDevice d : all.values()) {
                    JSONObject o = new JSONObject();
                    try { o.put("name", d.getName() == null ? "Bluetooth device" : d.getName()); }
                    catch (SecurityException e) { o.put("name", "Bluetooth device"); }
                    o.put("address", d.getAddress());
                    try { o.put("paired", d.getBondState() == BluetoothDevice.BOND_BONDED); }
                    catch (SecurityException e) { o.put("paired", false); }
                    bluetoothItems.put(o);
                }
                JSONObject result = new JSONObject(); result.put("wifi", wifiItems); result.put("bluetooth", bluetoothItems);
                return result.toString();
            } catch (Exception e) { return "{\"error\":\"scan_unavailable\"}"; }
        }

        @JavascriptInterface
        public boolean pairBluetooth(String address) {
            try {
                if (!hasBluetoothConnectPermission()) return false;
                BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter(); if (adapter == null) return false;
                BluetoothDevice device = adapter.getRemoteDevice(address);
                if (device.getBondState() == BluetoothDevice.BOND_BONDED) {
                    startActivity(new Intent(Settings.ACTION_BLUETOOTH_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                    return true;
                }
                return device.createBond();
            } catch (Exception e) { return false; }
        }

        @JavascriptInterface
        public void openBluetoothSettings() {
            try { startActivity(new Intent(Settings.ACTION_BLUETOOTH_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); } catch (Exception ignored) { }
        }

        @JavascriptInterface
        public void openWifi() {
            try { startActivity(new Intent(Settings.ACTION_WIFI_SETTINGS)); } catch (Exception e) { }
        }

        @JavascriptInterface
        public void openAppSettings() {
            try { startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + getPackageName()))); } catch (Exception e) { }
        }
    }
}
