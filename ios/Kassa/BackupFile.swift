import Foundation
import CryptoKit
import CommonCrypto
import Compression

/// Why a .kassa file could not be opened. The wording is Android's (`BackupFile` in the Android app).
enum BackupError: Error, Equatable {
    case notABackup
    case needsPassword
    case wrongPassword
    case tooLarge

    var message: String {
        switch self {
        case .notABackup: return "This file isn't a Kassa backup."
        case .needsPassword: return "This backup has a password. Enter it, then pick the file again."
        case .wrongPassword: return "That password doesn't open this backup."
        case .tooLarge: return "This backup is too large to open on this phone."
        }
    }
}

/// A failure while making a file that the person can do nothing about (the system's random numbers or key derivation).
enum BackupWriteError: Error {
    case randomFailed
    case keyFailed
    case compressionFailed

    var message: String { "Couldn't save the backup. Try again." }
}

/// The bytes of a .kassa backup file around its JSON text, as in docs/BACKUP_FORMAT.md:
/// "KASSA0" and the gzipped JSON, or "KASSA1", a 16-byte salt, a 12-byte IV, then AES-256-GCM over the gzipped JSON with the
/// 16-byte tag last. The key is PBKDF2-HMAC-SHA256 of the password (UTF-8), 210,000 iterations.
/// Backups are tens of megabytes at most, so this works on whole `Data` values instead of streams.
enum BackupFile {

    /// The most JSON a backup may unpack to, the same as Android.
    static let maxJSONBytes = 256 * 1024 * 1024

    private static let plainHeader: [UInt8] = Array("KASSA0".utf8)
    private static let protectedHeader: [UInt8] = Array("KASSA1".utf8)
    private static let saltSize = 16
    private static let ivSize = 12
    private static let tagSize = 16
    private static let iterations = 210_000

    // MARK: Reading

    /// The JSON text inside `bytes`. Throws `BackupError`. A protected file's text is only produced after its tag has checked.
    static func open(_ bytes: Data, password: String?) throws -> String {
        let all = [UInt8](bytes)
        guard all.count >= plainHeader.count else { throw BackupError.notABackup }
        let head = Array(all[0..<6])
        let isProtected: Bool
        if head == plainHeader {
            isProtected = false
        } else if head == protectedHeader {
            isProtected = true
        } else {
            throw BackupError.notABackup
        }

        let packed: [UInt8]
        if isProtected {
            guard all.count >= 6 + saltSize + ivSize else { throw BackupError.notABackup }
            guard let password = password, !password.isEmpty else { throw BackupError.needsPassword }
            let salt = Array(all[6..<(6 + saltSize)])
            let iv = Array(all[(6 + saltSize)..<(6 + saltSize + ivSize)])
            let sealed = Array(all[(6 + saltSize + ivSize)...])
            // A missing tag, a changed byte or a wrong password all end here.
            guard sealed.count >= tagSize else { throw BackupError.wrongPassword }
            let key = SymmetricKey(data: Data(try deriveKey(password: password, salt: salt)))
            let ciphertext = Array(sealed[0..<(sealed.count - tagSize)])
            let tag = Array(sealed[(sealed.count - tagSize)...])
            do {
                let box = try AES.GCM.SealedBox(nonce: AES.GCM.Nonce(data: Data(iv)), ciphertext: Data(ciphertext), tag: Data(tag))
                packed = [UInt8](try AES.GCM.open(box, using: key))
            } catch {
                throw BackupError.wrongPassword
            }
        } else {
            packed = Array(all[6...])
        }

        do {
            let json = try gunzip(packed, limit: maxJSONBytes)
            return String(decoding: json, as: UTF8.self)
        } catch GzipFailure.tooLarge {
            throw BackupError.tooLarge
        } catch {
            // A damaged or cut-short file. For a protected one the likeliest cause is the password, as on Android.
            throw isProtected ? BackupError.wrongPassword : BackupError.notABackup
        }
    }

    // MARK: Writing

    /// Wraps `json`. A nil or empty `password` means a file anyone can open. Salt and IV are new every time.
    static func make(json: String, password: String?) throws -> Data {
        let body = try gzip(Array(json.utf8))
        guard let password = password, !password.isEmpty else {
            return Data(plainHeader + body)
        }
        let salt = try randomBytes(saltSize)
        let iv = try randomBytes(ivSize)
        let key = SymmetricKey(data: Data(try deriveKey(password: password, salt: salt)))
        let box = try AES.GCM.seal(Data(body), using: key, nonce: AES.GCM.Nonce(data: Data(iv)))
        return Data(protectedHeader + salt + iv) + box.ciphertext + box.tag
    }

    // MARK: Key and random bytes

    private static func deriveKey(password: String, salt: [UInt8]) throws -> [UInt8] {
        let passwordBytes = Array(password.utf8)
        var derived = [UInt8](repeating: 0, count: 32)
        let status: Int32 = passwordBytes.withUnsafeBytes { pw in
            salt.withUnsafeBufferPointer { saltPointer in
                derived.withUnsafeMutableBufferPointer { out in
                    CCKeyDerivationPBKDF(
                        CCPBKDFAlgorithm(kCCPBKDF2),
                        pw.bindMemory(to: Int8.self).baseAddress, passwordBytes.count,
                        saltPointer.baseAddress, salt.count,
                        CCPseudoRandomAlgorithm(kCCPRFHmacAlgSHA256),
                        UInt32(iterations),
                        out.baseAddress, out.count
                    )
                }
            }
        }
        guard status == Int32(kCCSuccess) else { throw BackupWriteError.keyFailed }
        return derived
    }

