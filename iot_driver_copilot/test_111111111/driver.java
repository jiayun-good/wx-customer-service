import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpHandler;

import java.io.*;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.Executors;

public class DeviceShifuDriver {

    // In-memory mock for device data points (simulate telemetry data)
    private static final List<Map<String, Object>> deviceDataPoints = Collections.synchronizedList(new ArrayList<>());
    static {
        // Populate with some default mock data
        for (int i = 1; i <= 25; i++) {
            Map<String, Object> point = new HashMap<>();
            point.put("id", i);
            point.put("temperature", 20 + i);
            point.put("humidity", 45 + i);
            point.put("timestamp", System.currentTimeMillis() - (i * 1000));
            deviceDataPoints.add(point);
        }
    }

    // Used to simulate command execution and store last command response
    private static final List<Map<String, Object>> commandHistory = Collections.synchronizedList(new ArrayList<>());

    public static void main(String[] args) throws Exception {
        String serverHost = System.getenv().getOrDefault("SERVER_HOST", "0.0.0.0");
        int serverPort = Integer.parseInt(System.getenv().getOrDefault("SERVER_PORT", "8080"));
        // Device-specific (mock) configs
        String deviceIp = System.getenv().getOrDefault("DEVICE_IP", "127.0.0.1");

        HttpServer server = HttpServer.create(new InetSocketAddress(serverHost, serverPort), 0);
        server.createContext("/points", new PointsHandler());
        server.createContext("/commands", new CommandsHandler());
        server.setExecutor(Executors.newCachedThreadPool());
        server.start();
        System.out.println("DeviceShifuDriver HTTP server started at http://" + serverHost + ":" + serverPort);
    }

