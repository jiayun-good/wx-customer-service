import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.Headers;

import java.io.*;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.Executors;

public class DeviceShifuDriver {
    // Environment variables
    private static final String ENV_SERVER_HOST = "SERVER_HOST";
    private static final String ENV_SERVER_PORT = "SERVER_PORT";
    private static final String ENV_DEVICE_IP = "DEVICE_IP";
    private static final String ENV_DEVICE_PORT = "DEVICE_PORT";

    // Example in-memory telemetry data and command state
    private static final List<Map<String, Object>> telemetryData = Collections.synchronizedList(new ArrayList<>());
    private static final List<Map<String, Object>> commandLog = Collections.synchronizedList(new ArrayList<>());

    public static void main(String[] args) throws IOException {
        String host = getEnvOrDefault(ENV_SERVER_HOST, "0.0.0.0");
        int port = Integer.parseInt(getEnvOrDefault(ENV_SERVER_PORT, "8080"));
        String deviceIp = getEnvOrDefault(ENV_DEVICE_IP, "127.0.0.1");
        String devicePort = getEnvOrDefault(ENV_DEVICE_PORT, "12345");

        HttpServer server = HttpServer.create(new InetSocketAddress(host, port), 0);
        server.createContext("/points", new PointsHandler(deviceIp, devicePort));
        server.createContext("/commands", new CommandsHandler(deviceIp, devicePort));
        server.setExecutor(Executors.newCachedThreadPool());
        server.start();
        System.out.printf("DeviceShifu driver running at http://%s:%d\n", host, port);
    }

    static class PointsHandler implements com.sun.net.httpserver.HttpHandler {
        private final String deviceIp;
        private final String devicePort;

        public PointsHandler(String deviceIp, String devicePort) {
            this.deviceIp = deviceIp;
            this.devicePort = devicePort;
        }

        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!exchange.getRequestMethod().equalsIgnoreCase("GET")) {
                sendResponse(exchange, 405, "Method Not Allowed", "text/plain");
                return;
            }
            // Parse query params
            URI requestURI = exchange.getRequestURI();
            Map<String, String> params = queryToMap(requestURI.getQuery());
            int page = parseIntOrDefault(params.get("page"), 1);
            int limit = parseIntOrDefault(params.get("limit"), 10);

            // Simulate fetching telemetry data from the device
            List<Map<String, Object>> deviceData = fetchDeviceTelemetry(deviceIp, devicePort);

            // Pagination
            int start = (page - 1) * limit;
            int end = Math.min(start + limit, deviceData.size());
            List<Map<String, Object>> pageData = start < deviceData.size() ? deviceData.subList(start, end) : Collections.emptyList();

            Map<String, Object> response = new LinkedHashMap<>();
            response.put("page", page);
            response.put("limit", limit);
            response.put("total", deviceData.size());
            response.put("data", pageData);

