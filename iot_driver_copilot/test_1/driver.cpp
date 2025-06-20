#include <iostream>
#include <cstdlib>
#include <string>
#include <sstream>
#include <fstream>
#include <streambuf>
#include <map>
#include <vector>
#include <algorithm>
#include <cstring>
#include <ctime>
#include <sys/types.h>
#include <sys/socket.h>
#include <netinet/in.h>
#include <unistd.h>

#define DEFAULT_SERVER_HOST "0.0.0.0"
#define DEFAULT_SERVER_PORT 8080

std::string get_env(const std::string& var, const std::string& def) {
    const char* val = std::getenv(var.c_str());
    return val ? std::string(val) : def;
}

std::string xml_escape(const std::string& data) {
    std::string out;
    for(char c : data) {
        switch (c) {
            case '&': out += "&amp;"; break;
            case '<': out += "&lt;"; break;
            case '>': out += "&gt;"; break;
            case '"': out += "&quot;"; break;
            case '\'': out += "&apos;"; break;
            default: out += c;
        }
    }
    return out;
}

// Simple HTTP request parsing
struct HttpRequest {
    std::string method;
    std::string path;
    std::map<std::string, std::string> query_params;
    std::string body;
    std::map<std::string, std::string> headers;
};

void url_decode(std::string &src) {
    char *p = &src[0];
    char code[3] = {0};
    for(size_t i=0, j=0; i < src.length(); ++i, ++j) {
        if(src[i] == '%') {
            code[0] = src[i+1];
            code[1] = src[i+2];
            p[j] = (char)strtol(code, NULL, 16);
            i += 2;
        } else if(src[i] == '+') {
            p[j] = ' ';
        } else {
            p[j] = src[i];
        }
    }
}

std::map<std::string, std::string> parse_query(const std::string& query) {
    std::map<std::string, std::string> params;
    size_t start = 0, end;
    while ((end = query.find('&', start)) != std::string::npos) {
        std::string kv = query.substr(start, end - start);
        size_t eq = kv.find('=');
        if (eq != std::string::npos) {
            std::string k = kv.substr(0, eq);
            std::string v = kv.substr(eq + 1);
            url_decode(k); url_decode(v);
            params[k] = v;
        }
        start = end + 1;
    }
    if (start < query.size()) {
        std::string kv = query.substr(start);
        size_t eq = kv.find('=');
        if (eq != std::string::npos) {
            std::string k = kv.substr(0, eq);
            std::string v = kv.substr(eq + 1);
            url_decode(k); url_decode(v);
            params[k] = v;
        }
    }
    return params;
}

HttpRequest parse_http_request(const std::string& raw) {
    HttpRequest req;
    std::istringstream ss(raw);
    std::string line;
    std::getline(ss, line);
    size_t m1 = line.find(' '), m2 = line.find(' ', m1+1);
    req.method = line.substr(0, m1);
    std::string url = line.substr(m1+1, m2-m1-1);

    size_t q_pos = url.find('?');
    if (q_pos != std::string::npos) {
        req.path = url.substr(0, q_pos);
        req.query_params = parse_query(url.substr(q_pos+1));
    } else {
        req.path = url;
    }

    // Headers
    while(std::getline(ss, line) && line != "\r") {
        size_t colon = line.find(':');
        if (colon != std::string::npos) {
            std::string key = line.substr(0, colon);
            std::string value = line.substr(colon+1);
            value.erase(value.begin(), std::find_if(value.begin(), value.end(), [](int ch) { return !isspace(ch); }));
            value.erase(std::find_if(value.rbegin(), value.rend(), [](int ch) { return !isspace(ch); }).base(), value.end());
            req.headers[key] = value;
        }
    }
    // Body
    std::string body;
    while(std::getline(ss, line)) {
        body += line + "\n";
    }
    if (!body.empty() && body.back() == '\n') body.pop_back();
    req.body = body;
    return req;
}

