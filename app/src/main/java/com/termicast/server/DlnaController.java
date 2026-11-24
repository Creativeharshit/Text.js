package com.termicast.server;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.InetAddress;
import java.net.HttpURLConnection;
import java.net.MulticastSocket;
import java.net.URL;
import java.nio.charset.Charset;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/** SSDP discovery and UPnP AVTransport control for local media renderers. */
public final class DlnaController {
    private static final String ST = "urn:schemas-upnp-org:device:MediaRenderer:1";
    private static final String AVT = "urn:schemas-upnp-org:service:AVTransport:1";
    private final Map<String, Renderer> renderers = new ConcurrentHashMap<String, Renderer>();

    public String scanJson() {
        discover();
        StringBuilder b = new StringBuilder("[");
        boolean first = true;
        for (Renderer r : renderers.values()) {
            if (!first) b.append(',');
            first = false;
            b.append("{\"id\":").append(Server.js(r.id)).append(",\"name\":").append(Server.js(r.name))
                    .append(",\"address\":").append(Server.js(r.address)).append(",\"service\":\"AVTransport\"}");
        }
        return b.append(']').toString();
    }

    public boolean play(String id, String mediaUrl, String mime, String title) {
        Renderer r = renderers.get(id);
        if (r == null) return false;
        String cls = mime != null && mime.startsWith("audio/") ? "object.item.audioItem.musicTrack" : "object.item.videoItem";
        String didl = "<DIDL-Lite xmlns=\"urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/\" xmlns:dc=\"http://purl.org/dc/elements/1.1/\" xmlns:upnp=\"urn:schemas-upnp-org:metadata-1-0/upnp/\"><item id=\"0\" parentID=\"0\" restricted=\"1\"><dc:title>"
                + xml(title) + "</dc:title><upnp:class>" + cls + "</upnp:class><res protocolInfo=\"http-get:*:"
                + xml(mime == null ? "application/octet-stream" : mime) + ":*\">" + xml(mediaUrl)
                + "</res></item></DIDL-Lite>";
        try {
            action(r, "SetAVTransportURI", "<InstanceID>0</InstanceID><CurrentURI>" + xml(mediaUrl)
                    + "</CurrentURI><CurrentURIMetaData>" + xml(didl) + "</CurrentURIMetaData>");
            action(r, "Play", "<InstanceID>0</InstanceID><Speed>1</Speed>");
            return true;
        } catch (IOException e) { return false; }
    }

    public boolean control(String id, String command, String position) {
        Renderer r = renderers.get(id);
        if (r == null) return false;
        try {
            if ("play".equals(command)) action(r, "Play", "<InstanceID>0</InstanceID><Speed>1</Speed>");
            else if ("pause".equals(command)) action(r, "Pause", "<InstanceID>0</InstanceID>");
            else if ("stop".equals(command)) action(r, "Stop", "<InstanceID>0</InstanceID>");
            else if ("seek".equals(command)) action(r, "Seek", "<InstanceID>0</InstanceID><Unit>ABS_TIME</Unit><Target>" + xml(position) + "</Target>");
            else if ("rel".equals(command)) {
                String current = xmlValue(text(soap(r, "GetPositionInfo", "<InstanceID>0</InstanceID>")), "RelTime");
                if (!current.matches("[0-9]{1,4}:[0-5][0-9]:[0-5][0-9]")) return false;
                int now = timeSeconds(current);
                int target = Math.max(0, now + Integer.parseInt(position));
                action(r, "Seek", "<InstanceID>0</InstanceID><Unit>ABS_TIME</Unit><Target>" + timeString(target) + "</Target>");
            }
            else if ("volume".equals(command) && r.renderControlUrl != null)
                action(r.renderControlUrl, r.renderServiceType, "SetVolume", "<InstanceID>0</InstanceID><Channel>Master</Channel><DesiredVolume>" + Math.max(0, Math.min(100, Integer.parseInt(position))) + "</DesiredVolume>");
            else return false;
            return true;
        } catch (Exception e) { return false; }
    }

