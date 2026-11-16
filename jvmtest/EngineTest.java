import com.marinevhf.core.*;
import com.marinevhf.dsp.*;
import java.util.*;

public class EngineTest {
    static int fails = 0;
    static void check(String n, boolean ok, String extra) { System.out.println((ok ? "[OK]   " : "[FAIL] ") + n + " " + extra); if (!ok) fails++; }
    public static void main(String[] args) throws Exception {
        final int port = Integer.parseInt(args[0]);
        final int[] voiceOpen = {0, 0};
        final Set<String> active = new TreeSet<>();
        final Set<Integer> types = new TreeSet<>();
        final List<String> nmea = Collections.synchronizedList(new ArrayList<String>());
        final int[] scanDone = {0};
        final String[] err = {null};
        final String[] lastMode = {""};
        RadioEngine.Listener l = new RadioEngine.Listener() {
            public void onReady(String i) { System.out.println("ready: " + i); }
            public void onError(String m) { err[0] = m; System.out.println("ERROR " + m); }
            public void onMode(String m) { lastMode[0] = m; }
            public void onVoice(long f, boolean open, double rssi) { voiceOpen[open ? 0 : 1]++; }
            public void onAis(char c, String[] s, Ais.Msg m, String text, int n) { types.add(m.type); if (n <= 3) System.out.println("  AIS " + c + " " + text); }
            public void onAisLevel(double a, double b) { }
            public void onScanProgress(int h, int hs, int k, int t, List<RadioEngine.ScanResult> r) { }
            public void onScanDone(List<RadioEngine.ScanResult> all) {
                scanDone[0]++;
                for (RadioEngine.ScanResult r : all) if (r.active) active.add(r.ch + (r.ship ? "с" : "б"));
            }
        };
        RadioEngine e = new RadioEngine(() -> RtlTcpClient.connect("127.0.0.1", port, 30, 200), l, null, null, nmea::add);
        e.start();
        e.tune(Channels.shipHz(16));
        e.setMode("VOICE");
        Thread.sleep(3000);
        check("голос: канал 16, шумоподавитель открывается и закрывается", voiceOpen[0] > 3 && voiceOpen[1] > 3, Arrays.toString(voiceOpen));
        // обзор
        e.setMode("SCAN");
        long t0 = System.currentTimeMillis();
        while (scanDone[0] < 12 && System.currentTimeMillis() - t0 < 60000) Thread.sleep(100);
        System.out.println("  циклов обзора: " + scanDone[0] + ", найдено: " + active);
        check("обзор находит 16с, 12с, 6с и береговой 22б", active.containsAll(Arrays.asList("16с", "12с", "6с", "22б")), active.toString());
        check("обзор без лишних", active.size() == 4, "");
        // АИС
        e.setMode("AIS");
        Thread.sleep(6000);
        check("АИС: типы 1, 5, 18, строки NMEA", types.equals(new TreeSet<>(Arrays.asList(1, 5, 18))) && nmea.size() >= 6, types + " строк NMEA: " + nmea.size());
        // перестройка на другой канал
        voiceOpen[0] = voiceOpen[1] = 0;
        e.tune(Channels.shipHz(12));
        e.setMode("VOICE");
        Thread.sleep(4000);
        check("голос на канале 12 (слабый сигнал) после перестройки", voiceOpen[0] > 0, Arrays.toString(voiceOpen));
        check("нет ошибок", err[0] == null, "" + err[0]);
        e.shutdown();
        Thread.sleep(300);
        System.out.println(fails == 0 ? "ИТОГ: всё в порядке" : "ИТОГ: провалено " + fails);
        System.exit(fails == 0 ? 0 : 1);
    }
}
