import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpExchange;

import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.ParserConfigurationException;

import java.net.InetSocketAddress;
import java.net.HttpURLConnection;
import java.net.URL;
import java.io.*;
import java.util.*;
import java.nio.charset.StandardCharsets;

import org.w3c.dom.*;
import org.xml.sax.SAXException;

public class DeviceShifuDriver {

    private static final String ENV_SERVER_HOST = "SERVER_HOST";
    private static final String ENV_SERVER_PORT = "SERVER_PORT";
    private static final String ENV_DEVICE_IP = "DEVICE_IP";
    private static final String ENV_DEVICE_HTTP_PORT = "DEVICE_HTTP_PORT";
    private static final String ENV_DEVICE_DATA_ENDPOINT = "DEVICE_DATA_ENDPOINT";
    private static final String ENV_DEVICE_COMMAND_ENDPOINT = "DEVICE_COMMAND_ENDPOINT";

    private static String serverHost;
    private static int serverPort;
    private static String deviceIp;
    private static int deviceHttpPort;
    private static String deviceDataEndpoint;
    private static String deviceCommandEndpoint;

    public static void main(String[] args) throws Exception {
        loadEnv();

        InetSocketAddress socketAddress = new InetSocketAddress(serverHost, serverPort);
        HttpServer server = HttpServer.create(socketAddress, 0);

        server.createContext("/datapoints", new DataPointsHandler());
        server.createContext("/commands", new CommandsHandler());

        server.setExecutor(null);
        server.start();
        System.out.println("DeviceShifuDriver HTTP server started at http://" + serverHost + ":" + serverPort);
    }

    private static void loadEnv() {
        serverHost = getEnvOrDefault(ENV_SERVER_HOST, "0.0.0.0");
        serverPort = Integer.parseInt(getEnvOrDefault(ENV_SERVER_PORT, "8080"));
        deviceIp = getEnvOrDefault(ENV_DEVICE_IP, "127.0.0.1");
        deviceHttpPort = Integer.parseInt(getEnvOrDefault(ENV_DEVICE_HTTP_PORT, "80"));
        deviceDataEndpoint = getEnvOrDefault(ENV_DEVICE_DATA_ENDPOINT, "/data");
        deviceCommandEndpoint = getEnvOrDefault(ENV_DEVICE_COMMAND_ENDPOINT, "/command");
    }

    private static String getEnvOrDefault(String envKey, String defaultValue) {
        String value = System.getenv(envKey);
        return value != null ? value : defaultValue;
    }

