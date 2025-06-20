#include <iostream>
#include <cstdlib>
#include <string>
#include <vector>
#include <map>
#include <sstream>
#include <algorithm>
#include <cstring>
#include <cstdio>
#include <ctime>

#ifdef _WIN32
#include <winsock2.h>
#pragma comment(lib, "ws2_32.lib")
typedef int socklen_t;
#else
#include <unistd.h>
#include <sys/socket.h>
#include <netinet/in.h>
#include <fcntl.h>
#endif

// Utility to trim whitespace
inline std::string trim(const std::string &s) {
    auto start = s.begin();
    while (start != s.end() && isspace(*start)) start++;
    auto end = s.end();
    do { end--; } while (distance(start, end) > 0 && isspace(*end));
    return std::string(start, end + 1);
}

// Parse query parameters from a URL
std::map<std::string, std::string> parse_query(const std::string& url) {
    std::map<std::string, std::string> params;
    auto qm = url.find('?');
    if (qm == std::string::npos) return params;
    std::string query = url.substr(qm + 1);
    std::istringstream iss(query);
    std::string pair;
    while (std::getline(iss, pair, '&')) {
        auto eq = pair.find('=');
        if (eq != std::string::npos) {
            params[pair.substr(0, eq)] = pair.substr(eq + 1);
        }
    }
    return params;
}

// Read a line from a socket
int recv_line(int sock, std::string& out) {
    char c;
    out.clear();
    while (true) {
        int n = recv(sock, &c, 1, 0);
        if (n <= 0) return n;
        if (c == '\r') continue;
        if (c == '\n') break;
        out += c;
    }
    return out.size();
}

// Parse HTTP headers into a map
std::map<std::string, std::string> parse_headers(int sock) {
    std::map<std::string, std::string> headers;
    std::string line;
    while (recv_line(sock, line) > 0) {
        if (line.empty()) break;
        auto colon = line.find(':');
        if (colon != std::string::npos) {
            std::string key = trim(line.substr(0, colon));
            std::string val = trim(line.substr(colon + 1));
            std::transform(key.begin(), key.end(), key.begin(), ::tolower);
            headers[key] = val;
        }
    }
    return headers;
}

// Read a fixed number of bytes from a socket
std::string recv_n(int sock, int len) {
    std::string data;
    data.resize(len);
    int recvd = 0;
    while (recvd < len) {
        int n = recv(sock, &data[recvd], len - recvd, 0);
        if (n <= 0) break;
        recvd += n;
    }
    data.resize(recvd);
    return data;
}

// XML data stub generator (simulate device data)
std::string generate_device_xml(const std::map<std::string, std::string>& qp) {
    std::ostringstream oss;
    oss << "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n";
    oss << "<device_data>\n";
    oss << "  <device_name>test1</device_name>\n";
    oss << "  <device_model>dsa</device_model>\n";
    oss << "  <manufacturer>adsads</manufacturer>\n";
    oss << "  <device_type>ads</device_type>\n";
    oss << "  <data_points>" << (qp.count("filter") ? qp.at("filter") : "adsads") << "</data_points>\n";
    oss << "  <timestamp>" << std::time(nullptr) << "</timestamp>\n";
    oss << "</device_data>\n";
    return oss.str();
}

// JSON command response stub (simulate device command execution)
std::string command_response_json(const std::string& action) {
    std::ostringstream oss;
    oss << "{\n";
    oss << "  \"command\": \"" << action << "\",\n";
    oss << "  \"status\": \"accepted\",\n";
    oss << "  \"executed\": true,\n";
    oss << "  \"timestamp\": " << std::time(nullptr) << "\n";
    oss << "}";
    return oss.str();
}

// Read POST body from client
std::string read_post_body(int sock, const std::map<std::string, std::string>& headers) {
    auto it = headers.find("content-length");
    if (it == headers.end()) return "";
    int len = std::stoi(it->second);
    return recv_n(sock, len);
}

