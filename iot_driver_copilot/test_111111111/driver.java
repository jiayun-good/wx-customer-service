import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.Headers;

import java.io.IOException;
import java.io.OutputStream;
import java.io.InputStreamReader;
import java.io.BufferedReader;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class DeviceShifuDriver {

    // Simulated device data (replace with real device access logic)
    private static final List<Map<String, Object>> DATA_POINTS = Collections.synchronizedList(new ArrayList<>());
    static {
        Map<String, Object> point1 = new HashMap<>();
        point1.put("timestamp", System.currentTimeMillis());
        point1.put("temperature", 25.3);
        point1.put("status", "OK");
        DATA_POINTS.add(point1);

        Map<String, Object> point2 = new HashMap<>();
        point2.put("timestamp", System.currentTimeMillis() - 10000);
        point2.put("temperature", 24.9);
        point2.put("status", "OK");
        DATA_POINTS.add(point2);
    }

    private static String readRequestBody(HttpExchange exchange) throws IOException {
        InputStream is = exchange.getRequestBody();
        BufferedReader br = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = br.readLine()) != null) {
            sb.append(line);
        }
        return sb.toString();
    }

    private static Map<String, String> parseQuery(String query) throws IOException {
        Map<String, String> result = new HashMap<>();
        if (query == null || query.isEmpty()) return result;
        String[] pairs = query.split("&");
        for (String pair : pairs) {
            int idx = pair.indexOf('=');
            if (idx >= 0) {
                result.put(URLDecoder.decode(pair.substring(0, idx), "UTF-8"),
                           URLDecoder.decode(pair.substring(idx + 1), "UTF-8"));
            } else {
                result.put(URLDecoder.decode(pair, "UTF-8"), "");
            }
        }
        return result;
    }

    private static void sendJsonResponse(HttpExchange exchange, String response, int statusCode) throws IOException {
        Headers headers = exchange.getResponseHeaders();
        headers.set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(statusCode, response.getBytes(StandardCharsets.UTF_8).length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(response.getBytes(StandardCharsets.UTF_8));
        }
    }

    private static void handlePoints(HttpExchange exchange) throws IOException {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            exchange.sendResponseHeaders(405, -1); // Method Not Allowed
            return;
        }
        String query = exchange.getRequestURI().getQuery();
        Map<String, String> params = parseQuery(query);

        int page = 1;
        int limit = 10;
        if (params.containsKey("page")) {
            try { page = Integer.parseInt(params.get("page")); } catch (Exception ignored) {}
            if (page < 1) page = 1;
        }
        if (params.containsKey("limit")) {
            try { limit = Integer.parseInt(params.get("limit")); } catch (Exception ignored) {}
            if (limit < 1) limit = 10;
        }

        int fromIndex = (page - 1) * limit;
        int toIndex = Math.min(fromIndex + limit, DATA_POINTS.size());

        List<Map<String, Object>> pageData = new ArrayList<>();
        if (fromIndex < DATA_POINTS.size()) {
            pageData.addAll(DATA_POINTS.subList(fromIndex, toIndex));
        }

        Map<String, Object> result = new HashMap<>();
        result.put("data", pageData);
        result.put("page", page);
        result.put("limit", limit);
        result.put("total", DATA_POINTS.size());

        String json = toJson(result);
        sendJsonResponse(exchange, json, 200);
    }

    private static void handleCommands(HttpExchange exchange) throws IOException {
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            exchange.sendResponseHeaders(405, -1); // Method Not Allowed
            return;
        }
        String body = readRequestBody(exchange);
        Map<String, Object> cmdResult = new HashMap<>();
        cmdResult.put("success", true);
        cmdResult.put("received", body);
        cmdResult.put("timestamp", System.currentTimeMillis());
        String json = toJson(cmdResult);
        sendJsonResponse(exchange, json, 200);
    }

    // Minimal JSON serializer for this simple data
    private static String toJson(Object obj) {
        if (obj == null) return "null";
        if (obj instanceof String) return "\"" + escape((String) obj) + "\"";
        if (obj instanceof Number || obj instanceof Boolean) return obj.toString();
        if (obj instanceof Map) {
            StringBuilder sb = new StringBuilder();
            sb.append("{");
            boolean first = true;
            for (Map.Entry<?, ?> entry : ((Map<?, ?>) obj).entrySet()) {
                if (!first) sb.append(",");
                sb.append(toJson(entry.getKey())).append(":").append(toJson(entry.getValue()));
                first = false;
            }
            sb.append("}");
            return sb.toString();
        }
        if (obj instanceof List) {
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
        }
        return "\"" + escape(obj.toString()) + "\"";
    }

    private static String escape(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    public static void main(String[] args) throws Exception {
        String serverHost = System.getenv("SERVER_HOST");
        String serverPortStr = System.getenv("SERVER_PORT");

        if (serverHost == null || serverHost.isEmpty()) serverHost = "0.0.0.0";
        int serverPort = 8080;
        if (serverPortStr != null && !serverPortStr.isEmpty()) {
            try {
                serverPort = Integer.parseInt(serverPortStr);
            } catch (NumberFormatException ignored) {}
        }

        InetSocketAddress address = new InetSocketAddress(serverHost, serverPort);
        HttpServer server = HttpServer.create(address, 0);

        server.createContext("/points", DeviceShifuDriver::handlePoints);
        server.createContext("/commands", DeviceShifuDriver::handleCommands);

        ExecutorService executor = Executors.newFixedThreadPool(8);
        server.setExecutor(executor);

        System.out.println("DeviceShifu HTTP Driver started at http://" + serverHost + ":" + serverPort);
        server.start();
    }
}