            String json = toJson(response);
            sendResponse(exchange, 200, json, "application/json");
        }
    }

    static class CommandsHandler implements com.sun.net.httpserver.HttpHandler {
        private final String deviceIp;
        private final String devicePort;

        public CommandsHandler(String deviceIp, String devicePort) {
            this.deviceIp = deviceIp;
            this.devicePort = devicePort;
        }

        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!exchange.getRequestMethod().equalsIgnoreCase("POST")) {
                sendResponse(exchange, 405, "Method Not Allowed", "text/plain");
                return;
            }
            String body = readRequestBody(exchange);
            Map<String, Object> command;
            try {
                command = parseJson(body);
            } catch (Exception e) {
                sendResponse(exchange, 400, "{\"error\":\"Invalid JSON payload\"}", "application/json");
                return;
            }
            // Simulate sending command to device and store log
            boolean success = sendCommandToDevice(deviceIp, devicePort, command);
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("success", success);
            result.put("receivedCommand", command);
            String json = toJson(result);
            sendResponse(exchange, 200, json, "application/json");
        }
    }

    // Simulate fetching telemetry data from device
    private static List<Map<String, Object>> fetchDeviceTelemetry(String ip, String port) {
        // Insert your device-specific protocol code here.
        // For demonstration, return mock telemetry data.
        synchronized(telemetryData) {
            if (telemetryData.isEmpty()) {
                for (int i = 1; i <= 20; i++) {
                    Map<String, Object> entry = new LinkedHashMap<>();
                    entry.put("timestamp", System.currentTimeMillis() - i * 1000);
                    entry.put("pointId", "point-" + i);
                    entry.put("value", Math.random() * 100);
                    telemetryData.add(entry);
                }
            }
            return new ArrayList<>(telemetryData);
        }
    }

    // Simulate sending command to device
    private static boolean sendCommandToDevice(String ip, String port, Map<String, Object> command) {
        // Insert your device-specific protocol code here.
        // For demonstration, just log the command.
        commandLog.add(new LinkedHashMap<>(command));
        return true;
    }

    // --- Utility Methods ---

    private static String getEnvOrDefault(String key, String def) {
        String val = System.getenv(key);
        return (val != null && !val.isEmpty()) ? val : def;
    }

    private static void sendResponse(HttpExchange exchange, int status, String body, String contentType) throws IOException {
        Headers h = exchange.getResponseHeaders();
        h.set("Content-Type", contentType + "; charset=utf-8");
        exchange.sendResponseHeaders(status, body.getBytes(StandardCharsets.UTF_8).length);
        OutputStream os = exchange.getResponseBody();
        os.write(body.getBytes(StandardCharsets.UTF_8));
        os.close();
    }

    private static Map<String, String> queryToMap(String query) {
        Map<String, String> result = new HashMap<>();
        if (query == null) return result;
        for (String param : query.split("&")) {
            String[] pair = param.split("=", 2);
            if (pair.length > 0) {
                String key = urlDecode(pair[0]);
                String value = pair.length > 1 ? urlDecode(pair[1]) : "";
                result.put(key, value);
            }
        }
        return result;
    }

    private static int parseIntOrDefault(String s, int def) {
        try { return Integer.parseInt(s); } catch (Exception e) { return def; }
    }

    private static String readRequestBody(HttpExchange exchange) throws IOException {
        InputStream is = exchange.getRequestBody();
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        byte[] buf = new byte[1024];
        int len;
        while ((len = is.read(buf)) != -1) baos.write(buf, 0, len);
        return baos.toString(StandardCharsets.UTF_8.name());
    }

    // Minimal JSON serialization/deserialization
    private static String toJson(Object obj) {
        if (obj instanceof Map) {
            StringBuilder sb = new StringBuilder("{");
            boolean first = true;
            for (Map.Entry<?, ?> e : ((Map<?, ?>) obj).entrySet()) {
                if (!first) sb.append(",");
                sb.append("\"").append(escape(e.getKey().toString())).append("\":");
                sb.append(toJson(e.getValue()));
                first = false;
            }
            sb.append("}");
            return sb.toString();
        } else if (obj instanceof List) {
            StringBuilder sb = new StringBuilder("[");
            boolean first = true;
            for (Object o : (List<?>) obj) {
                if (!first) sb.append(",");
                sb.append(toJson(o));
                first = false;
            }
            sb.append("]");
            return sb.toString();
        } else if (obj instanceof String) {
            return "\"" + escape((String) obj) + "\"";
        } else if (obj instanceof Number || obj instanceof Boolean) {
            return obj.toString();
        } else if (obj == null) {
            return "null";
        } else {
            return "\"" + escape(obj.toString()) + "\"";
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> parseJson(String json) throws IOException {
        // Very minimal JSON parser for flat objects, for demo only
        json = json.trim();
        if (!json.startsWith("{") || !json.endsWith("}")) throw new IOException("Malformed JSON");
        Map<String, Object> map = new LinkedHashMap<>();
        json = json.substring(1, json.length() - 1).trim();
        if (json.isEmpty()) return map;
        for (String pair : json.split(",")) {
            String[] kv = pair.split(":", 2);
            if (kv.length != 2) throw new IOException("Malformed JSON");
            String key = kv[0].trim().replaceAll("^\"|\"$", "");
            String value = kv[1].trim();
            if (value.startsWith("\"") && value.endsWith("\"")) {
                map.put(key, value.substring(1, value.length() - 1));
            } else if ("true".equals(value) || "false".equals(value)) {
                map.put(key, Boolean.parseBoolean(value));
            } else if (value.matches("-?\\d+(\\.\\d+)?")) {
                map.put(key, value.contains(".") ? Double.parseDouble(value) : Long.parseLong(value));
            } else {
                map.put(key, value);
            }
        }
        return map;
    }

    private static String escape(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static String urlDecode(String s) {
        try {
            return URLDecoder.decode(s, StandardCharsets.UTF_8.name());
        } catch (Exception e) {
            return s;
        }
    }
}