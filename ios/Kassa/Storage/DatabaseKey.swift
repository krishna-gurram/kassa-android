import Foundation
import Security

/// Where the database key is kept. The app uses the Keychain; tests use an in-memory store.
protocol KeyStore {
    /// nil when no key is stored. Throws when the store can't be read.
    func read() throws -> Data?
    func write(_ key: Data) throws
    func delete() throws
}

/// The database key of an existing database can't be found or used. A new key would never open the data, so the caller
/// stops and asks (Android's `KeyUnreadable`).
struct KeyUnreadable: Error {}

struct KeychainError: Error {
    let status: OSStatus
}

/// What to do about the key, given what exists. The same rule as Android's `KeyPolicy`.
enum KeyAction: Equatable {
    case useStored
    case createNew
    case cannotOpen
}

enum KeyPolicy {
    /// Never makes a new key over an existing database: a lost key would turn into "file is not a database" on every start.
    static func decide(databaseExists: Bool, keyStored: Bool, storedKeyUsable: Bool) -> KeyAction {
        if keyStored && storedKeyUsable { return .useStored }
        if databaseExists { return .cannotOpen }
        return .createNew
    }
}

/// The key as the Keychain stores it: a generic password under service "app.kassa.money", account "database-key".
///
/// Accessible `AfterFirstUnlockThisDeviceOnly` rather than `WhenUnlockedThisDeviceOnly`: the database file is protected
/// `completeUntilFirstUserAuthentication` (readable after the first unlock until the next restart), and the due notices
/// are planned to refresh from a background task that can run while the phone is locked. A key that needs an unlocked phone
/// would make that task fail. `ThisDeviceOnly` keeps the key out of iCloud Keychain and out of backups restored to another
/// phone (the data would not open there anyway; a Kassa backup file is how data moves).
struct KeychainKeyStore: KeyStore {

    static let service = "app.kassa.money"
    static let account = "database-key"

    private var identity: [String: Any] {
        return [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: KeychainKeyStore.service,
            kSecAttrAccount as String: KeychainKeyStore.account,
        ]
    }

    func read() throws -> Data? {
        var query = identity
        query[kSecReturnData as String] = true
        query[kSecMatchLimit as String] = kSecMatchLimitOne
        var item: CFTypeRef?
        let status = SecItemCopyMatching(query as CFDictionary, &item)
        if status == errSecItemNotFound { return nil }
        guard status == errSecSuccess, let data = item as? Data else { throw KeychainError(status: status) }
        return data
    }

    func write(_ key: Data) throws {
        // Replace, never duplicate: delete what is there (no error if nothing is), then add.
        try delete()
        var attributes = identity
        attributes[kSecValueData as String] = key
        attributes[kSecAttrAccessible as String] = kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
        let status = SecItemAdd(attributes as CFDictionary, nil)
        guard status == errSecSuccess else { throw KeychainError(status: status) }
    }

    func delete() throws {
        let status = SecItemDelete(identity as CFDictionary)
        guard status == errSecSuccess || status == errSecItemNotFound else { throw KeychainError(status: status) }
    }
}

enum DatabaseKey {

    static let size = 32

    /// The 32-byte key for the database. `databaseExists` is whether a database with data is on the phone. Makes and stores a
    /// new random key only when there is no data to lose; otherwise a missing or damaged key throws `KeyUnreadable`.
    static func get(store: KeyStore, databaseExists: Bool) throws -> Data {
        // A store that can't be read counts as "no usable key": with data on the phone that is a stop, without it a new key.
        let stored: Data? = try? store.read()
        let usable = stored?.count == size
        switch KeyPolicy.decide(databaseExists: databaseExists, keyStored: stored != nil, storedKeyUsable: usable) {
        case .useStored:
            if let stored = stored { return stored }
            throw KeyUnreadable()
        case .cannotOpen:
            throw KeyUnreadable()
        case .createNew:
            let key = try randomKey()
            try store.write(key)
            return key
        }
    }

    private static func randomKey() throws -> Data {
        var bytes = [UInt8](repeating: 0, count: size)
        let status = SecRandomCopyBytes(kSecRandomDefault, size, &bytes)
        guard status == errSecSuccess else { throw KeychainError(status: status) }
        return Data(bytes)
    }
}
