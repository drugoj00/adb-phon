import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.RandomAccessFile;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;

/**
 * ТЕСТОВАЯ замена uinput-помощника для Android 10 (утилиты uinput там нет): слушает 127.0.0.1:38555 (как vpad_helper.sh),
 * принимает строки JSON приложения ({"command":"inject","events":[тип,код,значение,...]}) и превращает их в отчёты
 * виртуального HID-геймпада через /dev/uhid (запись без ioctl). Запуск от shell через app_process.
 */
public class VpadUhid {
    /** Версия помощника: приложение сверяет её с файлом /data/local/tmp/vpad.ver и перезапускает старого помощника. */
    static final int VERSION = 2;
    static RandomAccessFile uhid;
    static final Object LOCK = new Object();
    // 15 кнопок (usage 1..15 -> BTN_A.. в ядре), X Y Rx Ry int16, Z Rz uint8, hat 4 бита
    static final int[] RD = {
        0x05,0x01, 0x09,0x05, 0xA1,0x01,
        0x05,0x09, 0x19,0x01, 0x29,0x0F, 0x15,0x00, 0x25,0x01, 0x75,0x01, 0x95,0x0F, 0x81,0x02,
        0x75,0x01, 0x95,0x01, 0x81,0x03,
        0x05,0x01, 0x09,0x30, 0x09,0x31, 0x09,0x33, 0x09,0x34, 0x16,0x00,0x80, 0x26,0xFF,0x7F, 0x75,0x10, 0x95,0x04, 0x81,0x02,
        0x09,0x32, 0x09,0x35, 0x15,0x00, 0x26,0xFF,0x00, 0x75,0x08, 0x95,0x02, 0x81,0x02,
        0x09,0x39, 0x15,0x01, 0x25,0x08, 0x35,0x00, 0x46,0x3B,0x01, 0x65,0x14, 0x75,0x04, 0x95,0x01, 0x81,0x42,
        0x75,0x04, 0x95,0x01, 0x81,0x03,
        0xC0
    };
    static int buttons = 0;
    static int[] ax = new int[4];   // X Y Rx Ry
    static int z = 0, rz = 0, hx = 0, hy = 0;
    static long events = 0;

    static void ev(int type, byte[] payload) throws Exception {
        byte[] b = new byte[4 + payload.length];
        b[0] = (byte) type;
        System.arraycopy(payload, 0, b, 4, payload.length);
        synchronized (LOCK) { uhid.write(b); }
    }

    static void create() throws Exception {
        byte[] p = new byte[276 + RD.length];
        byte[] name = "Xbox 360 Controller".getBytes("UTF-8");
        System.arraycopy(name, 0, p, 0, name.length);
        p[256] = (byte) RD.length; p[257] = (byte) (RD.length >> 8);
        p[258] = 3;                     // BUS_USB
        p[260] = 0x5E; p[261] = 0x04;   // vendor 0x045E
        p[264] = (byte) 0x8E; p[265] = 0x02;   // product 0x028E
        for (int i = 0; i < RD.length; i++) p[276 + i] = (byte) RD[i];
        ev(11, p);                      // UHID_CREATE2
    }

    static void flush() throws Exception {
        byte[] p = new byte[2 + 13];
        p[0] = 13; p[1] = 0;
        p[2] = (byte) (buttons & 0xFF); p[3] = (byte) ((buttons >> 8) & 0x7F);
        for (int i = 0; i < 4; i++) { p[4 + 2 * i] = (byte) (ax[i] & 0xFF); p[5 + 2 * i] = (byte) ((ax[i] >> 8) & 0xFF); }
        p[12] = (byte) z; p[13] = (byte) rz;
        int hat;
        if (hx == 0 && hy == 0) hat = 0;
        else if (hx == 0 && hy < 0) hat = 1;
        else if (hx > 0 && hy < 0) hat = 2;
        else if (hx > 0 && hy == 0) hat = 3;
        else if (hx > 0 && hy > 0) hat = 4;
        else if (hx == 0 && hy > 0) hat = 5;
        else if (hx < 0 && hy > 0) hat = 6;
        else if (hx < 0 && hy == 0) hat = 7;
        else hat = 8;
        p[14] = (byte) hat;
        ev(12, p);                      // UHID_INPUT2
    }

    static void apply(int type, int code, int val) {
        if (type == 1) {
            int bit = code - 304;
            if (bit < 0 || bit > 14) return;
            if (val != 0) buttons |= 1 << bit; else buttons &= ~(1 << bit);
        } else if (type == 3) {
            switch (code) {
                case 0: ax[0] = val; break;
                case 1: ax[1] = val; break;
                case 3: ax[2] = val; break;   // правый стик X (RX)
                case 4: ax[3] = val; break;   // правый стик Y (RY)
                case 2: z = val & 0xFF; break;
                case 5: rz = val & 0xFF; break;
                case 16: hx = val; break;
                case 17: hy = val; break;
            }
        }
    }

    /** Команда quit: убрать виртуальный контроллер, удалить свои файлы и выйти (приложение может сделать это и без ADB). */
    static void quit() {
        try { byte[] d = new byte[4]; d[0] = 1; synchronized (LOCK) { uhid.write(d); } } catch (Throwable ignored) { }     // UHID_DESTROY
        String[] f = { "/data/local/tmp/vpad.pid", "/data/local/tmp/vpad.ver", "/data/local/tmp/vpad_helper.log", "/data/local/tmp/vpad_uhid.jar" };
        for (String n : f) { try { new java.io.File(n).delete(); } catch (Throwable ignored) { } }
        System.exit(0);
    }

    static void handle(String line) throws Exception {
        if (line.contains("\"quit\"")) { quit(); return; }
        int i = line.indexOf("\"events\":[");
        if (i < 0) return;
        int j = line.indexOf(']', i);
        String[] s = line.substring(i + 10, j).split(",");
        int n = 0;
        for (int k = 0; k + 2 < s.length; k += 3) {
            int t = Integer.parseInt(s[k].trim()), c = Integer.parseInt(s[k + 1].trim()), v = Integer.parseInt(s[k + 2].trim());
            if (t == 0) { flush(); n++; } else apply(t, c, v);
        }
        events++;
        if (n == 0) flush();
    }

    public static void main(String[] a) throws Exception {
        uhid = new RandomAccessFile("/dev/uhid", "rw");
        Thread drain = new Thread(new Runnable() { public void run() {
            byte[] buf = new byte[4380];
            try { while (uhid.read(buf) >= 0) { } } catch (Throwable ignored) { }
        } });
        drain.setDaemon(true); drain.start();
        create();
        ServerSocket ss = new ServerSocket(38555, 8, InetAddress.getByName("127.0.0.1"));
        System.out.println("ready uhid v" + VERSION); System.out.flush();
        while (true) {
            final Socket c = ss.accept();
            c.setTcpNoDelay(true);
            // приветствие: приложение узнаёт, что это помощник на uhid, и его версию (даже без ADB)
            try { c.getOutputStream().write(("uhid " + VERSION + "\n").getBytes("UTF-8")); c.getOutputStream().flush(); } catch (Exception e) { }
            new Thread(new Runnable() { public void run() {
                try {
                    BufferedReader r = new BufferedReader(new InputStreamReader(c.getInputStream()));
                    String l;
                    while ((l = r.readLine()) != null) { try { handle(l); } catch (Exception e) { } }
                } catch (Exception e) { }
            } }).start();
        }
    }
}
