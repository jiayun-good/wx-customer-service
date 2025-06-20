#include <iostream>
#include <cstdlib>
#include <sstream>
#include <string>
#include <map>
#include <vector>
#include <cstring>
#include <thread>
#include <algorithm>
#include <ctime>

// Simple HTTP server using sockets (no third-party code or external commands)
#ifdef _WIN32
#include <winsock2.h>
#pragma comment(lib, "ws2_32.lib")
typedef int socklen_t;
#else
#include <sys/socket.h>
#include <netinet/in.h>
#include <unistd.h>
#endif

#define BUFFER_SIZE 4096

// Helper to trim whitespace
static inline std::string trim(const std::string& s) {
    auto start = s.begin();
    while (start != s.end() && isspace(*start)) ++start;
    auto end = s.end();
    do { --end; } while (distance(start, end) > 0 && isspace(*end));
    return std::string(start, end + 1);
}

struct HttpRequest {
    std::string method;
    std::string path;
    std::string http_version;
    std::map<std::string, std::string> headers;
    std::string body;
    std::map<std::string, std::string> query_params;
};

std::string url_decode(const std::string& str) {
    std::string ret;
    char ch;
    int i, ii, len = str.length();
    for (i = 0; i < len; i++) {
        if (str[i] != '%') {
            if (str[i] == '+')
                ret += ' ';
            else
                ret += str[i];
        } else {
            sscanf(str.substr(i + 1, 2).c_str(), "%x", &ii);
            ch = static_cast<char>(ii);
            ret += ch;
            i = i + 2;
        }
    }
    return ret;
}

std::map<std::string, std::string> parse_query(const std::string& qs) {
    std::map<std::string, std::string> params;
    std::string::size_type lastPos = 0, pos = 0;
    while ((pos = qs.find('&', lastPos)) != std::string::npos) {
        std::string token = qs.substr(lastPos, pos - lastPos);
        std::string::size_type eq = token.find('=');
        if (eq != std::string::npos)
            params[url_decode(token.substr(0, eq))] = url_decode(token.substr(eq + 1));
        lastPos = pos + 1;
    }
    if (lastPos < qs.length()) {
        std::string token = qs.substr(lastPos);
        std::string::size_type eq = token.find('=');
        if (eq != std::string::npos)
            params[url_decode(token.substr(0, eq))] = url_decode(token.substr(eq + 1));
    }
    return params;
}

HttpRequest parse_request(const std::string& raw) {
    HttpRequest req;
    std::istringstream stream(raw);
    std::string line;
    std::getline(stream, line);
    std::istringstream lstream(line);
    lstream >> req.method >> req.path >> req.http_version;
    // Parse query string if present
    auto qmark = req.path.find('?');
    if (qmark != std::string::npos) {
        req.query_params = parse_query(req.path.substr(qmark + 1));
        req.path = req.path.substr(0, qmark);
    }
    // Headers
    while (std::getline(stream, line) && line != "\r") {
        auto colon = line.find(':');
        if (colon != std::string::npos) {
            std::string key = trim(line.substr(0, colon));
            std::string value = trim(line.substr(colon + 1));
            // Remove trailing \r
            if (!value.empty() && value.back() == '\r') value.pop_back();
            req.headers[key] = value;
        }
    }
    // Body
    std::string body;
    while (std::getline(stream, line)) {
        body += line + "\n";
    }
    req.body = body;
    return req;
}

// Simulate device data in XML format
std::string get_device_data_xml(const std::map<std::string, std::string>& query) {
    // Simulate filter, limit, offset
    std::string filter = "";
    int limit = 1, offset = 0;
    if (query.count("filter")) filter = query.at("filter");
    if (query.count("limit")) limit = std::max(1, std::stoi(query.at("limit")));
    if (query.count("offset")) offset = std::max(0, std::stoi(query.at("offset")));

    std::ostringstream oss;
    oss << "<?xml version=\"1.0\"?>\n";
    oss << "<DeviceDataPoints>\n";
    for (int i = 0; i < limit; ++i) {
        oss << "  <DataPoint>\n";
        oss << "    <Name>dp" << (offset + i) << "</Name>\n";
        oss << "    <Value>" << ((filter.empty()) ? "42" : filter) << "</Value>\n";
        oss << "    <Timestamp>" << std::time(nullptr) << "</Timestamp>\n";
        oss << "  </DataPoint>\n";
    }
    oss << "</DeviceDataPoints>\n";
    return oss.str();
}

