import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;

/** Cozy Cluster backend. JDK only. Requests arrive form-encoded from the Python front end. */
public class Server {
    static final int MAX_BODY = 2_500_000;
    static final Object LOCK = new Object();
    static final List<Map<String, String>> homes = new ArrayList<>();
    static final List<Map<String, String>> bookings = new ArrayList<>();
    static Path homesFile, bookingsFile;

    static class HttpError extends RuntimeException {
        final int code;
        HttpError(int code, String msg) { super(msg); this.code = code; }
    }

    public static void main(String[] args) throws Exception {
        int port = Integer.parseInt(env("BACKEND_PORT", "8081"));
        Path dir = Paths.get(env("DATA_DIR", "./data"));
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            dir = Paths.get(System.getProperty("java.io.tmpdir"), "cozy-cluster");
            Files.createDirectories(dir);
            System.err.println("DATA_DIR not writable, using " + dir);
        }
        homesFile = dir.resolve("homes.txt");
        bookingsFile = dir.resolve("bookings.txt");
        load(homesFile, homes, Server::validHome);
        load(bookingsFile, bookings, Server::validBooking);
        if (homes.isEmpty()) {
            seed(mk("Snowed-in A-frame", "Lake Tahoe, CA", 189, 6, "Wood stove, bunk loft and a hot tub under the pines.", "Maya"));
            seed(mk("Brownstone with a record player", "Brooklyn, NY", 142, 4, "Walk to the tree lighting. Big sofa, bigger kitchen.", "Dev"));
            seed(mk("Beach bungalow, warm December", "Tybee Island, GA", 120, 5, "String lights on the porch and bikes for everyone.", "Rosa"));
        }
        HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), port), 0);
        server.createContext("/", ex -> {
            try {
                route(ex);
            } catch (HttpError e) {
                safeSend(ex, e.code, "{\"error\":\"" + esc(e.getMessage()) + "\"}");
            } catch (Exception e) {
                e.printStackTrace();
                safeSend(ex, 500, "{\"error\":\"Server error, please try again\"}");
            } finally {
                ex.close();
            }
        });
        server.setExecutor(Executors.newFixedThreadPool(8));
        server.start();
        System.out.println("Cozy Cluster backend on 127.0.0.1:" + port + ", data in " + dir);
    }

    static void route(HttpExchange ex) throws IOException {
        String path = ex.getRequestURI().getPath();
        String m = ex.getRequestMethod();
        if (path.equals("/health")) { send(ex, 200, "{\"ok\":true}"); return; }
        if (path.equals("/api/homes")) {
            if (m.equals("GET")) { listHomes(ex); return; }
            if (m.equals("POST")) { addHome(ex); return; }
            throw new HttpError(405, "Method not allowed");
        }
        if (path.equals("/api/bookings")) {
            if (m.equals("POST")) { addBooking(ex); return; }
            throw new HttpError(405, "Method not allowed");
        }
        throw new HttpError(404, "Not found");
    }

    static void listHomes(HttpExchange ex) throws IOException {
        StringBuilder sb = new StringBuilder("[");
        synchronized (LOCK) {
            for (int i = homes.size() - 1; i >= 0; i--) {
                sb.append(homeJson(homes.get(i)));
                if (i > 0) sb.append(',');
            }
        }
        send(ex, 200, sb.append(']').toString());
    }

    static void addHome(HttpExchange ex) throws IOException {
        Map<String, String> f = form(ex);
        String title = text(f, "title", "Title", 80, true);
        String city = text(f, "city", "City", 60, true);
        int price = num(f, "price", "Price per night", 1, 100000);
        int guests = num(f, "guests", "Guests", 1, 50);
        String desc = text(f, "description", "Description", 1000, false);
        String host = text(f, "host", "Host name", 60, true);
        Map<String, String> h = mk(title, city, price, guests, desc, host);
        String img = f.getOrDefault("image", "").trim();
        if (!img.isEmpty()) {
            if (!img.startsWith("data:image/") || img.length() > 2_000_000 || img.contains("\"") || img.contains("<"))
                throw new HttpError(400, "That photo couldn't be used. Try a smaller JPG or PNG.");
            h.put("image", img);
        }
        synchronized (LOCK) {
            append(homesFile, h);
            homes.add(h);
        }
        send(ex, 201, homeJson(h));
    }

    static void addBooking(HttpExchange ex) throws IOException {
        Map<String, String> f = form(ex);
        String homeId = text(f, "homeId", "Home", 40, true);
        String name = text(f, "name", "Name", 80, true);
        String email = text(f, "email", "Email", 120, true);
        if (!email.matches("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")) throw new HttpError(400, "Enter a valid email address");
        LocalDate in = date(f, "checkin", "Check-in");
        LocalDate out = date(f, "checkout", "Check-out");
        if (in.isBefore(LocalDate.now().minusDays(1))) throw new HttpError(400, "Check-in can't be in the past");
        long nights = ChronoUnit.DAYS.between(in, out);
        if (nights < 1) throw new HttpError(400, "Check-out must be after check-in");
        if (nights > 60) throw new HttpError(400, "Stays are limited to 60 nights");
        int guests = num(f, "guests", "Guests", 1, 50);
        String resp;
        synchronized (LOCK) {
            Map<String, String> home = null;
            for (Map<String, String> h : homes) if (h.get("id").equals(homeId)) home = h;
            if (home == null) throw new HttpError(404, "That home is no longer listed");
            if (guests > Integer.parseInt(home.get("guests")))
                throw new HttpError(400, "This home sleeps up to " + home.get("guests") + " guests");
            for (Map<String, String> b : bookings) {
                if (b.get("homeId").equals(homeId)
                        && LocalDate.parse(b.get("in")).isBefore(out)
                        && LocalDate.parse(b.get("out")).isAfter(in))
                    throw new HttpError(409, "Those dates are already booked. Try other dates.");
            }
            long total = nights * Long.parseLong(home.get("price"));
            Map<String, String> b = new LinkedHashMap<>();
            b.put("id", UUID.randomUUID().toString().substring(0, 8).toUpperCase());
            b.put("homeId", homeId);
            b.put("name", name);
            b.put("email", email);
            b.put("in", in.toString());
            b.put("out", out.toString());
            b.put("guests", String.valueOf(guests));
            b.put("total", String.valueOf(total));
            append(bookingsFile, b);
            bookings.add(b);
            resp = "{\"id\":\"" + esc(b.get("id")) + "\",\"title\":\"" + esc(home.get("title"))
                    + "\",\"nights\":" + nights + ",\"total\":" + total + "}";
        }
        send(ex, 201, resp);
    }

    // ---------- helpers ----------
    static String env(String k, String d) {
        String v = System.getenv(k);
        return (v == null || v.isBlank()) ? d : v;
    }

    static Map<String, String> mk(String title, String city, int price, int guests, String desc, String host) {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("id", UUID.randomUUID().toString().substring(0, 8));
        m.put("title", title);
        m.put("city", city);
        m.put("price", String.valueOf(price));
        m.put("guests", String.valueOf(guests));
        m.put("description", desc);
        m.put("host", host);
        m.put("image", "");
        return m;
    }

    static void seed(Map<String, String> h) throws IOException {
        append(homesFile, h);
        homes.add(h);
    }

    static Map<String, String> form(HttpExchange ex) throws IOException {
        byte[] b = ex.getRequestBody().readNBytes(MAX_BODY + 1);
        if (b.length > MAX_BODY) throw new HttpError(413, "Upload is too large");
        return parseForm(new String(b, StandardCharsets.UTF_8));
    }

    static String text(Map<String, String> f, String key, String label, int max, boolean required) {
        String v = f.getOrDefault(key, "").trim();
        if (required && v.isEmpty()) throw new HttpError(400, label + " is required");
        if (v.length() > max) throw new HttpError(400, label + " must be " + max + " characters or fewer");
        return v;
    }

    static int num(Map<String, String> f, String key, String label, int lo, int hi) {
        try {
            int n = Integer.parseInt(f.getOrDefault(key, "").trim());
            if (n < lo || n > hi) throw new HttpError(400, label + " must be between " + lo + " and " + hi);
            return n;
        } catch (NumberFormatException e) {
            throw new HttpError(400, label + " must be a whole number");
        }
    }

    static LocalDate date(Map<String, String> f, String key, String label) {
        try {
            return LocalDate.parse(f.getOrDefault(key, "").trim());
        } catch (DateTimeParseException e) {
            throw new HttpError(400, label + " date is invalid");
        }
    }

    static Map<String, String> parseForm(String s) {
        Map<String, String> m = new LinkedHashMap<>();
        if (s == null || s.isEmpty()) return m;
        for (String p : s.split("&")) {
            if (p.isEmpty()) continue;
            int i = p.indexOf('=');
            String k = i < 0 ? p : p.substring(0, i);
            String v = i < 0 ? "" : p.substring(i + 1);
            try {
                m.put(URLDecoder.decode(k, StandardCharsets.UTF_8), URLDecoder.decode(v, StandardCharsets.UTF_8));
            } catch (IllegalArgumentException ignored) { }
        }
        return m;
    }

    static String encodeForm(Map<String, String> m) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : m.entrySet()) {
            if (sb.length() > 0) sb.append('&');
            sb.append(URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8)).append('=')
              .append(URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8));
        }
        return sb.toString();
    }

    static boolean validHome(Map<String, String> m) {
        try {
            Integer.parseInt(m.get("price"));
            Integer.parseInt(m.get("guests"));
            return m.get("id") != null && m.get("title") != null && m.get("city") != null && m.get("host") != null;
        } catch (RuntimeException e) { return false; }
    }

    static boolean validBooking(Map<String, String> m) {
        try {
            LocalDate.parse(m.get("in"));
            LocalDate.parse(m.get("out"));
            return m.get("homeId") != null;
        } catch (RuntimeException e) { return false; }
    }

    static void load(Path p, List<Map<String, String>> into, java.util.function.Predicate<Map<String, String>> ok) {
        try {
            if (!Files.exists(p)) return;
            for (String line : Files.readAllLines(p, StandardCharsets.UTF_8)) {
                if (line.isBlank()) continue;
                Map<String, String> m = parseForm(line);
                if (ok.test(m)) into.add(m);
                else System.err.println("Skipping unreadable record in " + p);
            }
        } catch (IOException e) {
            System.err.println("Could not read " + p + ": " + e);
        }
    }

    static void append(Path p, Map<String, String> m) throws IOException {
        Files.write(p, (encodeForm(m) + "\n").getBytes(StandardCharsets.UTF_8),
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    static String homeJson(Map<String, String> h) {
        return "{\"id\":\"" + esc(h.get("id")) + "\",\"title\":\"" + esc(h.get("title"))
                + "\",\"city\":\"" + esc(h.get("city")) + "\",\"price\":" + Integer.parseInt(h.get("price"))
                + ",\"guests\":" + Integer.parseInt(h.get("guests")) + ",\"description\":\"" + esc(h.getOrDefault("description", ""))
                + "\",\"host\":\"" + esc(h.get("host")) + "\",\"image\":\"" + esc(h.getOrDefault("image", "")) + "\"}";
    }

    static String esc(String s) {
        StringBuilder b = new StringBuilder();
        for (char c : s.toCharArray()) {
            switch (c) {
                case '"': b.append("\\\""); break;
                case '\\': b.append("\\\\"); break;
                case '\n': b.append("\\n"); break;
                case '\r': b.append("\\r"); break;
                case '\t': b.append("\\t"); break;
                default:
                    if (c < 0x20) b.append(String.format("\\u%04x", (int) c));
                    else b.append(c);
            }
        }
        return b.toString();
    }

    static void send(HttpExchange ex, int code, String body) throws IOException {
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        ex.getResponseHeaders().set("Cache-Control", "no-store");
        ex.sendResponseHeaders(code, b.length);
        try (OutputStream o = ex.getResponseBody()) { o.write(b); }
    }

    static void safeSend(HttpExchange ex, int code, String body) {
        try { send(ex, code, body); } catch (IOException ignored) { }
    }
}
