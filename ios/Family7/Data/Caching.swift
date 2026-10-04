import Foundation
import Network

/// Kleine cache in het geheugen met een houdbaarheidsduur.
///
/// `snapshot` geeft de laatst geladen waarde ongeacht ouderdom (genoeg om een
/// scherm meteen te vullen), `fresh` alleen als hij nog vers is. Een waarde van
/// schijf (`seed`) telt nooit als vers.
final class TimedCache<Value>: @unchecked Sendable {
    private let ttl: TimeInterval
    private let lock = NSLock()
    private var value: Value?
    private var storedAt = Date.distantPast
    private var seeded = false

    init(ttl: TimeInterval) { self.ttl = ttl }

    var snapshot: Value? { lock.withLock { value } }

    var fresh: Value? {
        lock.withLock {
            guard let value, !seeded, Date().timeIntervalSince(storedAt) < ttl else { return nil }
            return value
        }
    }

    func put(_ newValue: Value) {
        lock.withLock {
            value = newValue
            storedAt = Date()
            seeded = false
        }
    }

    /// Een waarde uit een vorige sessie; een nieuwere waarde wint.
    func seed(_ newValue: Value) {
        lock.withLock {
            guard value == nil else { return }
            value = newValue
            seeded = true
        }
    }

    func clear() {
        lock.withLock {
            value = nil
            seeded = false
            storedAt = .distantPast
        }
    }
}

/// Standaard houdbaarheid voor catalogusinhoud.
let catalogTTL: TimeInterval = 5 * 60
/// Hoe vaak de startpagina zichzelf stil ververst zolang hij zichtbaar is.
let backgroundRefreshInterval: TimeInterval = 10 * 60

/// Bewaart de laatst geladen catalogus op schijf, voor een koude start zonder
/// laadscherm. Staat in Application Support, buiten de back-up.
struct SnapshotStore: Sendable {
    private let directory: URL

    /// `folder` is alleen voor tests anders, zodat die nooit de echte catalogus wissen.
    init(folder: String = "family7_catalog") {
        let base = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
        var dir = base.appendingPathComponent(folder, isDirectory: true)
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        var values = URLResourceValues()
        values.isExcludedFromBackup = true
        try? dir.setResourceValues(values)
        directory = dir
    }

    func read<T: Decodable>(_ type: T.Type, _ name: String) -> T? {
        guard let data = try? Data(contentsOf: directory.appendingPathComponent("\(name).json")) else { return nil }
        return try? JSONDecoder().decode(type, from: data)
    }

    /// Atomisch geschreven: nooit een half snapshot.
    func write<T: Encodable>(_ value: T, _ name: String) {
        guard let data = try? JSONEncoder().encode(value) else { return }
        try? data.write(to: directory.appendingPathComponent("\(name).json"), options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication])
    }

    func clear() {
        let files = (try? FileManager.default.contentsOfDirectory(at: directory, includingPropertiesForKeys: nil)) ?? []
        files.forEach { try? FileManager.default.removeItem(at: $0) }
    }
}

/// Of het toestel internet heeft. Schermen en de speler gebruiken dit om een
/// mislukte lading vanzelf opnieuw te proberen zodra de verbinding terug is.
@MainActor
@Observable
final class NetworkMonitor {
    private(set) var isOnline = true
    /// Telt op bij elk herstel van de verbinding; schermen reageren daarop.
    private(set) var reconnects = 0

    private let monitor = NWPathMonitor()

    init() {
        monitor.pathUpdateHandler = { [weak self] path in
            let online = path.status == .satisfied
            Task { @MainActor in
                guard let self else { return }
                if online && !self.isOnline { self.reconnects += 1 }
                self.isOnline = online
            }
        }
        monitor.start(queue: DispatchQueue(label: "com.xiappdesign.family7.network"))
    }
}