    private static func randomBytes(_ count: Int) throws -> [UInt8] {
        var bytes = [UInt8](repeating: 0, count: count)
        let status = SecRandomCopyBytes(kSecRandomDefault, count, &bytes)
        guard status == errSecSuccess else { throw BackupWriteError.randomFailed }
        return bytes
    }

    // MARK: gzip (RFC 1952)

    private enum GzipFailure: Error {
        case damaged
        case tooLarge
    }

    /// Apple's COMPRESSION_ZLIB is raw deflate (RFC 1951), so the gzip header and trailer are handled here.
    /// The trailer's size field says how much the JSON unpacks to; it is checked against `limit` before anything is
    /// unpacked, and the unpacked bytes are checked against the trailer's CRC-32, so a lying size ends as "damaged".
    private static func gunzip(_ data: [UInt8], limit: Int) throws -> [UInt8] {
        // Smallest possible member: 10-byte header, an empty deflate block (2 bytes), 8-byte trailer.
        guard data.count >= 10 + 8 else { throw GzipFailure.damaged }
        guard data[0] == 0x1f, data[1] == 0x8b, data[2] == 8 else { throw GzipFailure.damaged }
        let flags = data[3]
        guard flags & 0xE0 == 0 else { throw GzipFailure.damaged } // reserved bits must be zero
        var at = 10
        let end = data.count - 8
        if flags & 0x04 != 0 { // FEXTRA: two-byte length, then that many bytes
            guard at + 2 <= end else { throw GzipFailure.damaged }
            let extra = Int(data[at]) | (Int(data[at + 1]) << 8)
            at += 2 + extra
        }
        if flags & 0x08 != 0 { at = try skipZeroTerminated(data, from: at, end: end) } // FNAME
        if flags & 0x10 != 0 { at = try skipZeroTerminated(data, from: at, end: end) } // FCOMMENT
        if flags & 0x02 != 0 { at += 2 } // FHCRC
        guard at <= end else { throw GzipFailure.damaged }

        let crc = readUInt32(data, end)
        let size = Int(readUInt32(data, end + 4))
        if size > limit { throw GzipFailure.tooLarge }
        let deflated = Array(data[at..<end])
        if size == 0 {
            // An empty text: nothing to unpack, but the CRC of nothing must still match.
            guard crc == 0 else { throw GzipFailure.damaged }
            return []
        }
        guard !deflated.isEmpty else { throw GzipFailure.damaged }

        var out = [UInt8](repeating: 0, count: size)
        let written = out.withUnsafeMutableBufferPointer { dst in
            deflated.withUnsafeBufferPointer { src in
                compression_decode_buffer(dst.baseAddress!, size, src.baseAddress!, src.count, nil, COMPRESSION_ZLIB)
            }
        }
        guard written == size, crc32(out) == crc else { throw GzipFailure.damaged }
        return out
    }

    private static func skipZeroTerminated(_ data: [UInt8], from: Int, end: Int) throws -> Int {
        var i = from
        while i < end {
            if data[i] == 0 { return i + 1 }
            i += 1
        }
        throw GzipFailure.damaged
    }

    private static func readUInt32(_ data: [UInt8], _ at: Int) -> UInt32 {
        return UInt32(data[at]) | (UInt32(data[at + 1]) << 8) | (UInt32(data[at + 2]) << 16) | (UInt32(data[at + 3]) << 24)
    }

    /// A gzip member with no name and a modification time of 0, so the same text always packs to the same bytes.
    private static func gzip(_ input: [UInt8]) throws -> [UInt8] {
        let deflated: [UInt8]
        if input.isEmpty {
            deflated = [0x03, 0x00] // a final, empty block
        } else {
            var capacity = input.count + input.count / 8 + 1024
            var produced = 0
            var buffer = [UInt8]()
            // The output can only be too small for text that doesn't compress; widen and try again.
            for _ in 0..<4 {
                buffer = [UInt8](repeating: 0, count: capacity)
                produced = buffer.withUnsafeMutableBufferPointer { dst in
                    input.withUnsafeBufferPointer { src in
                        compression_encode_buffer(dst.baseAddress!, capacity, src.baseAddress!, src.count, nil, COMPRESSION_ZLIB)
                    }
                }
                if produced > 0 { break }
                capacity *= 2
            }
            guard produced > 0 else { throw BackupWriteError.compressionFailed }
            deflated = Array(buffer[0..<produced])
        }
        var out: [UInt8] = [0x1f, 0x8b, 8, 0, 0, 0, 0, 0, 0, 0xff]
        out.append(contentsOf: deflated)
        out.append(contentsOf: littleEndian(crc32(input)))
        out.append(contentsOf: littleEndian(UInt32(truncatingIfNeeded: input.count)))
        return out
    }

    private static func littleEndian(_ v: UInt32) -> [UInt8] {
        return [UInt8(v & 0xff), UInt8((v >> 8) & 0xff), UInt8((v >> 16) & 0xff), UInt8((v >> 24) & 0xff)]
    }

    // MARK: CRC-32

    private static let crcTable: [UInt32] = (0..<256).map { n -> UInt32 in
        var c = UInt32(n)
        for _ in 0..<8 {
            c = (c & 1) != 0 ? (0xEDB88320 ^ (c >> 1)) : (c >> 1)
        }
        return c
    }

    private static func crc32(_ bytes: [UInt8]) -> UInt32 {
        var c: UInt32 = 0xFFFF_FFFF
        for b in bytes {
            c = crcTable[Int((c ^ UInt32(b)) & 0xFF)] ^ (c >> 8)
        }
        return c ^ 0xFFFF_FFFF
    }
}
