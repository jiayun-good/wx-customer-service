import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.*;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.*;

public class DeviceShifuDriver {

    private static final String ENV_SERVER_HOST = "SERVER_HOST";
    private static final String ENV_SERVER_PORT = "SERVER_PORT";
    private static final String ENV_DEVICE_IP = "DEVICE_IP";
    private static final String ENV_DEVICE_PORT = "DEVICE_PORT";

    // Simulated device data for demonstration
    private static final List<Map<String, Object>> DEVICE_POINTS = new ArrayList<>();
    static {
        for (int i = 1; i <= 100; i++) {
            Map<String, Object> point = new HashMap<>();
            point.put("id", i);
            point.put("name", "DataPoint-" + i);
            point.put("value", Math.random() * 100);
            point.put("timestamp", System.currentTimeMillis());
            DEVICE_POINTS.add(point);
        }
    }

    public static void main(String[] args) throws Exception {
        String host = getEnvOrDefault(ENV_SERVER_HOST, "0.0.0.0");
        int port = Integer.parseInt(getEnvOrDefault(ENV_SERVER_PORT, "8080"));

        HttpServer server = HttpServer.create(new InetSocketAddress(host, port), 0);

        server.createContext("/points", new PointsHandler());
        server.createContext("/commands", new CommandsHandler());

        server.setExecutor(null);
        System.out.println("DeviceShifuDriver HTTP Server started at http://" + host + ":" + port);
        server.start();
    }

    static class PointsHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendResponse(exchange, 405, "Method Not Allowed", "text/plain");
                return;
            }

            Map<String, String> queryParams = parseQueryParams(exchange.getRequestURI().getRawQuery());
            int page = parsePositiveInt(queryParams.get("page"), 1);
            int limit = parsePositiveInt(queryParams.get("limit"), 10);

            // Filter and paginate
            int start = (page - 1) * limit;
            int end = Math.min(start + limit, DEVICE_POINTS.size());
            List<Map<String, Object>> dataPage = start < DEVICE_POINTS.size() ? DEVICE_POINTS.subList(start, end) : Collections.emptyList();

            JSONObject response = new JSONObject();
            response.put("page", page);
            response.put("limit", limit);
            response.put("total", DEVICE_POINTS.size());
            response.put("data", new JSONArray(dataPage));

            sendResponse(exchange, 200, response.toString(), "application/json");
        }
    }

    static class CommandsHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendResponse(exchange, 405, "Method Not Allowed", "text/plain");
                return;
            }

            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            JSONObject json;
            try {
                json = new JSONObject(body);
            } catch (Exception e) {
                sendResponse(exchange, 400, "{\"error\":\"Invalid JSON payload\"}", "application/json");
                return;
            }

            // Simulate command execution
            JSONObject result = new JSONObject();
            result.put("status", "success");
            result.put("received", json);

            sendResponse(exchange, 200, result.toString(), "application/json");
        }
    }

    private static void sendResponse(HttpExchange exchange, int code, String body, String contentType) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        Headers headers = exchange.getResponseHeaders();
        headers.set("Content-Type", contentType + "; charset=UTF-8");
        headers.set("Access-Control-Allow-Origin", "*");
        if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
            headers.set("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
            headers.set("Access-Control-Allow-Headers", "Content-Type");
            exchange.sendResponseHeaders(204, -1);
            return;
        }
        exchange.sendResponseHeaders(code, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    private static Map<String, String> parseQueryParams(String query) throws UnsupportedEncodingException {
        Map<String, String> params = new HashMap<>();
        if (query == null) return params;
        for (String pair : query.split("&")) {
            int idx = pair.indexOf('=');
            if (idx > 0) {
                params.put(
                        URLDecoder.decode(pair.substring(0, idx), "UTF-8"),
                        URLDecoder.decode(pair.substring(idx + 1), "UTF-8")
                );
            } else if (!pair.isEmpty()) {
                params.put(URLDecoder.decode(pair, "UTF-8"), "");
            }
        }
        return params;
    }

    private static int parsePositiveInt(String s, int def) {
        try {
            int v = Integer.parseInt(s);
            return v > 0 ? v : def;
        } catch (Exception e) {
            return def;
        }
    }

    private static String getEnvOrDefault(String key, String def) {
        String val = System.getenv(key);
        return (val == null || val.isEmpty()) ? def : val;
    }
}