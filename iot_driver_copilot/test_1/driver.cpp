#include <iostream>
#include <cstdlib>
#include <string>
#include <vector>
#include <map>
#include <sstream>
#include <algorithm>
#include <cstring>
#include <ctime>
#include <cstdio>
#include <unistd.h>
#include <netinet/in.h>
#include <sys/socket.h>

// Helper: Get environment variable or default
std::string getenv_or(const char* env, const std::string& def) {
    const char* val = getenv(env);
    return val ? std::string(val) : def;
}

// Device dummy data (simulate)
std::string get_device_data_xml(const std::map<std::string, std::string>& query) {
    std::stringstream xml;
    xml << "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n";
    xml << "<DeviceData>\n";
    xml << "  <DeviceName>test1</DeviceName>\n";
    xml << "  <Model>dsa</Model>\n";
    xml << "  <Manufacturer>adsads</Manufacturer>\n";
    xml << "  <Type>ads</Type>\n";
    xml << "  <Status>online</Status>\n";
    xml << "  <Timestamp>" << std::time(nullptr) << "</Timestamp>\n";
    // Simulate filter, limit, offset
    if (query.count("filter")) {
        xml << "  <Filter>" << query.at("filter") << "</Filter>\n";
    }
    if (query.count("limit")) {
        xml << "  <Limit>" << query.at("limit") << "</Limit>\n";
    }
    if (query.count("offset")) {
        xml << "  <Offset>" << query.at("offset") << "</Offset>\n";
    }
    xml << "</DeviceData>\n";
    return xml.str();
}

// Device dummy command processing
std::string process_device_command(const std::string& payload) {
    // Simulate parsing and result
    std::stringstream json;
    json << "{\n";
    json << "  \"success\": true,\n";
    json << "  \"accepted\": true,\n";
    json << "  \"execution_status\": \"done\",\n";
    json << "  \"received_command\": \"" << payload << "\"\n";
    json << "}\n";
    return json.str();
}

// URL decode
std::string url_decode(const std::string& s) {
    std::string result;
    char ch;
    int i, ii;
    for (i=0; i<s.length(); i++) {
        if (int(s[i]) == int('%')) {
            sscanf(s.substr(i+1,2).c_str(), "%x", &ii);
            ch = static_cast<char>(ii);
            result += ch;
            i = i+2;
        } else if (s[i]=='+') {
            result += ' ';
        } else {
            result += s[i];
        }
    }
    return result;
}

// Query string parsing
std::map<std::string, std::string> parse_query(const std::string& query) {
    std::map<std::string, std::string> qmap;
    size_t start = 0;
    while (start < query.length()) {
        size_t eq = query.find('=', start);
        if (eq == std::string::npos) break;
        size_t amp = query.find('&', eq);
        std::string key = url_decode(query.substr(start, eq-start));
        std::string val = url_decode(query.substr(eq+1, (amp==std::string::npos ? query.size() : amp)-eq-1));
        qmap[key] = val;
        if (amp == std::string::npos) break;
        start = amp + 1;
    }
    return qmap;
}

// HTTP request parsing
struct HttpRequest {
    std::string method;
    std::string path;
    std::string query;
    std::map<std::string, std::string> headers;
    std::string body;
};

HttpRequest parse_http_request(const std::string& req) {
    HttpRequest r;
    std::istringstream iss(req);
    std::string line;
    std::getline(iss, line); // request line
    size_t mpos = line.find(' ');
    size_t ppos = line.find(' ', mpos+1);
    r.method = line.substr(0, mpos);
    std::string fullpath = line.substr(mpos+1, ppos-mpos-1);
    size_t qpos = fullpath.find('?');
    if (qpos != std::string::npos) {
        r.path = fullpath.substr(0, qpos);
        r.query = fullpath.substr(qpos+1);
    } else {
        r.path = fullpath;
    }
    // headers
    while (std::getline(iss, line) && line != "\r") {
        if (line.empty() || line == "\n" || line == "\r\n") break;
        size_t cpos = line.find(':');
        if (cpos != std::string::npos) {
            std::string key = line.substr(0, cpos);
            std::string val = line.substr(cpos+1);
            val.erase(std::remove(val.begin(), val.end(), '\r'), val.end());
            val.erase(0, val.find_first_not_of(" \t"));
            r.headers[key] = val;
        }
    }
    // body
    std::string body;
    while (std::getline(iss, line)) {
        body += line + "\n";
    }
    r.body = body;
    return r;
}

// HTTP response
void send_http_response(int client, const std::string& status, const std::string& content_type, const std::string& body) {
    std::stringstream resp;
    resp << "HTTP/1.1 " << status << "\r\n";
    resp << "Content-Type: " << content_type << "\r\n";
    resp << "Access-Control-Allow-Origin: *\r\n";
    resp << "Content-Length: " << body.size() << "\r\n";
    resp << "Connection: close\r\n";
    resp << "\r\n";
    resp << body;
    send(client, resp.str().c_str(), resp.str().size(), 0);
}

// Main HTTP server loop
int main() {
    // Config from env
    std::string server_host = getenv_or("SHIFU_HTTP_SERVER_HOST", "0.0.0.0");
    int server_port = std::stoi(getenv_or("SHIFU_HTTP_SERVER_PORT", "8080"));
    // Setup socket
    int server_fd = socket(AF_INET, SOCK_STREAM, 0);
    if (server_fd == 0) {
        perror("socket failed");
        exit(EXIT_FAILURE);
    }
    int opt = 1;
    setsockopt(server_fd, SOL_SOCKET, SO_REUSEADDR, &opt, sizeof(opt));
    struct sockaddr_in address;
    address.sin_family = AF_INET;
    address.sin_addr.s_addr = server_host == "0.0.0.0" ? INADDR_ANY : inet_addr(server_host.c_str());
    address.sin_port = htons(server_port);
    if (bind(server_fd, (struct sockaddr*)&address, sizeof(address))<0) {
        perror("bind failed");
        exit(EXIT_FAILURE);
    }
    if (listen(server_fd, 10) < 0) {
        perror("listen");
        exit(EXIT_FAILURE);
    }
    std::cout << "HTTP server started on " << server_host << ":" << server_port << std::endl;

    while (1) {
        int addrlen = sizeof(address);
        int client_socket = accept(server_fd, (struct sockaddr*)&address, (socklen_t*)&addrlen);
        if (client_socket < 0) continue;
        char buffer[8192] = {0};
        int valread = read(client_socket, buffer, sizeof(buffer)-1);
        if (valread <= 0) {
            close(client_socket);
            continue;
        }
        std::string reqstr(buffer, valread);
        HttpRequest req = parse_http_request(reqstr);

        // Routing
        if (req.method == "GET" && req.path == "/data") {
            auto querymap = parse_query(req.query);
            std::string data = get_device_data_xml(querymap);
            send_http_response(client_socket, "200 OK", "application/xml", data);
        } else if (req.method == "POST" && req.path == "/commands") {
            std::string payload = req.body;
            // Remove trailing newlines
            while (!payload.empty() && (payload.back()=='\n'||payload.back()=='\r')) payload.pop_back();
            std::string resp = process_device_command(payload);
            send_http_response(client_socket, "200 OK", "application/json", resp);
        } else {
            std::string msg = "{\"error\":\"Not found\"}";
            send_http_response(client_socket, "404 Not Found", "application/json", msg);
        }
        close(client_socket);
    }
    close(server_fd);
    return 0;
}