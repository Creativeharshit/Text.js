import com.termicast.server.Server;
import java.io.*;
import java.util.*;

/** PC test runner: java DevMain <port> <mediaDir> <assetsDir> */
public class DevMain {
    public static void main(String[] a) throws Exception {
        final File media = new File(a.length > 1 ? a[1] : "media");
        final File assets = new File(a.length > 2 ? a[2] : "app/src/main/assets");
        Server s = new Server(new Server.Host() {
            public InputStream asset(String p) throws IOException {
                File f = new File(assets, p);
                return f.isFile() ? new FileInputStream(f) : null;
            }
            public List<Server.Media> scan() {
                List<Server.Media> l = new ArrayList<Server.Media>();
                File[] fs = media.listFiles();
                if (fs != null) for (File f : fs) {
                    String n = f.getName().toLowerCase();
                    boolean v = n.endsWith(".mp4") || n.endsWith(".mkv"), au = n.endsWith(".mp3"), sub = n.endsWith(".srt") || n.endsWith(".vtt");
                    if (!v && !au && !sub) continue;
                    Server.Media m = new Server.Media();
                    m.path = f.getAbsolutePath(); m.name = f.getName(); m.type = v ? "video" : au ? "audio" : "subtitle"; m.size = f.length(); m.dur = 14000;
                    l.add(m);
                }
                return l;
            }
            public String deviceJson() { return "{\"model\":\"DevPC\",\"android\":\"n/a\"}"; }
        });
        s.start(a.length > 0 ? Integer.parseInt(a[0]) : 8765);
        System.out.println("PORT " + s.port + " PIN " + s.pin + " IP " + Server.localIp());
        Thread.sleep(Long.MAX_VALUE);
    }
}
