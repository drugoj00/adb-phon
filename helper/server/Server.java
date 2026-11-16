import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;

/**
 * «ADB-phon Server» (7.0): процесс уровня shell, который остаётся работать после выключения отладки по Wi-Fi (как Shizuku).
 * Запускается один раз через ADB: CLASSPATH=/data/local/tmp/adbphon-server.jar nohup setsid app_process / Server ПОРТ ТОКЕН &
 * Слушает только 127.0.0.1. Протокол (одно соединение = одна команда):
 *   строка 1: токен; строка 2: режим; дальше по режиму.
 *   ping            -> «pong uid=N»
 *   sh/shb          строка 3: команда в base64 (UTF-8); shb без stderr (бинарный вывод); отвечает выводом sh -c (stdout+stderr), закрывает соединение
 *   exec            строка 3: команда в base64; строка 4: N; дальше ровно N байт на стандартный ввод команды; ответ как у sh
 *   stop            -> завершает сервер
 */
public class Server {
    static String token;

    public static void main(String[] a) throws Exception {
        int port = Integer.parseInt(a[0]);
        token = a[1];
        ServerSocket ss = new ServerSocket(port, 20, InetAddress.getByName("127.0.0.1"));
        ss.setReuseAddress(true);
        while (true) {
            final Socket s = ss.accept();
            Thread t = new Thread(() -> handle(s));
            t.setDaemon(true);
            t.start();
        }
    }

    static String readLine(InputStream in) throws Exception {
        java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
        int c;
        while ((c = in.read()) >= 0 && c != '\n') bo.write(c);
        return c < 0 && bo.size() == 0 ? null : new String(bo.toByteArray(), "UTF-8");
    }

    static void handle(Socket s) {
        try {
            s.setSoTimeout(15000);
            InputStream in = s.getInputStream();
            OutputStream out = s.getOutputStream();
            String tk = readLine(in);
            if (tk == null || !tk.equals(token)) { s.close(); return; }
            String mode = readLine(in);
            if (mode == null) { s.close(); return; }
            if (mode.equals("ping")) {
                out.write(("pong uid=" + android.os.Process.myUid() + " v=2\n").getBytes("UTF-8"));
                out.flush();
            } else if (mode.equals("stop")) {
                out.write("bye\n".getBytes("UTF-8"));
                out.flush();
                s.close();
                System.exit(0);
            } else if (mode.equals("sh") || mode.equals("shb") || mode.equals("exec")) {
                String cmd64 = readLine(in);
                if (cmd64 == null) { s.close(); return; }
                String cmd = new String(android.util.Base64.decode(cmd64, 0), "UTF-8");
                s.setSoTimeout(0);
                ProcessBuilder pb = new ProcessBuilder("/system/bin/sh", "-c", mode.equals("shb") ? "exec 2>/dev/null; " + cmd : cmd);
                if (!mode.equals("shb")) pb.redirectErrorStream(true);
                final Process p = pb.start();
                final boolean feed = mode.equals("exec");
                if (feed) {
                    final long total = Long.parseLong(readLine(in).trim());
                    Thread w = new Thread(() -> {
                        try {
                            OutputStream pi = p.getOutputStream();
                            byte[] b = new byte[65536];
                            long left = total;
                            while (left > 0) {
                                int n = in.read(b, 0, (int) Math.min(b.length, left));
                                if (n < 0) break;
                                pi.write(b, 0, n);
                                left -= n;
                            }
                            pi.close();
                        } catch (Exception ignored) { }
                    });
                    w.setDaemon(true);
                    w.start();
                } else {
                    p.getOutputStream().close();
                }
                InputStream po = p.getInputStream();
                byte[] b = new byte[65536];
                int n;
                while ((n = po.read(b)) >= 0) { out.write(b, 0, n); out.flush(); }
                p.waitFor();
            }
            s.close();
        } catch (Throwable e) {
            try { s.close(); } catch (Throwable ignored) { }
        }
    }
}
