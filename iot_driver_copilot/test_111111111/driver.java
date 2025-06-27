package devicestifu.driver;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import java.io.*;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.*;

public class DeviceShifuDriver {
    // Environment variable names
    private static final String ENV_SERVER_HOST = "SHIFU_HTTP_SERVER_HOST";
    private static final String ENV_SERVER_PORT = "SHIFU_HTTP_SERVER_PORT";
    private static final String ENV_DEVICE_IP = "SHIFU_DEVICE_IP";
    private static final String ENV_DEVICE_PORT = "SHIFU_DEVICE_PORT";
    private static final String ENV_DEVICE_USERNAME = "SHIFU_DEVICE_USERNAME";
    private static final String ENV_DEVICE_PASSWORD = "SHIFU_DEVICE_PASSWORD";

    // Device mock data (replace with real device access logic as needed)
    private static final List<Map<String, Object>> MOCK_DATA_POINTS = new ArrayList<>();

    static {
        Map<String, Object> d1 = new HashMap<>();
        d1.put("timestamp", System.currentTimeMillis());
        d1.put("point", "temperature");
        d1.put("value", 22.8);
        d1.put("unit", "C");
        MOCK_DATA_POINTS.add(d1);

        Map<String, Object> d2 = new HashMap<>();
        d2.put("timestamp", System.currentTimeMillis());
        d2.put("point", "humidity");
        d2.put("value", 56);
        d2.put("unit", "%");
        MOCK_DATA_POINTS.add(d2);
    }

    public static void main(String[] args) throws Exception {
        String host = getEnvOrDefault(ENV_SERVER_HOST, "0.0.0.0");
        int port = Integer.parseInt(getEnvOrDefault(ENV_SERVER_PORT, "8080"));

        HttpServer server = HttpServer.create(new InetSocketAddress(host, port), 0);

        server.createContext("/points", new PointsHandler());
        server.createContext("/commands", new CommandsHandler());

        server.setExecutor(null); // creates a default executor
        System.out.println("DeviceShifu Driver HTTP server started at " + host + ":" + port);
        server.start();
    }

