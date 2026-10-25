import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.RandomAccessFile;

/**
 * Помощник ADB-phon: создаёт на устройстве виртуальную мышь (UHID) и по командам из stdin двигает курсор.
 * Запускается от имени shell через app_process; команды: "m dx dy", "b маска", "w шаг", "q".
 */
public class Helper implements Runnable {
    private static RandomAccessFile uhid;

    // Дескриптор HID: мышь, 3 кнопки, относительные X/Y и колесо
    private static final int[] REPORT = {
            0x05, 0x01, 0x09, 0x02, 0xA1, 0x01, 0x09, 0x01, 0xA1, 0x00, 0x05, 0x09, 0x19, 0x01, 0x29, 0x03,
            0x15, 0x00, 0x25, 0x01, 0x95, 0x03, 0x75, 0x01, 0x81, 0x02, 0x95, 0x01, 0x75, 0x05, 0x81, 0x03,
            0x05, 0x01, 0x09, 0x30, 0x09, 0x31, 0x09, 0x38, 0x15, 0x81, 0x25, 0x7F, 0x75, 0x08, 0x95, 0x03,
            0x81, 0x06, 0xC0, 0xC0
    };

    private static void event(int type, byte[] payload) throws IOException {
        byte[] b = new byte[4 + payload.length];
        b[0] = (byte) type;
        System.arraycopy(payload, 0, b, 4, payload.length);
        synchronized (Helper.class) {
            uhid.write(b);
        }
    }

    private static void create() throws IOException {
        byte[] p = new byte[276 + REPORT.length];
        byte[] name = "ADB-phon Air Mouse".getBytes("UTF-8");
        System.arraycopy(name, 0, p, 0, name.length);
        p[256] = (byte) REPORT.length;
        p[257] = (byte) (REPORT.length >> 8);
        p[258] = 3; // BUS_USB
        p[260] = (byte) 0xD1;
        p[261] = (byte) 0x18; // vendor 0x18D1
        p[264] = 0x11;
        p[265] = 0x4E; // product 0x4E11
        for (int i = 0; i < REPORT.length; i++) p[276 + i] = (byte) REPORT[i];
        event(11, p); // UHID_CREATE2
    }

    private static void report(int buttons, int dx, int dy, int wheel) throws IOException {
        byte[] p = new byte[2 + 4];
        p[0] = 4;
        p[1] = 0;
        p[2] = (byte) buttons;
        p[3] = (byte) dx;
        p[4] = (byte) dy;
        p[5] = (byte) wheel;
        event(12, p); // UHID_INPUT2
    }

    private static int buttons = 0;

    private static void move(int dx, int dy) throws IOException {
        while (dx != 0 || dy != 0) {
            int sx = Math.max(-127, Math.min(127, dx));
            int sy = Math.max(-127, Math.min(127, dy));
            report(buttons, sx, sy, 0);
            dx -= sx;
            dy -= sy;
        }
    }

    public void run() {
        byte[] buf = new byte[4380];
        try {
            while (uhid.read(buf) >= 0) { }
        } catch (Throwable ignored) { }
    }

    public static void main(String[] args) throws Exception {
        uhid = new RandomAccessFile("/dev/uhid", "rw");
        // Ядро присылает служебные события (START, OPEN и т. д.): их нужно вычитывать с того же дескриптора
        Thread drain = new Thread(new Helper());
        drain.setDaemon(true);
        drain.start();
        create();
        System.out.println("ready");
        System.out.flush();
        BufferedReader in = new BufferedReader(new InputStreamReader(System.in));
        String line;
        try {
            while ((line = in.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) continue;
                String[] t = line.split("\\s+");
                char c = t[0].charAt(0);
                if (c == 'q') break;
                if (c == 'm' && t.length >= 3) move(Integer.parseInt(t[1]), Integer.parseInt(t[2]));
                else if (c == 'b' && t.length >= 2) {
                    buttons = Integer.parseInt(t[1]) & 7;
                    report(buttons, 0, 0, 0);
                } else if (c == 'w' && t.length >= 2) report(buttons, 0, 0, Integer.parseInt(t[1]));
            }
        } finally {
            try {
                event(1, new byte[0]); // UHID_DESTROY
            } catch (Throwable ignored) { }
            uhid.close();
        }
    }
}
