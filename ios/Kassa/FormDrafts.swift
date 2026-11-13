import SwiftUI

/// One unfinished form, as plain values a scene can keep. A draft holds what the person typed or chose (text, days, switches, chip
/// choices, account and category ids) and never a secret (no password is ever in a draft) nor a transient screen (which picker is open).
protocol FormDraft: Codable, Equatable {}

/// The scene's kept copy of every unfinished form: one JSON text (`SceneStorage` in `AppShell`) holding one JSON text per form, by key
/// (`form:target`, for example `payCard:12`). It reads and writes nothing else: a text that does not decode is dropped, a form that
/// is saved, cancelled or swiped away is cleared by its owner, and ids a draft points at are checked by the form before it uses them
/// (`FormDraftIds.keep`), so a draft never crashes anything and never brings back what is gone. At most [limit] forms are kept.
final class FormDraftStore: ObservableObject {

    static let limit = 12

    private var items: [String: String] = [:]
    private var order: [String] = []
    private var adopted = false

    /// Called with the whole kept text after every change (`AppShell` points it at its `SceneStorage`).
    var persist: (String) -> Void = { _ in }

    init() {}

    /// Starts from what the scene kept; only the first call does anything, so the store never goes back to an older text.
    func adopt(_ text: String) {
        if adopted { return }
        adopted = true
        guard let data = text.data(using: .utf8),
              let wrapper = try? JSONDecoder().decode(Wrapper.self, from: data), wrapper.v == 1 else { return }
        for key in wrapper.keys where wrapper.items[key] != nil {
            items[key] = wrapper.items[key]
            order.append(key)
        }
    }

    /// The kept text of every form, in the order they were last changed.
    var encoded: String {
        if order.isEmpty { return "" }
        let wrapper = Wrapper(v: 1, keys: order, items: items)
        guard let data = try? JSONEncoder().encode(wrapper), let text = String(data: data, encoding: .utf8) else { return "" }
        return text
    }

    var keys: [String] { return order }

    /// The key of a form: its name and what it is about (an account id, a person id; 0 for a new one with no target).
    static func key(_ form: String, _ target: Int64 = 0) -> String {
        return form + ":" + String(target)
    }

    /// The draft kept under [key], or nil when there is none or it no longer decodes (a text from another version is dropped).
    func load<D: FormDraft>(_ type: D.Type, key: String) -> D? {
        guard let text = items[key] else { return nil }
        guard let data = text.data(using: .utf8), let draft = try? JSONDecoder().decode(D.self, from: data) else {
            remove(key)
            return nil
        }
        return draft
    }

    func save<D: FormDraft>(_ draft: D, key: String) {
        guard let data = try? JSONEncoder().encode(draft), let text = String(data: data, encoding: .utf8) else { return }
        if items[key] == text { return }
        items[key] = text
        order.removeAll { $0 == key }
        order.append(key)
        while order.count > FormDraftStore.limit {
            let oldest = order.removeFirst()
            items[oldest] = nil
        }
        persist(encoded)
    }

    func clear(_ key: String) {
        if items[key] == nil { return }
        remove(key)
        persist(encoded)
    }

    /// Clears every draft of one form, whatever its target (`payCard:`): a form that closes leaves no draft behind.
    func clear(form: String) {
        let prefix = form + ":"
        let doomed = order.filter { $0.hasPrefix(prefix) }
        if doomed.isEmpty { return }
        for key in doomed { remove(key) }
        persist(encoded)
    }

    func clearAll() {
        if order.isEmpty { return }
        items = [:]
        order = []
        persist("")
    }

    private func remove(_ key: String) {
        items[key] = nil
        order.removeAll { $0 == key }
    }

    private struct Wrapper: Codable {
        let v: Int
        let keys: [String]
        let items: [String: String]
    }
}

/// Which ids a draft may bring back. An account, person or category that is gone since the draft was kept becomes 0 (nothing
/// chosen), as if it had never been picked.
enum FormDraftIds {

    static func keep(_ id: Int64, exists: (Int64) -> Bool) -> Int64 {
        if id <= 0 { return 0 }
        return exists(id) ? id : 0
    }

    /// A kept epoch day that is far from today (more than ten years either way) is not believable; the form's own day is used instead.
    static func day(_ kept: Int, fallback: Int) -> Int {
        if kept == 0 { return fallback }
        if abs(kept - fallback) > 3660 { return fallback }
        return kept
    }
}

private struct FormDraftsKey: EnvironmentKey {
    /// A store nothing persists: a form built outside `AppShell` (a test, a preview) still works, it just keeps nothing.
    static let defaultValue = FormDraftStore()
}

extension EnvironmentValues {
    var formDrafts: FormDraftStore {
        get { return self[FormDraftsKey.self] }
        set { self[FormDraftsKey.self] = newValue }
    }
}

/// Keeps one form's draft: the first time the form shows it remembers how it opened, brings back a kept draft for [key] over it, and
/// from then on keeps what is typed (or forgets it when the form is back to how it opened). The owner clears it on close.
struct FormDraftKeeper<D: FormDraft>: ViewModifier {
    let key: String
    let current: D
    /// False while the form is still waiting for what it opens on (an edit sheet loads its record first): the form's "how it opened"
    /// is taken once this is true.
    let ready: Bool
    let apply: (D) -> Void

    @Environment(\.formDrafts) private var drafts
    @State private var opened: D?

    private func arm() {
        if opened != nil || !ready { return }
        opened = current
        if let kept = drafts.load(D.self, key: key), kept != current { apply(kept) }
    }

    func body(content: Content) -> some View {
        content
            .onAppear { arm() }
            .onChange(of: ready) { _ in arm() }
            .onChange(of: current) { next in
                guard let base = opened else { return }
                if next == base { drafts.clear(key) } else { drafts.save(next, key: key) }
            }
    }
}

extension View {
    /// Keeps [current] as a draft under [key] while this form is open; [apply] puts a kept draft back when the form opens.
    func keepsDraft<D: FormDraft>(_ key: String, current: D, ready: Bool = true, apply: @escaping (D) -> Void) -> some View {
        return modifier(FormDraftKeeper(key: key, current: current, ready: ready, apply: apply))
    }
}

/// Clears a form's drafts the moment its sheet goes away (saved, cancelled with the back arrow, or swiped down): [open] is whether the
/// sheet is showing.
struct DraftSweeper: ViewModifier {
    let form: String
    let open: Bool
    @Environment(\.formDrafts) private var drafts

    func body(content: Content) -> some View {
        content.onChange(of: open) { nowOpen in
            if !nowOpen { drafts.clear(form: form) }
        }
    }
}

extension View {
    func sweepsDraft(_ form: String, open: Bool) -> some View {
        return modifier(DraftSweeper(form: form, open: open))
    }
}

/// What a form may bring back from a draft: ids that still exist in the ledger the form shows (0 for anything that is gone).
extension AppModel {

    func keepAccount(_ id: Int64) -> Int64 {
        return FormDraftIds.keep(id, exists: { self.picks?.accountChoice(id: $0) != nil })
    }

    func keepCategory(_ id: Int64) -> Int64 {
        return FormDraftIds.keep(id, exists: { self.picks?.categoryChoice(id: $0) != nil })
    }

    func keepPerson(_ id: Int64) -> Int64 {
        return FormDraftIds.keep(id, exists: { !(self.picks?.personName(id: $0) ?? "").isEmpty })
    }
}