    static class DataPointsHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!exchange.getRequestMethod().equalsIgnoreCase("GET")) {
                sendResponse(exchange, 405, "Method Not Allowed");
                return;
            }

            // Build URL to device
            String query = exchange.getRequestURI().getRawQuery();
            StringBuilder deviceUrlBuilder = new StringBuilder();
            deviceUrlBuilder.append("http://")
                    .append(deviceIp)
                    .append(":")
                    .append(deviceHttpPort)
                    .append(deviceDataEndpoint);
            if (query != null && !query.isEmpty()) {
                deviceUrlBuilder.append("?").append(query);
            }
            String deviceUrl = deviceUrlBuilder.toString();

            try {
                HttpURLConnection conn = (HttpURLConnection) new URL(deviceUrl).openConnection();
                conn.setRequestMethod("GET");
                conn.setConnectTimeout(5000);
                conn.setReadTimeout(5000);

                int status = conn.getResponseCode();
                if (status != 200) {
                    sendResponse(exchange, 502, "Failed to fetch data from device.");
                    return;
                }

                InputStream xmlStream = conn.getInputStream();
                String json = xmlToJson(xmlStream);
                conn.disconnect();

                exchange.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
                sendResponse(exchange, 200, json);
            } catch (Exception e) {
                sendResponse(exchange, 500, "{\"error\": \"" + e.getMessage() + "\"}");
            }
        }
    }

    static class CommandsHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!exchange.getRequestMethod().equalsIgnoreCase("POST")) {
                sendResponse(exchange, 405, "Method Not Allowed");
                return;
            }

            // Parse JSON payload
            String requestBody = readRequestBody(exchange.getRequestBody());
            if (requestBody == null || requestBody.isEmpty()) {
                sendResponse(exchange, 400, "{\"error\": \"Empty request body\"}");
                return;
            }

            // Forward command to device
            String deviceUrl = "http://" + deviceIp + ":" + deviceHttpPort + deviceCommandEndpoint;
            try {
                HttpURLConnection conn = (HttpURLConnection) new URL(deviceUrl).openConnection();
                conn.setRequestMethod("POST");
                conn.setDoOutput(true);
                conn.setConnectTimeout(5000);
                conn.setReadTimeout(5000);
                conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");

                OutputStream os = conn.getOutputStream();
                os.write(requestBody.getBytes(StandardCharsets.UTF_8));
                os.flush();
                os.close();

                int status = conn.getResponseCode();
                InputStream is = (status >= 200 && status < 300) ? conn.getInputStream() : conn.getErrorStream();
                String resp = readRequestBody(is);
                conn.disconnect();

                exchange.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
                sendResponse(exchange, status, resp != null ? resp : "{\"result\": \"Command processed\"}");
            } catch (Exception e) {
                sendResponse(exchange, 500, "{\"error\": \"" + e.getMessage() + "\"}");
            }
        }
    }

    private static String readRequestBody(InputStream is) throws IOException {
        if (is == null) return null;
        BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder();
        char[] buf = new char[1024];
        int len;
        while ((len = reader.read(buf)) != -1) {
            sb.append(buf, 0, len);
        }
        return sb.toString();
    }

    // Minimal XML to JSON conversion (for simple structures)
    private static String xmlToJson(InputStream xmlStream) throws ParserConfigurationException, IOException, SAXException {
        DocumentBuilderFactory dbFactory = DocumentBuilderFactory.newInstance();
        DocumentBuilder dBuilder = dbFactory.newDocumentBuilder();
        Document doc = dBuilder.parse(xmlStream);
        doc.getDocumentElement().normalize();

        StringBuilder json = new StringBuilder();
        json.append("{");
        appendXmlNode(json, doc.getDocumentElement());
        json.append("}");
        return json.toString();
    }

    private static void appendXmlNode(StringBuilder json, Node node) {
        NodeList children = node.getChildNodes();
        Map<String, List<Node>> grouped = new LinkedHashMap<>();
        boolean hasElementChild = false;

        for (int i = 0; i < children.getLength(); i++) {
            Node c = children.item(i);
            if (c.getNodeType() == Node.ELEMENT_NODE) {
                hasElementChild = true;
                grouped.computeIfAbsent(c.getNodeName(), k -> new ArrayList<>()).add(c);
            }
        }

        if (!hasElementChild) {
            json.append("\"").append(node.getNodeName()).append("\": ");
            json.append("\"").append(escapeJson(node.getTextContent())).append("\"");
        } else {
            int groupCount = 0;
            for (Map.Entry<String, List<Node>> entry : grouped.entrySet()) {
                if (groupCount++ > 0) json.append(",");
                json.append("\"").append(entry.getKey()).append("\": ");
                if (entry.getValue().size() > 1) {
                    json.append("[");
                    for (int i = 0; i < entry.getValue().size(); i++) {
                        if (i > 0) json.append(",");
                        json.append("{");
                        appendXmlNode(json, entry.getValue().get(i));
                        json.append("}");
                    }
                    json.append("]");
                } else {
                    json.append("{");
                    appendXmlNode(json, entry.getValue().get(0));
                    json.append("}");
                }
            }
        }
    }

    private static String escapeJson(String s) {
        return s.replace("\"", "\\\"");
    }

    private static void sendResponse(HttpExchange exchange, int statusCode, String body) throws IOException {
        exchange.sendResponseHeaders(statusCode, body.getBytes(StandardCharsets.UTF_8).length);
        OutputStream os = exchange.getResponseBody();
        os.write(body.getBytes(StandardCharsets.UTF_8));
        os.close();
    }
}