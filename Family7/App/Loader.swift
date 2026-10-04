import Foundation
import Observation

/// Laadt inhoud volgens "toon wat je hebt, ververs stil".
///
/// - begint met wat er al is (cache of snapshot van de vorige sessie);
/// - wat er staat blijft staan als verversen mislukt; een foutscherm alleen
///   als er niets te tonen is;
/// - een tweede verzoek terwijl het eerste nog loopt, start niets nieuws;
/// - na een mislukking door een wegvallend netwerk opnieuw zodra de
///   verbinding terug is (zie `networkRestored`).
@MainActor
@Observable
final class Loader<Value> {
    private(set) var value: Value?
    private(set) var isLoading = false
    private(set) var error: String?

    private let fetch: (Bool) async throws -> Value
    private var task: Task<Void, Never>?

    init(initial: Value? = nil, fetch: @escaping (_ force: Bool) async throws -> Value) {
        self.value = initial
        self.fetch = fetch
    }

    var showSkeleton: Bool { value == nil && isLoading }
    var showFullError: Bool { value == nil && !isLoading && error != nil }

    func load(force: Bool = false) {
        if task != nil {
            guard force else { return }
            task?.cancel()
        }
        isLoading = true
        error = nil
        task = Task { [weak self] in
            guard let self else { return }
            do {
                let result = try await self.fetch(force)
                try Task.checkCancellation()
                self.value = result
                self.error = nil
            } catch is CancellationError {
                return
            } catch {
                self.error = friendlyError(error)
            }
            self.isLoading = false
            self.task = nil
        }
    }

    /// Vegen om te verversen: wacht tot het klaar is, zodat de indicator klopt.
    func refresh() async {
        load(force: true)
        await task?.value
    }

    func networkRestored() {
        if error != nil { load(force: true) }
    }

    func offer(_ newValue: Value) {
        if value == nil { value = newValue }
    }
}
