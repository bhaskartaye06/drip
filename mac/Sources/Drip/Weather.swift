import Foundation

/// Same picture as the Android Snapshot, same rules in Decide.
struct Snapshot {
    var tempC: Double?
    var humidity: Int?
    var windKmh: Double?
    var windDir: String?
    var rainNowMmH: Double?
    var maxProbNext45: Int = 0
    var maxProbNext3h: Int = 0
    var minutesToRain: Int?
    var todayPeakProb: Int = 0
    var todayRainWindow: String?
    var sunset: Date?
    var upwindRaining = false
    var upwindNote: String?
    var fetchedAt = Date()
}

enum Weather {
    static func fetch(_ s: Settings) async throws -> Snapshot {
        var snap = try await openMeteo(lat: s.lat, lon: s.lon, threshold: s.probabilityThreshold)
        if !s.wuKey.isEmpty, !s.wuLocalities.isEmpty, let wu = try? await weatherUnion(s) {
            if let r = wu.first { snap.rainNowMmH = r }
            snap.upwindRaining = wu.any
            snap.upwindNote = wu.note
        }
        return snap
    }

    private static func get(_ url: String, headers: [String: String] = [:]) async throws -> Data {
        var req = URLRequest(url: URL(string: url)!)
        req.timeoutInterval = 15
        headers.forEach { req.setValue($1, forHTTPHeaderField: $0) }
        let (data, _) = try await URLSession.shared.data(for: req)
        return data
    }

    private static func openMeteo(lat: Double, lon: Double, threshold: Int) async throws -> Snapshot {
        let url = "https://api.open-meteo.com/v1/forecast?latitude=\(lat)&longitude=\(lon)" +
            "&current=temperature_2m,relative_humidity_2m,precipitation,wind_speed_10m,wind_direction_10m" +
            "&minutely_15=precipitation,precipitation_probability&forecast_minutely_15=24" +
            "&hourly=precipitation_probability&forecast_days=1&daily=sunset&timezone=Asia%2FKolkata"
        let j = try JSONSerialization.jsonObject(with: get(url)) as! [String: Any]
        let cur = j["current"] as! [String: Any]
        let m15 = j["minutely_15"] as! [String: Any]
        let times = m15["time"] as! [String]
        let prob = m15["precipitation_probability"] as! [Any]
        let prec = m15["precipitation"] as! [Any]

        let fmt = DateFormatter()
        fmt.dateFormat = "yyyy-MM-dd'T'HH:mm"
        fmt.timeZone = TimeZone(identifier: "Asia/Kolkata")
        let now = Date()

        var snap = Snapshot()
        for (k, t) in times.enumerated() {
            guard let pr = prob[k] as? Int, let d = fmt.date(from: t) else { continue }
            let mins = Int(d.timeIntervalSince(now) / 60)
            if mins < -15 { continue }
            let pc = prec[k] as? Double ?? 0
            if mins <= 45 { snap.maxProbNext45 = max(snap.maxProbNext45, pr) }
            if mins <= 180 { snap.maxProbNext3h = max(snap.maxProbNext3h, pr) }
            if snap.minutesToRain == nil, mins >= 0, pr >= threshold, pc > 0 { snap.minutesToRain = mins }
        }
        let hourly = j["hourly"] as! [String: Any]
        let hp = hourly["precipitation_probability"] as! [Any]
        let ht = hourly["time"] as! [String]
        var winStart: Date?, winEnd: Date?
        for (k, t) in ht.enumerated() {
            guard let pr = hp[k] as? Int, let d = fmt.date(from: t) else { continue }
            if d < now.addingTimeInterval(-3600) { continue }
            snap.todayPeakProb = max(snap.todayPeakProb, pr)
            if pr >= 50 { if winStart == nil { winStart = d }; winEnd = d.addingTimeInterval(3600) }
        }
        if let a = winStart, let b = winEnd {
            let f = DateFormatter(); f.dateFormat = "h a"; f.timeZone = fmt.timeZone
            snap.todayRainWindow = "\(f.string(from: a).lowercased()) to \(f.string(from: b).lowercased())"
        }
        if let daily = j["daily"] as? [String: Any], let ss = (daily["sunset"] as? [String])?.first {
            snap.sunset = fmt.date(from: ss)
        }
        snap.tempC = cur["temperature_2m"] as? Double
        snap.humidity = cur["relative_humidity_2m"] as? Int
        snap.windKmh = cur["wind_speed_10m"] as? Double
        snap.rainNowMmH = cur["precipitation"] as? Double
        if let deg = cur["wind_direction_10m"] as? Double {
            let dirs = ["N", "NE", "E", "SE", "S", "SW", "W", "NW"]
            snap.windDir = dirs[Int((deg + 22.5) / 45) % 8]
        }
        return snap
    }

    private static func weatherUnion(_ s: Settings) async throws -> (first: Double?, any: Bool, note: String?) {
        let ids = s.wuLocalities.split(separator: ",").map { $0.trimmingCharacters(in: .whitespaces) }.filter { !$0.isEmpty }
        var first: Double?; var any = false; var notes: [String] = []
        for (i, id) in ids.enumerated() {
            let data = try await get("https://www.weatherunion.com/gw/weather/external/v0/get_locality_weather_data?locality_id=\(id)",
                                     headers: ["x-zomato-api-key": s.wuKey])
            guard let j = try JSONSerialization.jsonObject(with: data) as? [String: Any],
                  let d = j["locality_weather_data"] as? [String: Any] else { continue }
            let rain = d["rain_intensity"] as? Double
            if i == 0 { first = rain }
            if let r = rain, r > 0 { any = true; notes.append("\(id) \(String(format: "%.1f", r)) mm/h") }
        }
        return (first, any, notes.isEmpty ? nil : "Rain at " + notes.joined(separator: ", "))
    }
}