    public String stateJson(String id) {
        Renderer r = renderers.get(id);
        if (r == null) return "{\"ok\":false}";
        try {
            String transport = text(soap(r, "GetTransportInfo", "<InstanceID>0</InstanceID>"));
            String position = text(soap(r, "GetPositionInfo", "<InstanceID>0</InstanceID>"));
            return "{\"ok\":true,\"state\":" + Server.js(xmlValue(transport, "CurrentTransportState"))
                    + ",\"pos\":" + timeSeconds(xmlValue(position, "RelTime"))
                    + ",\"dur\":" + timeSeconds(xmlValue(position, "TrackDuration")) + "}";
        } catch (Exception e) { return "{\"ok\":false}"; }
    }

    private void discover() {
        renderers.clear();
        MulticastSocket socket = null;
        try {
            socket = new MulticastSocket();
            socket.setSoTimeout(250);
            socket.setTimeToLive(2);
            String req = "M-SEARCH * HTTP/1.1\r\nHOST: 239.255.255.250:1900\r\nMAN: \"ssdp:discover\"\r\nMX: 2\r\nST: " + ST + "\r\n\r\n";
            byte[] data = req.getBytes(Charset.forName("US-ASCII"));
            socket.send(new DatagramPacket(data, data.length, InetAddress.getByName("239.255.255.250"), 1900));
            long until = System.currentTimeMillis() + 2300;
            while (System.currentTimeMillis() < until) {
                byte[] buf = new byte[8192];
                DatagramPacket packet = new DatagramPacket(buf, buf.length);
                try { socket.receive(packet); } catch (java.net.SocketTimeoutException timeout) { continue; }
                String response = new String(packet.getData(), packet.getOffset(), packet.getLength(), Charset.forName("US-ASCII"));
                String location = header(response, "location");
                if (location != null) loadRenderer(location);
            }
        } catch (Exception ignored) {
            // LAN multicast may be disabled by the current network or OS.
        } finally { if (socket != null) socket.close(); }
    }

    private void loadRenderer(String location) {
        HttpURLConnection c = null;
        try {
            URL descUrl = new URL(location);
            if (!"http".equalsIgnoreCase(descUrl.getProtocol())) return;
            c = (HttpURLConnection) descUrl.openConnection();
            c.setConnectTimeout(1800); c.setReadTimeout(1800); c.setInstanceFollowRedirects(false);
            if (c.getResponseCode() < 200 || c.getResponseCode() >= 300) return;
            byte[] xml = readBounded(c.getInputStream(), 1024 * 1024);
            DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
            f.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            f.setFeature("http://xml.org/sax/features/external-general-entities", false);
            f.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            f.setExpandEntityReferences(false);
            Document d = f.newDocumentBuilder().parse(new java.io.ByteArrayInputStream(xml));
            NodeList devices = d.getElementsByTagName("device");
            for (int i = 0; i < devices.getLength(); i++) {
                Element device = (Element) devices.item(i);
                String kind = childText(device, "deviceType");
                if (kind == null || !kind.contains("MediaRenderer")) continue;
                String name = childText(device, "friendlyName");
                String udn = childText(device, "UDN");
                NodeList services = device.getElementsByTagName("service");
                Renderer r = new Renderer();
                r.id = digest(udn == null ? location : udn);
                r.name = name == null ? "UPnP Renderer" : name;
                r.address = descUrl.getHost();
                for (int j = 0; j < services.getLength(); j++) {
                    Element service = (Element) services.item(j);
                    String serviceType = childText(service, "serviceType");
                    String control = childText(service, "controlURL");
                    if (serviceType == null || control == null) continue;
                    if (serviceType.contains("AVTransport")) { r.controlUrl = new URL(descUrl, control).toString(); r.serviceType = serviceType; }
                    if (serviceType.contains("RenderingControl")) { r.renderControlUrl = new URL(descUrl, control).toString(); r.renderServiceType = serviceType; }
                }
                if (r.controlUrl != null) {
                    renderers.put(r.id, r);
                    return;
                }
            }
        } catch (Exception ignored) {
        } finally { if (c != null) c.disconnect(); }
    }

