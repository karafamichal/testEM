import Foundation

enum EnvFileReader {
    static func read(key: String, from path: String) -> String? {
        guard let raw = try? String(contentsOfFile: path, encoding: .utf8) else {
            return nil
        }
        for line in raw.split(separator: "\n") {
            let trimmed = line.trimmingCharacters(in: .whitespacesAndNewlines)
            if trimmed.isEmpty || trimmed.hasPrefix("#") { continue }
            guard let sep = trimmed.firstIndex(of: "=") else { continue }
            let k = String(trimmed[..<sep]).trimmingCharacters(in: .whitespacesAndNewlines)
            if k == key {
                return String(trimmed[trimmed.index(after: sep)...]).trimmingCharacters(in: .whitespacesAndNewlines)
            }
        }
        return nil
    }
}
