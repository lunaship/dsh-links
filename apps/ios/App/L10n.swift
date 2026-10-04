import Foundation

enum L10n {
    static func string(_ key: String, fallback: String, locale: Locale? = nil) -> String {
        let language = locale?.language.languageCode?.identifier
        let region = language == "zh" ? "zh-Hans" : language
        let bundle =
            region.flatMap { Bundle.main.path(forResource: $0, ofType: "lproj") }
            .flatMap { Bundle(path: $0) } ?? .main
        return NSLocalizedString(key, tableName: nil, bundle: bundle, value: fallback, comment: "")
    }
}