    private static void action(Renderer r, String name, String args) throws IOException {
        soap(r, name, args);
    }

    private static void action(String controlUrl, String serviceType, String name, String args) throws IOException {
        soap(controlUrl, serviceType, name, args);
    }

    private static byte[] soap(Renderer r, String name, String args) throws IOException {
        return soap(r.controlUrl, r.serviceType, name, args);
    }

    private static byte[] soap(String controlUrl, String serviceType, String name, String args) throws IOException {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(controlUrl).openConnection();
            c.setConnectTimeout(3000); c.setReadTimeout(4000); c.setRequestMethod("POST"); c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "text/xml; charset=\"utf-8\"");
            c.setRequestProperty("SOAPAction", "\"" + serviceType + "#" + name + "\"");
            byte[] body = ("<?xml version=\"1.0\" encoding=\"utf-8\"?><s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\"><s:Body><u:"
                    + name + " xmlns:u=\"" + xml(serviceType) + "\">" + args + "</u:" + name + "></s:Body></s:Envelope>").getBytes(Charset.forName("UTF-8"));
            c.setFixedLengthStreamingMode(body.length);
            c.getOutputStream().write(body);
            int status = c.getResponseCode();
            if (status < 200 || status >= 300) throw new IOException("UPnP " + name + " HTTP " + status);
            return readBounded(c.getInputStream(), 262144);
        } finally { if (c != null) c.disconnect(); }
    }

    private static Document parseSoap(byte[] bytes) throws Exception {
        DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
        f.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        f.setFeature("http://xml.org/sax/features/external-general-entities", false);
        f.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        f.setExpandEntityReferences(false);
        return f.newDocumentBuilder().parse(new java.io.ByteArrayInputStream(bytes));
    }
    private static String text(byte[] bytes) throws IOException {
        return new String(bytes, Charset.forName("UTF-8"));
    }
    private static String xmlValue(String body, String tag) {
        try {
            NodeList n = parseSoap(body.getBytes(Charset.forName("UTF-8"))).getElementsByTagName(tag);
            return n.getLength() == 0 || n.item(0).getFirstChild() == null ? "" : n.item(0).getFirstChild().getNodeValue().trim();
        } catch (Exception e) { return ""; }
    }
    private static int timeSeconds(String value) {
        try { String[] p = value.split(":"); return Integer.parseInt(p[0])*3600 + Integer.parseInt(p[1])*60 + Integer.parseInt(p[2]); }
        catch (Exception e) { return 0; }
    }
    private static String timeString(int seconds) {
        return String.format(Locale.US, "%02d:%02d:%02d", seconds/3600, seconds/60%60, seconds%60);
    }

    private static String childText(Element e, String tag) {
        NodeList n = e.getElementsByTagName(tag);
        if (n.getLength() == 0) return null;
        Node x = n.item(0).getFirstChild();
        return x == null ? null : x.getNodeValue().trim();
    }
    private static String header(String response, String name) {
        for (String line : response.split("\\r?\\n")) {
            int p = line.indexOf(':');
            if (p > 0 && name.equalsIgnoreCase(line.substring(0, p).trim())) return line.substring(p + 1).trim();
        }
        return null;
    }
    private static byte[] readBounded(java.io.InputStream in, int limit) throws IOException {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream(); byte[] b = new byte[8192]; int n, total = 0;
            while ((n = in.read(b)) >= 0) { total += n; if (total > limit) throw new IOException("device description too large"); out.write(b, 0, n); }
            return out.toByteArray();
        } finally { in.close(); }
    }
    private static String xml(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&apos;");
    }
    private static String digest(String s) {
        try {
            byte[] b = MessageDigest.getInstance("SHA-256").digest(s.getBytes(Charset.forName("UTF-8")));
            StringBuilder out = new StringBuilder(); for (byte x : b) out.append(String.format(Locale.US, "%02x", x & 255)); return out.toString();
        } catch (Exception e) { return Integer.toHexString(s.hashCode()); }
    }
    private static final class Renderer { String id, name, address, controlUrl, serviceType, renderControlUrl, renderServiceType; }
}