// Simulated device data (XML formatted)
std::string get_device_data_xml(const std::map<std::string, std::string>& params) {
    std::string filter = params.count("filter") ? params.at("filter") : "";
    int limit = params.count("limit") ? std::stoi(params.at("limit")) : 10;
    int offset = params.count("offset") ? std::stoi(params.at("offset")) : 0;

    std::ostringstream xml;
    xml << "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n";
    xml << "<DeviceData>\n";
    for (int i = 0; i < limit; ++i) {
        int idx = offset + i;
        xml << "  <DataPoint>\n";
        xml << "    <ID>" << idx << "</ID>\n";
        xml << "    <Type>" << xml_escape(filter.empty() ? "adsads" : filter) << "</Type>\n";
        xml << "    <Value>" << (100 + idx) << "</Value>\n";
        xml << "    <Timestamp>" << std::time(nullptr) << "</Timestamp>\n";
        xml << "  </DataPoint>\n";
    }
    xml << "</DeviceData>\n";
    return xml.str();
}

// Simulated command execution
std::string execute_device_command(const std::string& cmd) {
    // In real driver, send command to device and process response
    std::ostringstream resp;
    resp << "{";
    resp << "\"status\":\"success\",";
    resp << "\"message\":\"Command executed: " << cmd << "\"";
    resp << "}";
    return resp.str();
}

void send_http_response(int client_sock, const std::string& status, const std::string& content_type,
                       const std::string& body, const std::map<std::string, std::string>& extra_headers = {}) {
    std::ostringstream resp;
    resp << "HTTP/1.1 " << status << "\r\n";
    resp << "Content-Type: " << content_type << "\r\n";
    resp << "Content-Length: " << body.size() << "\r\n";
    for (const auto& kv : extra_headers) {
        resp << kv.first << ": " << kv.second << "\r\n";
    }
    resp << "Connection: close\r\n\r\n";
    resp << body;
    std::string out = resp.str();
    send(client_sock, out.c_str(), out.size(), 0);
}

void handle_client(int client_sock) {
    char buffer[4096];
    ssize_t received = recv(client_sock, buffer, sizeof(buffer)-1, 0);
    if (received <= 0) {
        close(client_sock);
        return;
    }
    buffer[received] = 0;
    std::string raw(buffer);
    HttpRequest req = parse_http_request(raw);

    if (req.method == "GET" && req.path == "/data") {
        std::string xml = get_device_data_xml(req.query_params);
        send_http_response(client_sock, "200 OK", "application/xml", xml);
    } else if (req.method == "POST" && req.path == "/commands") {
        // Accept XML or JSON, but here we only process string as command
        std::string cmd = req.body;
        std::string result = execute_device_command(cmd);
        send_http_response(client_sock, "200 OK", "application/json", result);
    } else {
        std::string msg = "{\"error\":\"Not found\"}";
        send_http_response(client_sock, "404 Not Found", "application/json", msg);
    }
    close(client_sock);
}

int main() {
    std::string host = get_env("SERVER_HOST", DEFAULT_SERVER_HOST);
    int port = std::stoi(get_env("SERVER_PORT", std::to_string(DEFAULT_SERVER_PORT)));

    int server_sock = socket(AF_INET, SOCK_STREAM, 0);
    if (server_sock < 0) {
        std::cerr << "Failed to create socket\n";
        return 1;
    }

    int opt = 1;
    setsockopt(server_sock, SOL_SOCKET, SO_REUSEADDR, &opt, sizeof(opt));

    sockaddr_in addr;
    addr.sin_family = AF_INET;
    addr.sin_addr.s_addr = inet_addr(host.c_str());
    addr.sin_port = htons(port);

    if (bind(server_sock, (struct sockaddr*)&addr, sizeof(addr)) < 0) {
        std::cerr << "Bind failed\n";
        close(server_sock);
        return 1;
    }

    if (listen(server_sock, 16) < 0) {
        std::cerr << "Listen failed\n";
        close(server_sock);
        return 1;
    }

    std::cout << "HTTP server listening on " << host << ":" << port << std::endl;

    while (true) {
        sockaddr_in client_addr;
        socklen_t client_len = sizeof(client_addr);
        int client_sock = accept(server_sock, (struct sockaddr*)&client_addr, &client_len);
        if (client_sock < 0) continue;
        handle_client(client_sock);
    }

    close(server_sock);
    return 0;
}