    static class PointsHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!exchange.getRequestMethod().equalsIgnoreCase("GET")) {
                sendResponse(exchange, 405, "Method Not Allowed", "text/plain");
                return;
            }
            Map<String, String> queryParams = parseQueryParams(exchange.getRequestURI().getRawQuery());
            // Filter & paginate
            int page = parseInt(queryParams.getOrDefault("page", "1"), 1);
            int limit = parseInt(queryParams.getOrDefault("limit", "10"), 10);

            List<Map<String, Object>> filtered = new ArrayList<>(MOCK_DATA_POINTS);
            // Add filter logic here if needed

            int total = filtered.size();
            int fromIndex = Math.min((page - 1) * limit, total);
            int toIndex = Math.min(page * limit, total);
            List<Map<String, Object>> paged = filtered.subList(fromIndex, toIndex);

            Map<String, Object> response = new HashMap<>();
            response.put("page", page);
            response.put("limit", limit);
            response.put("total", total);
            response.put("data", paged);

            byte[] resp = toJson(response).getBytes(StandardCharsets.UTF_8);
            sendResponse(exchange, 200, resp, "application/json");
        }
    }

    static class CommandsHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!exchange.getRequestMethod().equalsIgnoreCase("POST")) {
                sendResponse(exchange, 405, "Method Not Allowed", "text/plain");
                return;
            }
            String body = readBody(exchange.getRequestBody());
            // Parse JSON body (very basic, for demo purposes - use a real JSON parser in production)
            Map<String, Object> request = parseJson(body);
            String command = request.getOrDefault("command", "").toString();

            // Here you would send the command to the device using HTTP or other protocol
            // For demo, we just echo back
            Map<String, Object> respObj = new HashMap<>();
            respObj.put("status", "success");
            respObj.put("message", "Command executed: " + command);

            byte[] resp = toJson(respObj).getBytes(StandardCharsets.UTF_8);
            sendResponse(exchange, 200, resp, "application/json");
        }
    }

    // Util methods

    private static void sendResponse(HttpExchange exchange, int status, String response, String contentType) throws IOException {
        sendResponse(exchange, status, response.getBytes(StandardCharsets.UTF_8), contentType);
    }

    private static void sendResponse(HttpExchange exchange, int status, byte[] response, String contentType) throws IOException {
        Headers headers = exchange.getResponseHeaders();
        headers.set("Content-Type", contentType);
        exchange.sendResponseHeaders(status, response.length);
        OutputStream os = exchange.getResponseBody();
        os.write(response);
        os.close();
    }

    private static String getEnvOrDefault(String name, String defaultVal) {
        String v = System.getenv(name);
        return v != null && !v.isEmpty() ? v : defaultVal;
    }

    private static Map<String, String> parseQueryParams(String rawQuery) {
        Map<String, String> params = new HashMap<>();
        if (rawQuery == null || rawQuery.isEmpty()) return params;
        for (String pair : rawQuery.split("&")) {
            int idx = pair.indexOf("=");
            if (idx > 0) {
                try {
                    params.put(URLDecoder.decode(pair.substring(0, idx), "UTF-8"),
                            URLDecoder.decode(pair.substring(idx + 1), "UTF-8"));
                } catch (UnsupportedEncodingException e) {
                    // Ignore and skip
                }
            } else {
                try {
                    params.put(URLDecoder.decode(pair, "UTF-8"), "");
                } catch (UnsupportedEncodingException e) {
                    // Ignore
                }
            }
        }
        return params;
    }

    private static int parseInt(String v, int def) {
        try {
            return Integer.parseInt(v);
        } catch (Exception e) {
            return def;
        }
    }

    // Basic JSON serialization (for demo only)
    private static String toJson(Object obj) {
        if (obj instanceof Map) {
            StringBuilder sb = new StringBuilder();
            sb.append("{");
            boolean first = true;
            for (Object k : ((Map<?, ?>) obj).keySet()) {
                if (!first) sb.append(",");
                first = false;
                sb.append("\"").append(escape(k.toString())).append("\":");
                sb.append(toJson(((Map<?, ?>) obj).get(k)));
            }
            sb.append("}");
            return sb.toString();
        } else if (obj instanceof List) {
            StringBuilder sb = new StringBuilder();
            sb.append("[");
            boolean first = true;
            for (Object o : (List<?>) obj) {
                if (!first) sb.append(",");
                first = false;
                sb.append(toJson(o));
            }
            sb.append("]");
            return sb.toString();
        } else if (obj instanceof String) {
            return "\"" + escape(obj.toString()) + "\"";
        } else {
            return String.valueOf(obj);
        }
    }

    private static String escape(String s) {
        return s.replace("\"", "\\\"");
    }

    // For demo only: parse a simple flat JSON object
    private static Map<String, Object> parseJson(String json) {
        Map<String, Object> map = new HashMap<>();
        if (json == null || json.isEmpty()) return map;
        json = json.trim();
        if (json.startsWith("{") && json.endsWith("}")) {
            json = json.substring(1, json.length() - 1);
            String[] entries = json.split(",");
            for (String entry : entries) {
                int idx = entry.indexOf(":");
                if (idx > 0) {
                    String key = entry.substring(0, idx).trim().replaceAll("^\"|\"$", "");
                    String val = entry.substring(idx + 1).trim().replaceAll("^\"|\"$", "");
                    map.put(key, val);
                }
            }
        }
        return map;
    }

    private static String readBody(InputStream is) throws IOException {
        StringBuilder sb = new StringBuilder();
        BufferedReader br = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8));
        String line;
        while ((line = br.readLine()) != null) sb.append(line);
        return sb.toString();
    }
}