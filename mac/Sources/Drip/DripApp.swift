import SwiftUI
import AppKit

@main
struct DripApp: App {
    @StateObject private var settings: Settings
    @StateObject private var engine: Engine

    init() {
        let s = Settings()
        _settings = StateObject(wrappedValue: s)
        _engine = StateObject(wrappedValue: Engine(settings: s))
        // Menu bar only: no Dock icon, no main window.
        NSApplication.shared.setActivationPolicy(.accessory)
    }

    var body: some Scene {
        MenuBarExtra {
            Panel().environmentObject(settings).environmentObject(engine)
        } label: {
            HStack(spacing: 4) {
                Image(systemName: engine.rainingNow ? "cloud.rain.fill" : engine.coming ? "cloud.drizzle" : settings.clothesOut ? "tshirt" : "sun.max")
                if !engine.barLabel.isEmpty { Text(engine.barLabel) }
            }
        }
        .menuBarExtraStyle(.window)
    }
}

// Tokens from the design canvas
let paper = Color(red: 0.984, green: 0.980, blue: 0.969)
let ink = Color(red: 0.106, green: 0.141, blue: 0.188)
let muted = Color(red: 0.416, green: 0.447, blue: 0.502)
let peg = Color(red: 0.914, green: 0.635, blue: 0.231)
let skyClear = Color(red: 0.839, green: 0.925, blue: 0.973)
let skyComing = Color(red: 0.247, green: 0.353, blue: 0.471)
let skyRaining = Color(red: 0.169, green: 0.227, blue: 0.310)
let skyNoData = Color(red: 0.867, green: 0.878, blue: 0.859)

struct Panel: View {
    @EnvironmentObject var settings: Settings
    @EnvironmentObject var engine: Engine
    @State private var showSettings = false

    var sky: Color { engine.snap == nil ? skyNoData : engine.rainingNow ? skyRaining : engine.coming ? skyComing : skyClear }
    var dark: Bool { engine.rainingNow || engine.coming }

    var body: some View {
        VStack(spacing: 0) {
            // Sky band
            VStack(alignment: .leading, spacing: 4) {
                Text((engine.rainingNow ? "AT YOUR BUILDING" : "OVER YOUR BUILDING") + " · " + settings.placeName.uppercased())
                    .font(.system(size: 11, weight: .semibold)).foregroundColor(dark ? .white.opacity(0.8) : muted)
                Spacer(minLength: 28)
                Text(engine.headline).font(.system(size: 24, weight: .bold)).foregroundColor(dark ? .white : ink)
                Text(engine.subline).font(.system(size: 12)).foregroundColor(dark ? .white.opacity(0.85) : muted)
            }
            .padding(16).frame(maxWidth: .infinity, alignment: .leading).background(sky)

            VStack(spacing: 10) {
                // Clothes row
                HStack {
                    VStack(alignment: .leading, spacing: 2) {
                        Text("Balcony").font(.system(size: 14, weight: .semibold)).foregroundColor(ink)
                        Text(settings.clothesOut ? "Hung \(elapsed(settings.outSince)) ago" : "Nothing hanging").font(.system(size: 12)).foregroundColor(muted)
                    }
                    Spacer()
                    Toggle("", isOn: $settings.clothesOut).toggleStyle(.switch).tint(peg).labelsHidden()
                }
                .padding(12).background(engine.coming && settings.clothesOut ? Color(red: 1, green: 0.965, blue: 0.89) : .white).cornerRadius(12)

                if engine.coming && settings.clothesOut {
                    Button { settings.clothesOut = false } label: {
                        Text("I've taken them in").frame(maxWidth: .infinity).padding(.vertical, 8)
                    }.buttonStyle(.borderedProminent).tint(ink)
                }

                if let s = engine.snap {
                    HStack(spacing: 6) {
                        stat("\(Int(s.tempC ?? 0))°", "Temp")
                        stat("\(s.humidity ?? 0)%", "Humidity")
                        stat("\(Int(s.windKmh ?? 0)) km/h", "From \(s.windDir ?? "–")")
                    }
                }

                HStack {
                    Text(engine.snap.map { "Updated \(elapsed($0.fetchedAt)) ago" } ?? "")
                        .font(.system(size: 11)).foregroundColor(muted)
                    Spacer()
                    Button(engine.loading ? "Checking…" : "Check now") { Task { await engine.refresh() } }.disabled(engine.loading).font(.system(size: 11))
                }

                Divider()
                HStack {
                    Button("Settings…") { showSettings.toggle() }.buttonStyle(.plain).foregroundColor(muted).font(.system(size: 11))
                    Spacer()
                    Button("Quit Drip") { NSApplication.shared.terminate(nil) }.buttonStyle(.plain).foregroundColor(muted).font(.system(size: 11))
                }
                if showSettings { SettingsForm() }
            }
            .padding(14).background(paper)
        }
        .frame(width: 320)
    }

    func stat(_ big: String, _ small: String) -> some View {
        VStack(alignment: .leading, spacing: 1) {
            Text(big).font(.system(size: 16, weight: .bold)).foregroundColor(ink)
            Text(small).font(.system(size: 10)).foregroundColor(muted)
        }.frame(maxWidth: .infinity, alignment: .leading).padding(8).background(.white).cornerRadius(10)
    }
}

struct SettingsForm: View {
    @EnvironmentObject var settings: Settings
    @EnvironmentObject var engine: Engine
    @State private var lat = ""
    @State private var lon = ""

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            TextField("Area name", text: $settings.placeName)
            HStack {
                TextField("Latitude", text: $lat).onAppear { lat = String(settings.lat) }
                TextField("Longitude", text: $lon).onAppear { lon = String(settings.lon) }
            }
            Picker("Drying spot", selection: $settings.spotCover) {
                Text("Open").tag(0); Text("Partly covered").tag(1); Text("Covered").tag(2)
            }
            HStack {
                Text("Warn").font(.system(size: 11)).foregroundColor(muted)
                Slider(value: Binding(get: { Double(settings.sensitivity) }, set: { settings.sensitivity = Int($0) }), in: 0...100).tint(peg)
                Text("\(settings.probabilityThreshold)%").font(.system(size: 11)).foregroundColor(muted).frame(width: 32)
            }
            Toggle("Only when clothes are out", isOn: $settings.onlyWhenOut)
            Toggle("Morning summary", isOn: $settings.morningSummary)
            Toggle("Dusk reminder", isOn: $settings.duskReminder)
            TextField("Weather Union API key (optional)", text: $settings.wuKey)
            TextField("WU locality IDs, upwind first", text: $settings.wuLocalities)
            Button("Apply") {
                if let v = Double(lat) { settings.lat = v }
                if let v = Double(lon) { settings.lon = v }
                Task { await engine.refresh() }
            }.buttonStyle(.borderedProminent).tint(ink)
        }
        .font(.system(size: 12))
        .textFieldStyle(.roundedBorder)
    }
}

func elapsed(_ since: Date) -> String {
    let m = Int(Date().timeIntervalSince(since) / 60)
    return m < 60 ? "\(m) min" : "\(m / 60) h \(m % 60) min"
}
