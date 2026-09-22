import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class ApplyPatch {
    static String strip(StringBuilder b) {
        String s = b.toString();
        return s.endsWith("\n") ? s.substring(0, s.length() - 1) : s;
    }

    public static void main(String[] a) throws Exception {
        List<String> lines = Files.readAllLines(Paths.get(a[0]), StandardCharsets.UTF_8);
        List<String[]> ops = new ArrayList<>();
        String file = null;
        StringBuilder oldB = null;
        StringBuilder newB = null;
        int mode = 0;
        for (String l : lines) {
            if (l.startsWith("@@FILE ")) {
                file = l.substring(7).trim();
            } else if (l.equals("@@OLD")) {
                oldB = new StringBuilder();
                mode = 1;
            } else if (l.equals("@@NEW")) {
                newB = new StringBuilder();
                mode = 2;
            } else if (l.equals("@@END")) {
                ops.add(new String[] { file, strip(oldB), strip(newB) });
                mode = 0;
            } else if (mode == 1) {
                oldB.append(l).append("\n");
            } else if (mode == 2) {
                newB.append(l).append("\n");
            }
        }
        Map<String, String> contents = new LinkedHashMap<>();
        int n = 0;
        for (String[] op : ops) {
            n++;
            String s = contents.get(op[0]);
            if (s == null) {
                s = Files.readString(Paths.get(op[0]), StandardCharsets.UTF_8);
            }
            String o = op[1];
            String rep = op[2];
            boolean crlf = false;
            int idx = s.indexOf(o);
            if (idx >= 0) {
                if (s.indexOf(o, idx + 1) >= 0) {
                    throw new IllegalStateException("op " + n + ": anchor not unique in " + op[0]);
                }
                int after = idx + o.length();
                crlf = after < s.length() && s.charAt(after) == '\r';
            } else if (o.contains("\n")) {
                String o2 = o.replace("\n", "\r\n");
                idx = s.indexOf(o2);
                if (idx < 0 || s.indexOf(o2, idx + 1) >= 0) {
                    throw new IllegalStateException("op " + n + ": anchor missing or not unique in " + op[0]);
                }
                o = o2;
                crlf = true;
            } else {
                throw new IllegalStateException("op " + n + ": anchor missing in " + op[0]);
            }
            if (crlf) {
                rep = rep.replace("\n", "\r\n");
            }
            contents.put(op[0], s.substring(0, idx) + rep + s.substring(idx + o.length()));
            System.out.println("ok op " + n + " (" + op[0] + ")");
        }
        for (Map.Entry<String, String> e : contents.entrySet()) {
            Files.writeString(Paths.get(e.getKey()), e.getValue(), StandardCharsets.UTF_8);
        }
        System.out.println("written: " + contents.keySet());
    }
}
