import Foundation
import AppKit

/// Settings, persisted in UserDefaults. Mirrors the Android Prefs.
final class Settings: ObservableObject {
    private let d = UserDefaults.standard
    @Published var lat: Double { didSet { d.set(lat, forKey: "lat") } }
    @Published var lon: Double { didSet { d.set(lon, forKey: "lon") } }
    @Published var placeName: String { didSet { d.set(placeName, forKey: "place") } }
    @Published var wuKey: String { didSet { d.set(wuKey, forKey: "wuKey") } }
    @Published var wuLocalities: String { didSet { d.set(wuLocalities, forKey: "wuLoc") } }
    @Published var clothesOut: Bool { didSet { d.set(clothesOut, forKey: "out"); if clothesOut { outSince = Date() } } }
    @Published var outSince: Date { didSet { d.set(outSince, forKey: "outSince") } }
    @Published var spotCover: Int { didSet { d.set(spotCover, forKey: "cover") } }
    @Published var sensitivity: Int { didSet { d.set(sensitivity, forKey: "sens") } }
    @Published var onlyWhenOut: Bool { didSet { d.set(onlyWhenOut, forKey: "onlyOut") } }
    @Published var morningSummary: Bool { didSet { d.set(morningSummary, forKey: "morning") } }
    @Published var duskReminder: Bool { didSet { d.set(duskReminder, forKey: "dusk") } }

    var lastRainAlertAt: Date { get { d.object(forKey: "lastRain") as? Date ?? .distantPast } set { d.set(newValue, forKey: "lastRain") } }
    var lastMorningDay: String { get { d.string(forKey: "lastMorning") ?? "" } set { d.set(newValue, forKey: "lastMorning") } }
    var lastDuskDay: String { get { d.string(forKey: "lastDusk") ?? "" } set { d.set(newValue, forKey: "lastDusk") } }

    init() {
        lat = d.object(forKey: "lat") as? Double ?? 12.9352
        lon = d.object(forKey: "lon") as? Double ?? 77.6245
        placeName = d.string(forKey: "place") ?? "Koramangala"
        wuKey = d.string(forKey: "wuKey") ?? ""
        wuLocalities = d.string(forKey: "wuLoc") ?? ""
        clothesOut = d.bool(forKey: "out")
        outSince = d.object(forKey: "outSince") as? Date ?? Date()
        spotCover = d.object(forKey: "cover") as? Int ?? 1
        sensitivity = d.object(forKey: "sens") as? Int ?? 35
        onlyWhenOut = d.object(forKey: "onlyOut") as? Bool ?? true
        morningSummary = d.object(forKey: "morning") as? Bool ?? true
        duskReminder = d.object(forKey: "dusk") as? Bool ?? true
    }

    var probabilityThreshold: Int { 30 + (sensitivity * 55) / 100 }
    var intensityFloor: Double { [0.2, 1.0, 4.0][min(max(spotCover, 0), 2)] }
}

/// Polls, decides, notifies. One instance for the app's life.
@MainActor
final class Engine: ObservableObject {
    @Published var snap: Snapshot?
    @Published var error: String?
    @Published var loading = false
    let settings: Settings
    private var timer: Timer?

    init(settings: Settings) {
        self.settings = settings
        timer = Timer.scheduledTimer(withTimeInterval: 300, repeats: true) { [weak self] _ in Task { await self?.refresh() } }
        Task { await refresh() }
    }

    func refresh() async {
        loading = true; error = nil
        do {
            let s = try await Weather.fetch(settings)
            snap = s
            decide(s)
        } catch {
            self.error = "Couldn't reach the weather service"
        }
        loading = false
    }

    var rainingNow: Bool { (snap?.rainNowMmH ?? 0) >= settings.intensityFloor }
    var coming: Bool {
        guard let s = snap, !rainingNow else { return false }
        return s.upwindRaining || (s.maxProbNext45 >= settings.probabilityThreshold && (s.minutesToRain ?? 999) <= 45)
    }

