package io.freemytv;

import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Collections;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * A small web server for sending photos from a phone (PhotosActivity shows its address as a QR code). Only while that
 * screen is open, only on the home network, and only for links with this session's random key. The phone's page
 * shrinks each photo to 2560 px and sends it; it's saved next to the ones from tools/google_photos.py (Photos).
 */
final class PhotoServer {
    interface Listener { void onReceived(int received); }

    private static final int PORT = 8765;
    private static final int MAX_BYTES = 25 * 1024 * 1024;
    private static final String DATE = "\\d{4}-\\d{2}-\\d{2}";

    private final File dir;
    private final Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService pool = Executors.newFixedThreadPool(2);
    final String key;
    private ServerSocket socket;
    private int received;

    PhotoServer(File dir, Listener listener) {
        this.dir = dir;
        this.listener = listener;
        key = Http.randomText(10);
    }

    /** The address for the QR code, or null without a home-network address (no Wi-Fi or Ethernet). */
    String start() throws IOException {
        String ip = localAddress();
        if (ip == null) return null;
        socket = new ServerSocket();
        socket.setReuseAddress(true);
        try {
            socket.bind(new InetSocketAddress(PORT));
        } catch (IOException busy) {
            socket.bind(new InetSocketAddress(0));   // another app has the port: any free one
        }
        Thread accept = new Thread(() -> {
            while (!socket.isClosed()) {
                try {
                    Socket s = socket.accept();
                    pool.execute(() -> handle(s));
                } catch (IOException e) {
                    return;   // stop() closed it
                }
            }
        }, "photo-server");
        accept.setDaemon(true);
        accept.start();
        AppLog.w("your photos: receiving on port " + socket.getLocalPort());
        return "http://" + ip + ":" + socket.getLocalPort() + "/?k=" + key;
    }

    void stop() {
        try {
            if (socket != null) socket.close();
        } catch (IOException ignored) {
        }
        pool.shutdownNow();
    }

