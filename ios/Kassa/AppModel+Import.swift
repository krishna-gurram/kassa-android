import Foundation
import SwiftUI
import KassaShared

/// Where the import from another app is. `idle` means no import screen is up.
enum ImportPhase {
    case idle
    /// Copying and reading the picked file.
    case reading
    /// A Cashew backup's questions (`ImportModel.question` says which).
    case questions
    /// The plan, ready to review (`ImportModel.review`).
    case review
    case saving
    case done
    /// `ImportModel.message` says why, in plain words.
    case failed
}

/// The import from another app (Money Manager `.mmbak`, Cashew `.sql`): pick a file, read it, ask the questions a Cashew backup needs,
/// review the plan with its balance checks, then save it over what is in Kassa in one transaction. Android's `ImportViewModel` is the
/// model. Every rule is Kotlin's (`ImportSession` in :shared over :importer and :domain); this runs the file and the store on the
/// app's one queue and publishes what the screens show. A .kassa file picked here is handed on to the usual restore.
/// Failures reach the screen only as plain sentences (`Failures`, or the sentence a refused file brings).
final class ImportModel: ObservableObject {

    @Published private(set) var phase: ImportPhase = .idle
    @Published private(set) var question: ImportQuestionData?
    @Published private(set) var review: ImportReviewData?
    /// The review of what was saved, for the "Imported" screen.
    @Published private(set) var doneReview: ImportReviewData?
    @Published private(set) var message: String?
    /// True while a step is being worked out.
    @Published private(set) var busy = false
    /// The "Replace everything?" question over existing data.
    @Published var asksReplace = false
    /// The currency list, because the backup has none Kassa lists and none was picked yet.
    @Published var asksCurrency = false

    private unowned let app: AppModel
    private var session: ImportSession?   // queue only
    private var replacing = false

    init(app: AppModel) {
        self.app = app
    }

    var isActive: Bool { phase != .idle }

    /// True when the import replaces data that is there (Settings), as opposed to the first run's empty store.
    var overExistingData: Bool { replacing }

    // MARK: Reading

    /// A file picked in the file importer. [replacing] is true from Settings, over existing data.
    func begin(_ url: URL, replacing over: Bool) {
        phase = .reading
        message = nil
        question = nil
        review = nil
        replacing = over
        let given = app.inputs()
        app.queue.async {
            self.session = nil
            let phone: String = self.app.view?.currencyCode() ?? given.currency ?? ""
            do {
                let read = try ImportReader.read(url, currency: phone, zone: given.zone)
                switch read {
                case .kassa:
                    self.app.publish {
                        self.phase = .idle
                        self.app.chooseBackup(url, replacing: over)
                    }
                case .refused(let text):
                    self.publishFailure(text)
                case .session(let opened):
                    self.session = opened
                    self.showCurrent(hidden: given.hidden)
                }
            } catch {
                self.publishFailure(Failures.importFile(error))
            }
        }
    }

    // MARK: Questions

    func setAccountType(_ pk: String, _ kind: String) {
        act { $0.setAccountType(pk: pk, type: kind) }
    }

    func setLoan(_ pk: String, _ choice: String) {
        act { $0.setLoan(pk: pk, choice: choice) }
    }

    func setLeaveOut(_ pk: String, _ leaveOut: Bool) {
        act { $0.setLeaveOut(pk: pk, leaveOut: leaveOut) }
    }

    private func act(_ change: @escaping (ImportSession) -> Void) {
        let hidden = app.hideAmounts
        app.queue.async {
            guard let current = self.session else { return }
            change(current)
            self.showCurrent(hidden: hidden)
        }
    }

    /// Next question, or the review once the last one is answered.
    func goOn() {
        busy = true
        let hidden = app.hideAmounts
        app.queue.async {
            guard let current = self.session else { return }
            do {
                try current.goOn()
            } catch {
                self.publishFailure(Failures.importFile(error))
                return
            }
            self.showCurrent(hidden: hidden)
        }
    }

    /// Back one question; from the first, back to picking a file.
    func goBack() {
        let hidden = app.hideAmounts
        app.queue.async {
            guard let current = self.session else { return }
            if current.goBack() {
                self.session = nil
                self.app.publish { self.clear() }
                return
            }
            self.showCurrent(hidden: hidden)
        }
    }

    /// Drops the file and closes the import screens.
    func reset() {
        app.queue.async {
            self.session = nil
            self.app.publish { self.clear() }
        }
    }

    private func clear() {
        phase = .idle
        question = nil
        review = nil
        doneReview = nil
        message = nil
        busy = false
        asksReplace = false
        asksCurrency = false
    }

    // MARK: Saving

    /// The Import button: over existing data it asks first.
    func confirm() {
        if replacing {
            asksReplace = true
        } else {
            save(pickedCurrency: nil)
        }
    }

    /// Saves the plan over what is in Kassa, in one transaction, then reads the figures again (which also refreshes the notifications
    /// and, as a restore does, forgets what the phone remembers by old account ids). [pickedCurrency] comes from the currency list.
    func save(pickedCurrency: String?) {
        asksReplace = false
        asksCurrency = false
        phase = .saving
        let given = app.inputs()
        app.queue.async {
            guard let current = self.session else {
                self.publishFailure(String(localized: "failure.importNotSaved"))
                return
            }
            // The backup's own currency when Kassa lists it, else the one the ledger shows, else the one picked on first run.
            let detected = current.detectedCurrency()
            let kept: String? = self.app.view?.currencyCode()
            var code: String? = pickedCurrency
            if code == nil && !detected.isEmpty { code = detected }
            if code == nil { code = kept ?? given.currency }
            guard let chosen = code, !chosen.isEmpty else {
                self.app.publish {
                    self.asksCurrency = true
                    self.phase = .review
                }
                return
            }
            do {
                let saved = try current.review(hidden: false)
                try self.app.applyImport(current, currency: chosen, given)
                self.app.publish {
                    if pickedCurrency != nil { self.app.chooseCurrency(chosen) }
                    self.doneReview = saved
                    self.busy = false
                    self.phase = .done
                }
            } catch {
                self.publishFailure(Failures.importSave(error))
            }
        }
    }

    // MARK: Helpers

    /// Works out what the current step shows and publishes it. Queue only.
    func showCurrent(hidden: Bool) {
        guard let current = session else { return }
        do {
            if current.hasReview() {
                let data = try current.review(hidden: hidden)
                app.publish {
                    self.review = data
                    self.question = nil
                    self.busy = false
                    self.phase = .review
                }
            } else {
                let data = try current.question(hidden: hidden)
                app.publish {
                    self.question = data
                    self.review = nil
                    self.busy = false
                    self.phase = .questions
                }
            }
        } catch {
            publishFailure(Failures.importFile(error))
        }
    }

    private func publishFailure(_ text: String) {
        app.publish {
            self.message = text
            self.busy = false
            self.phase = .failed
        }
    }

    /// For tests: the session being asked about (queue only, so call from `app.queue`).
    var currentSession: ImportSession? { return session }
}

extension AppModel {

    /// Saves [session]'s plan over the store in one transaction and opens the app on the result. Queue only. Like a restore, it forgets what
    /// the phone remembers by account id (cleared notices, what was rung) and reads the figures again, which re-plans the notifications.
    func applyImport(_ session: ImportSession, currency: String, _ given: Inputs) throws {
        guard let opened = store else { throw LedgerStoreError.badValue }
        try opened.replaceForImport(session, currency: currency)
        DueSettings(defaults: defaults).forgetAccountReferences()
        try load(opened, given)
        finishImport()
    }
}