    // Handler for GET /points
    static class PointsHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!exchange.getRequestMethod().equalsIgnoreCase("GET")) {
                sendResponse(exchange, 405, "Method Not Allowed", "text/plain");
                return;
            }

            // Parse query params for pagination/filter
            URI requestURI = exchange.getRequestURI();
            Map<String, String> queryParams = parseQuery(requestURI.getRawQuery());

            int page = 1;
            int limit = 10;
            try {
                if (queryParams.containsKey("page")) {
                    page = Integer.parseInt(queryParams.get("page"));
                }
                if (queryParams.containsKey("limit")) {
                    limit = Integer.parseInt(queryParams.get("limit"));
                }
            } catch (NumberFormatException e) {
                sendResponse(exchange, 400, "{\"error\":\"Invalid pagination parameters\"}", "application/json");
                return;
            }

            int total = deviceDataPoints.size();
            int fromIndex = Math.max(0, (page - 1) * limit);
            int toIndex = Math.min(fromIndex + limit, total);

            List<Map<String, Object>> pageData;
            if (fromIndex >= total) {
                pageData = Collections.emptyList();
            } else {
                pageData = deviceDataPoints.subList(fromIndex, toIndex);
            }

            Map<String, Object> response = new LinkedHashMap<>();
            response.put("page", page);
            response.put("limit", limit);
            response.put("total", total);
            response.put("data", pageData);

            String json = toJson(response);
            sendResponse(exchange, 200, json, "application/json");
        }
    }

    // Handler for POST /commands
    static class CommandsHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!exchange.getRequestMethod().equalsIgnoreCase("POST")) {
                sendResponse(exchange, 405, "Method Not Allowed", "text/plain");
                return;
            }
            Headers headers = exchange.getRequestHeaders();
            String contentType = headers.getFirst("Content-Type");
            if (contentType == null || !contentType.toLowerCase().contains("application/json")) {
                sendResponse(exchange, 400, "{\"error\":\"Content-Type must be application/json\"}", "application/json");
                return;
            }
            String body = readAll(exchange.getRequestBody());
            Map<String, Object> command;
            try {
                command = fromJson(body);
            } catch (Exception e) {
                sendResponse(exchange, 400, "{\"error\":\"Malformed JSON\"}", "application/json");
                return;
            }
            // Simulate command execution and store result
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("command", command);
            result.put("status", "success");
            result.put("executedAt", System.currentTimeMillis());
            commandHistory.add(result);

            sendResponse(exchange, 200, toJson(result), "application/json");
        }
    }

    // Helper: Parse query string
    private static Map<String, String> parseQuery(String query) throws UnsupportedEncodingException {
        Map<String, String> result = new HashMap<>();
        if (query == null || query.isEmpty()) return result;
        for (String pair : query.split("&")) {
            int idx = pair.indexOf("=");
            if (idx > 0) {
                result.put(URLDecoder.decode(pair.substring(0, idx), "UTF-8"),
                        URLDecoder.decode(pair.substring(idx + 1), "UTF-8"));
            } else {
                result.put(URLDecoder.decode(pair, "UTF-8"), "");
            }
        }
        return result;
    }

    // Helper: Read all from InputStream
    private static String readAll(InputStream is) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int len;
        while ((len = is.read(buf)) != -1) {
            baos.write(buf, 0, len);
        }
        return baos.toString(StandardCharsets.UTF_8.name());
    }

    // Helper: Send HTTP response
    private static void sendResponse(HttpExchange exchange, int code, String body, String contentType) throws IOException {
        byte[] resp = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", contentType + "; charset=utf-8");
        exchange.sendResponseHeaders(code, resp.length);
        OutputStream os = exchange.getResponseBody();
        os.write(resp);
        os.close();
    }

    // Minimal JSON serialization (for this example only)
    private static String toJson(Object obj) {
        if (obj == null) return "null";
        if (obj instanceof Map) {
            StringBuilder sb = new StringBuilder();
            sb.append("{");
            boolean first = true;
            for (Map.Entry<?,?> e : ((Map<?,?>) obj).entrySet()) {
                if (!first) sb.append(",");
                sb.append("\"").append(escapeJson(e.getKey().toString())).append("\":").append(toJson(e.getValue()));
                first = false;
            }
            sb.append("}");
            return sb.toString();
        } else if (obj instanceof List) {
            StringBuilder sb = new StringBuilder();
            sb.append("[");
            boolean first = true;
            for (Object o : (List<?>) obj) {
                if (!first) sb.append(",");
                sb.append(toJson(o));
                first = false;
            }
            sb.append("]");
            return sb.toString();
        } else if (obj instanceof String) {
            return "\"" + escapeJson(obj.toString()) + "\"";
        } else if (obj instanceof Number || obj instanceof Boolean) {
            return obj.toString();
        } else {
            return "\"" + escapeJson(obj.toString()) + "\"";
        }
    }

    // Minimal JSON parsing (for this example only; supports flat JSON objects)
    private static Map<String, Object> fromJson(String json) throws Exception {
        json = json.trim();
        if (!json.startsWith("{") || !json.endsWith("}")) throw new Exception("Not a JSON object");
        json = json.substring(1, json.length() - 1).trim();
        Map<String, Object> map = new HashMap<>();
        if (json.isEmpty()) return map;
        String[] pairs = json.split(",(?=(?:[^\"]*\"[^\"]*\")*[^\"]*$)");
        for (String pair : pairs) {
            String[] kv = pair.split(":(?=(?:[^\"]*\"[^\"]*\")*[^\"]*$)", 2);
            if (kv.length != 2) throw new Exception("Malformed JSON");
            String key = kv[0].trim();
            String value = kv[1].trim();
            if (key.startsWith("\"") && key.endsWith("\"")) {
                key = key.substring(1, key.length() - 1);
            }
            Object val;
            if (value.startsWith("\"") && value.endsWith("\"")) {
                val = value.substring(1, value.length() - 1);
            } else if ("true".equals(value) || "false".equals(value)) {
                val = Boolean.valueOf(value);
            } else {
                try {
                    val = Double.valueOf(value);
                } catch (NumberFormatException e) {
                    val = value;
                }
            }
            map.put(key, val);
        }
        return map;
    }

    private static String escapeJson(String s) {
        return s.replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r");
    }
}