    /** The TV's IPv4 address on the home network. */
    static String localAddress() {
        try {
            for (NetworkInterface n : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!n.isUp() || n.isLoopback()) continue;
                for (InetAddress a : Collections.list(n.getInetAddresses())) {
                    if (a instanceof Inet4Address && a.isSiteLocalAddress()) return a.getHostAddress();
                }
            }
        } catch (Exception e) {
            AppLog.w("your photos: no address: " + e.getMessage());
        }
        return null;
    }

    // MARK: - HTTP

    private void handle(Socket s) {
        try (Socket sock = s) {
            sock.setSoTimeout(30_000);
            InputStream in = new BufferedInputStream(sock.getInputStream());
            OutputStream out = sock.getOutputStream();
            Http.Request r = Http.read(in);
            if (!key.equals(r.params.get("k"))) {
                Http.reply(out, 403, "text/plain", "This link has expired. Open Your Photos on the TV again.");
                return;
            }
            if (r.method.equals("GET") && r.path.equals("/")) {
                Http.reply(out, 200, "text/html; charset=utf-8", PAGE.replace("KEY", key));
            } else if (r.method.equals("POST") && r.path.equals("/upload")) {
                long length = r.length();
                if (length <= 0 || length > MAX_BYTES) {
                    Http.reply(out, 413, "application/json", "{\"ok\":false}");
                    return;
                }
                String d = r.params.get("d"), set = r.params.get("s");
                boolean ok = save(in, length, d != null && d.matches(DATE) ? d : today(), set == null ? "" : set);
                Http.reply(out, ok ? 200 : 400, "application/json", "{\"ok\":" + ok + "}");
            } else {
                Http.reply(out, 404, "text/plain", "Not found");
            }
        } catch (IOException e) {
            AppLog.w("your photos: " + e.getMessage());
        }
    }

    /** Streams the body to a temporary file, checks it's a picture, then names it "yyyy-MM-dd_<random>.<ext>". */
    private boolean save(InputStream in, long length, String date, String set) throws IOException {
        // A known set is a sub-folder (Photos.SETS); anything else goes in the folder itself.
        File target = dir;
        for (String known : Photos.SETS) if (known.equals(set)) target = new File(dir, set);
        if (!target.isDirectory() && !target.mkdirs()) return false;
        File part = new File(target, "." + Http.randomText(8) + ".part");   // hidden: Photos only lists pictures
        byte[] head = new byte[12];
        try (FileOutputStream f = new FileOutputStream(part)) {
            byte[] buf = new byte[64 * 1024];
            long left = length;
            int got = 0;
            while (left > 0) {
                int n = in.read(buf, 0, (int) Math.min(buf.length, left));
                if (n < 0) throw new IOException("the upload stopped");
                if (got < head.length) System.arraycopy(buf, 0, head, got, Math.min(n, head.length - got));
                got += n;
                f.write(buf, 0, n);
                left -= n;
            }
        } catch (IOException e) {
            //noinspection ResultOfMethodCallIgnored
            part.delete();
            throw e;
        }
        String ext = extension(head);
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(part.getPath(), bounds);
        if (ext == null || bounds.outWidth <= 0) {
            //noinspection ResultOfMethodCallIgnored
            part.delete();
            return false;
        }
        File done = new File(target, date + "_" + Http.randomText(12) + ext);
        if (!part.renameTo(done)) return false;
        final int count;
        synchronized (this) {
            count = ++received;
        }
        main.post(() -> listener.onReceived(count));
        return true;
    }

    private static String extension(byte[] h) {
        if ((h[0] & 0xFF) == 0xFF && (h[1] & 0xFF) == 0xD8) return ".jpg";
        if ((h[0] & 0xFF) == 0x89 && h[1] == 'P' && h[2] == 'N' && h[3] == 'G') return ".png";
        if (h[0] == 'R' && h[1] == 'I' && h[2] == 'F' && h[3] == 'F' && h[8] == 'W' && h[9] == 'E' && h[10] == 'B') return ".webp";
        return null;
    }

    private static String today() {
        return new java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.ROOT).format(new java.util.Date());
    }

    /** The phone's page: choose photos, each shrunk to 2560 px (as a JPEG) and sent one at a time. */
    private static final String PAGE = "<!doctype html><html><head><meta charset=utf-8>"
            + "<meta name=viewport content='width=device-width,initial-scale=1'><title>" + Ui.BRAND + "</title><style>"
            + "body{margin:0;font-family:system-ui,sans-serif;background:#141218;color:#E6E0E9;display:flex;"
            + "min-height:100vh;align-items:center;justify-content:center;text-align:center}"
            + "main{padding:32px 24px;max-width:420px}h1{font-size:26px;font-weight:600;margin:0 0 8px}"
            + "p{color:#CAC4D0;font-size:16px;line-height:1.5;margin:0 0 28px}"
            + "label{display:inline-block;background:#D0BCFF;color:#381E72;font-size:18px;font-weight:600;"
            + "padding:16px 36px;border-radius:40px}input{display:none}"
            + "#bar{height:8px;background:#4A4458;border-radius:4px;margin:28px 0 12px;overflow:hidden;display:none}"
            + "#fill{height:100%;width:0;background:#D0BCFF;transition:width .3s}#status{min-height:24px}</style></head>"
            + "<body><main><h1>Send photos to your TV</h1>"
            + "<p>Choose photos from your gallery or Google Photos. They show on the TV's home screen and screensaver.</p>"
            + "<div style='margin:0 0 20px'><div style='color:#CAC4D0;font-size:14px;margin-bottom:8px'>Add them to</div>"
            + "<select id=set style='font-size:17px;padding:12px 16px;border-radius:14px;border:0;background:#4A4458;"
            + "color:#E8DEF8;width:100%'><option value=''>Every day</option><option value=morning>Morning</option>"
            + "<option value=afternoon>Afternoon</option><option value=evening>Evening</option>"
            + "<option value=night>Night</option><option value=favourites>Favourites</option>"
            + "<option value=diwali>Diwali</option><option value=sankranti>Sankranti</option>"
            + "<option value=christmas>Christmas</option><option value=newyear>New Year</option></select></div>"
            + "<label>Choose photos<input id=pick type=file accept='image/*' multiple></label>"
            + "<div id=bar><div id=fill></div></div><div id=status></div></main><script>"
            + "const pick=document.getElementById('pick'),bar=document.getElementById('bar'),"
            + "fill=document.getElementById('fill'),status=document.getElementById('status'),"
            + "setSel=document.getElementById('set');"
            + "function day(t){const d=new Date(t||Date.now()),z=n=>String(n).padStart(2,'0');"
            + "return d.getFullYear()+'-'+z(d.getMonth()+1)+'-'+z(d.getDate())}"
            + "async function shrink(f){try{const b=await createImageBitmap(f);"
            + "const s=Math.min(1,2560/Math.max(b.width,b.height)),c=document.createElement('canvas');"
            + "c.width=Math.round(b.width*s);c.height=Math.round(b.height*s);"
            + "c.getContext('2d').drawImage(b,0,0,c.width,c.height);"
            + "return await new Promise(r=>c.toBlob(r,'image/jpeg',0.88))}catch(e){return f}}"
            + "pick.onchange=async()=>{const files=[...pick.files];if(!files.length)return;"
            + "bar.style.display='block';let sent=0,failed=0;"
            + "for(let i=0;i<files.length;i++){status.textContent='Sending '+(i+1)+' of '+files.length+'\\u2026';"
            + "try{const body=await shrink(files[i]);"
            + "const r=await fetch('/upload?k=KEY&s='+setSel.value+'&d='+day(files[i].lastModified),{method:'POST',body});"
            + "if(r.ok)sent++;else failed++}catch(e){failed++}"
            + "fill.style.width=Math.round((i+1)*100/files.length)+'%'}"
            + "status.textContent=sent+(sent==1?' photo':' photos')+' sent to your TV'"
            + "+(failed?' ('+failed+' could not be sent)':'')+'. You can choose more.';pick.value=''}"
            + "</script></body></html>";
}