// Extract action command from JSON body (very basic, not a full parser)
std::string extract_action_from_json(const std::string& body) {
    auto pos = body.find("\"action\"");
    if (pos == std::string::npos) return "unknown";
    auto colon = body.find(':', pos);
    if (colon == std::string::npos) return "unknown";
    auto quote1 = body.find('"', colon);
    if (quote1 == std::string::npos) return "unknown";
    auto quote2 = body.find('"', quote1 + 1);
    if (quote2 == std::string::npos) return "unknown";
    return body.substr(quote1 + 1, quote2 - quote1 - 1);
}

// Send HTTP response
void send_response(int client, const std::string& status, const std::string& content_type, const std::string& body) {
    std::ostringstream oss;
    oss << "HTTP/1.1 " << status << "\r\n";
    oss << "Content-Type: " << content_type << "\r\n";
    oss << "Content-Length: " << body.size() << "\r\n";
    oss << "Connection: close\r\n";
    oss << "\r\n";
    send(client, oss.str().c_str(), oss.str().size(), 0);
    send(client, body.c_str(), body.size(), 0);
}

// Server main loop
void run_server(const std::string& host, int port) {
#ifdef _WIN32
    WSADATA wsa;
    WSAStartup(MAKEWORD(2,2), &wsa);
#endif
    int server_fd = socket(AF_INET, SOCK_STREAM, 0);
    if (server_fd < 0) {
        perror("socket");
        exit(1);
    }
    int yes = 1;
    setsockopt(server_fd, SOL_SOCKET, SO_REUSEADDR, (const char*)&yes, sizeof(yes));
    struct sockaddr_in addr;
    addr.sin_family = AF_INET;
    addr.sin_addr.s_addr = (host == "0.0.0.0") ? INADDR_ANY : inet_addr(host.c_str());
    addr.sin_port = htons(port);
    if (bind(server_fd, (struct sockaddr*)&addr, sizeof(addr)) < 0) {
        perror("bind");
        exit(1);
    }
    if (listen(server_fd, 10) < 0) {
        perror("listen");
        exit(1);
    }
    std::cout << "HTTP server running on " << host << ":" << port << std::endl;
    while (true) {
        struct sockaddr_in client_addr;
        socklen_t ca_len = sizeof(client_addr);
        int client = accept(server_fd, (struct sockaddr*)&client_addr, &ca_len);
        if (client < 0) continue;
        // Parse request line
        std::string req_line;
        if (recv_line(client, req_line) <= 0) { closesocket(client); continue; }
        std::istringstream iss(req_line);
        std::string method, full_path, http_ver;
        iss >> method >> full_path >> http_ver;
        if (method.empty() || full_path.empty()) { closesocket(client); continue; }
        std::map<std::string, std::string> headers = parse_headers(client);
        // Handle GET /data
        if (method == "GET" && full_path.find("/data") == 0) {
            std::map<std::string, std::string> qp = parse_query(full_path);
            std::string xml = generate_device_xml(qp);
            send_response(client, "200 OK", "application/xml", xml);
        }
        // Handle POST /commands
        else if (method == "POST" && full_path == "/commands") {
            std::string body = read_post_body(client, headers);
            std::string action = extract_action_from_json(body);
            std::string resp = command_response_json(action);
            send_response(client, "200 OK", "application/json", resp);
        }
        // 404 for other paths
        else {
            std::string notf = "Not Found";
            send_response(client, "404 Not Found", "text/plain", notf);
        }
#ifdef _WIN32
        closesocket(client);
#else
        close(client);
#endif
    }
}

// Read environment variable or use default
std::string getenv_or_default(const char* var, const char* defval) {
    const char* val = std::getenv(var);
    return val ? val : defval;
}

int main() {
    std::string host = getenv_or_default("SHIFU_HTTP_HOST", "0.0.0.0");
    int port = std::stoi(getenv_or_default("SHIFU_HTTP_PORT", "8080"));
    run_server(host, port);
    return 0;
}