    /// Menu bar label: "Dry", "25 min", "Rain", "?"
    var barLabel: String {
        guard let s = snap else { return "?" }
        if rainingNow { return settings.clothesOut ? "Rain!" : "Rain" }
        if coming { return s.minutesToRain.map { "\($0) min" } ?? "Soon" }
        return settings.clothesOut ? "Dry" : ""
    }

    var headline: String {
        guard let s = snap else { return error ?? "Looking at the sky…" }
        if rainingNow { return "Raining now" }
        if coming { return s.minutesToRain.map { "Rain in about \($0) min" } ?? "Rain is near" }
        if settings.clothesOut { return "Good drying weather" }
        if let w = s.todayRainWindow { return "Clear until \(w.components(separatedBy: " to").first ?? "")" }
        return "Clear today"
    }

    var subline: String {
        guard let s = snap else { return "Fetching the forecast for \(settings.placeName)" }
        if rainingNow { return String(format: "%.1f mm/h at your building", s.rainNowMmH ?? 0) }
        if coming { return s.upwindNote ?? "\(s.maxProbNext45)% in the next 45 min" }
        if let w = s.todayRainWindow { return "Rain likely \(w) · \(s.todayPeakProb)%" }
        return "Rain chance today \(s.todayPeakProb)% · watching"
    }

    private func decide(_ s: Snapshot) {
        let now = Date()
        let cal = Calendar.current
        let today = ISO8601DateFormatter().string(from: cal.startOfDay(for: now))
        let hour = cal.component(.hour, from: now)

        let rainSoon = s.upwindRaining
            || (s.maxProbNext45 >= settings.probabilityThreshold && (s.minutesToRain ?? 999) <= 45)
            || ((s.rainNowMmH ?? 0) >= settings.intensityFloor)
        let gate = !settings.onlyWhenOut || settings.clothesOut
        if rainSoon && gate && now.timeIntervalSince(settings.lastRainAlertAt) > 40 * 60 {
            let title = rainingNow ? "Raining at your building now" : (s.minutesToRain.map { "Rain in about \($0) min" } ?? "Rain is reaching your area")
            let body = (settings.clothesOut ? "Clothes are out. " : "Nothing marked out. ") + (s.upwindNote ?? "\(s.maxProbNext45)% in the next 45 min.")
            Notifier.send(title: title, body: body, sound: true)
            settings.lastRainAlertAt = now
        }
        if settings.morningSummary, (7...8).contains(hour), settings.lastMorningDay != today {
            let body: String
            if let w = s.todayRainWindow { body = "Rain likely \(w) (\(s.todayPeakProb)%). Dry before that." }
            else if s.todayPeakProb >= 30 { body = "Some chance of rain later (\(s.todayPeakProb)%)." }
            else { body = "Dry all day. Good day for a wash." }
            Notifier.send(title: "Today over \(settings.placeName)", body: body, sound: false)
            settings.lastMorningDay = today
        }
        if settings.duskReminder, settings.clothesOut, let ss = s.sunset, now >= ss, hour < 22, settings.lastDuskDay != today {
            let h = Int(now.timeIntervalSince(settings.outSince) / 3600)
            Notifier.send(title: "Sun's down. Clothes still out?", body: "Out for \(h) h. Dew sets in after 8 pm.", sound: false)
            settings.lastDuskDay = today
        }
    }
}

/// Native macOS notification without needing an app bundle: goes through Notification Center via osascript.
enum Notifier {
    static func send(title: String, body: String, sound: Bool) {
        let esc = { (s: String) in s.replacingOccurrences(of: "\"", with: "\\\"") }
        var script = "display notification \"\(esc(body))\" with title \"\(esc(title))\" subtitle \"Drip\""
        if sound { script += " sound name \"Submarine\"" }
        let p = Process()
        p.executableURL = URL(fileURLWithPath: "/usr/bin/osascript")
        p.arguments = ["-e", script]
        try? p.run()
    }
}