// Simulate command processing and respond in JSON
std::string process_command(const std::string& body, bool &valid) {
    std::string action;
    // Simple parse for JSON: look for "action":"..."
    auto pos = body.find("\"action\"");
    if (pos != std::string::npos) {
        auto start = body.find(':', pos);
        if (start != std::string::npos) {
            start++;
            while (start < body.size() && (body[start] == ' ' || body[start] == '"')) ++start;
            auto end = body.find_first_of("\"},", start);
            if (end != std::string::npos)
                action = body.substr(start, end - start);
        }
    }
    valid = !action.empty();
    std::ostringstream oss;
    oss << "{\n";
    oss << "  \"accepted\": " << (valid ? "true" : "false") << ",\n";
    oss << "  \"executed\": " << (valid ? "true" : "false") << ",\n";
    oss << "  \"action\": \"" << (valid ? action : "") << "\"\n";
    oss << "}";
    return oss.str();
}

// Send HTTP response
void send_response(int client_sock, const std::string& status, const std::string& content_type, const std::string& body) {
    std::ostringstream oss;
    oss << "HTTP/1.1 " << status << "\r\n";
    oss << "Content-Type: " << content_type << "\r\n";
    oss << "Content-Length: " << body.size() << "\r\n";
    oss << "Connection: close\r\n";
    oss << "\r\n";
    oss << body;
    std::string resp = oss.str();
    send(client_sock, resp.c_str(), resp.size(), 0);
}

// Threaded handler for a client connection
void handle_client(int client_sock) {
    char buffer[BUFFER_SIZE];
    int received = recv(client_sock, buffer, BUFFER_SIZE - 1, 0);
    if (received <= 0) {
#ifdef _WIN32
        closesocket(client_sock);
#else
        close(client_sock);
#endif
        return;
    }
    buffer[received] = 0;
    std::string request_str(buffer);
    HttpRequest req = parse_request(request_str);
    if (req.method == "GET" && req.path == "/data") {
        std::string xml = get_device_data_xml(req.query_params);
        send_response(client_sock, "200 OK", "application/xml", xml);
    } else if (req.method == "POST" && req.path == "/commands") {
        bool valid = false;
        std::string json = process_command(req.body, valid);
        if (valid)
            send_response(client_sock, "200 OK", "application/json", json);
        else
            send_response(client_sock, "400 Bad Request", "application/json", "{\"error\":\"Invalid command\"}");
    } else {
        send_response(client_sock, "404 Not Found", "text/plain", "Not Found");
    }
#ifdef _WIN32
    closesocket(client_sock);
#else
    close(client_sock);
#endif
}

int main() {
    // Read configuration from environment variables
    const char* env_host = std::getenv("SHIFU_HTTP_SERVER_HOST");
    const char* env_port = std::getenv("SHIFU_HTTP_SERVER_PORT");
    std::string host = env_host ? env_host : "0.0.0.0";
    int port = env_port ? std::atoi(env_port) : 8080;

#ifdef _WIN32
    WSADATA wsaData;
    WSAStartup(MAKEWORD(2, 2), &wsaData);
#endif

    int server_sock = socket(AF_INET, SOCK_STREAM, 0);
    if (server_sock < 0) {
        std::cerr << "Socket creation failed\n";
        return 1;
    }
    int opt = 1;
    setsockopt(server_sock, SOL_SOCKET, SO_REUSEADDR, (char*)&opt, sizeof(opt));

    sockaddr_in addr;
    addr.sin_family = AF_INET;
    addr.sin_port = htons(port);
    addr.sin_addr.s_addr = host == "0.0.0.0" ? INADDR_ANY : inet_addr(host.c_str());

    if (bind(server_sock, (sockaddr*)&addr, sizeof(addr)) < 0) {
        std::cerr << "Bind failed\n";
#ifdef _WIN32
        closesocket(server_sock);
        WSACleanup();
#else
        close(server_sock);
#endif
        return 1;
    }
    if (listen(server_sock, 8) < 0) {
        std::cerr << "Listen failed\n";
#ifdef _WIN32
        closesocket(server_sock);
        WSACleanup();
#else
        close(server_sock);
#endif
        return 1;
    }
    std::cout << "Server listening on " << host << ":" << port << std::endl;
    while (true) {
        sockaddr_in client_addr;
        socklen_t client_len = sizeof(client_addr);
        int client_sock = accept(server_sock, (sockaddr*)&client_addr, &client_len);
        if (client_sock < 0) continue;
        std::thread(handle_client, client_sock).detach();
    }
#ifdef _WIN32
    closesocket(server_sock);
    WSACleanup();
#else
    close(server_sock);
#endif
    return 0;
}