import SwiftUI
import UIKit

/// Android's category icons are names from its icon catalog (`IconCatalog`: "cart", "restaurant", "bus"...), stored in the backup as
/// `symbol:<name>`. iOS draws each with an SF Symbol. Each name has candidates, best first; the first one this iOS has is used
/// (a symbol newer than the phone's iOS is skipped), and a name nobody mapped shows a tag. Names are never renamed, only added.
enum IconSymbols {

    private static let candidates: [String: [String]] = [
        "cart": ["cart"], "grocery": ["basket", "cart"], "restaurant": ["fork.knife"], "cafe": ["cup.and.saucer", "mug"],
        "coffee": ["mug", "cup.and.saucer"], "fastfood": ["takeoutbag.and.cup.and.straw", "fork.knife"], "pizza": ["fork.knife"],
        "lunch": ["takeoutbag.and.cup.and.straw", "fork.knife"], "bakery": ["birthday.cake", "fork.knife"],
        "icecream": ["snowflake", "fork.knife"], "cake": ["birthday.cake"], "drink": ["cup.and.saucer"],
        "bar": ["wineglass", "cup.and.saucer"], "liquor": ["wineglass"], "home": ["house"], "house": ["house.fill", "house"],
        "apartment": ["building.2", "building"], "bed": ["bed.double", "bed.double.fill"], "chair": ["chair.lounge", "chair"],
        "sofa": ["sofa", "chair.lounge"], "kitchen": ["refrigerator", "fork.knife"], "light": ["lightbulb"],
        "electricity": ["bolt"], "water": ["drop"], "gas": ["flame"], "wifi": ["wifi"], "phone": ["phone"],
        "smartphone": ["iphone"], "tv": ["tv"], "laptop": ["laptopcomputer"], "computer": ["desktopcomputer"],
        "headphones": ["headphones"], "watch": ["applewatch", "clock"], "camera": ["camera"], "car": ["car"],
        "fuel": ["fuelpump"], "charging": ["ev.charger", "bolt.car", "bolt"], "parking": ["parkingsign", "p.circle"],
        "carrepair": ["wrench.and.screwdriver", "wrench"], "bus": ["bus"], "train": ["tram", "train.side.front.car"],
        "taxi": ["car.side", "car"], "bike": ["bicycle"], "flight": ["airplane"], "hotel": ["bed.double"],
        "luggage": ["suitcase", "bag"], "beach": ["beach.umbrella", "sun.max"], "map": ["map"],
        "movie": ["film", "movieclapper"], "theater": ["theatermasks"], "music": ["music.note"], "games": ["gamecontroller"],
        "tennis": ["figure.tennis", "tennis.racket", "sportscourt"], "soccer": ["soccerball", "sportscourt"],
        "cricket": ["figure.cricket", "sportscourt"], "basketball": ["basketball", "sportscourt"],
        "gym": ["dumbbell", "figure.strengthtraining.traditional"], "pool": ["figure.pool.swim", "drop"],
        "hiking": ["figure.hiking", "figure.walk"], "park": ["leaf", "tree"], "spa": ["leaf"],
        "yoga": ["figure.yoga", "figure.mind.and.body"], "hospital": ["cross.case", "cross"],
        "medical": ["stethoscope", "cross.case"], "medicine": ["pills", "cross.case"], "vaccine": ["syringe", "cross.case"],
        "therapy": ["brain.head.profile", "brain"], "baby": ["figure.and.child.holdinghands", "face.smiling"],
        "stroller": ["figure.and.child.holdinghands", "face.smiling"], "toys": ["teddybear", "gift"],
        "school": ["graduationcap"], "book": ["book"], "stories": ["books.vertical", "book"], "work": ["briefcase"],
        "business": ["briefcase", "building.2"], "pets": ["pawprint"], "clothes": ["tshirt"], "jewelry": ["diamond", "sparkles"],
        "haircut": ["scissors"], "beauty": ["face.smiling", "sparkles"], "art": ["paintpalette", "paintbrush"],
        "brush": ["paintbrush"], "tools": ["hammer", "wrench"], "handyman": ["wrench.and.screwdriver", "hammer"],
        "plumbing": ["wrench.and.screwdriver", "drop"], "garden": ["leaf", "tree"], "flowers": ["camera.macro", "leaf"],
        "laundry": ["washer", "tshirt"], "cleaning": ["sparkles"], "shipping": ["box.truck", "shippingbox"],
        "package": ["shippingbox"], "store": ["storefront", "bag"], "storefront": ["storefront", "bag"], "bag": ["bag"],
        "mall": ["bag", "cart"], "gift": ["gift"], "redeem": ["gift"], "party": ["party.popper", "sparkles"],
        "charity": ["heart", "hands.sparkles"], "heart": ["heart"], "temple": ["building.columns", "building"],
        "mosque": ["building.columns", "building"], "church": ["building.columns", "building"],
        "subscriptions": ["repeat", "arrow.triangle.2.circlepath"], "receipt": ["receipt", "doc.text"],
        "bills": ["doc.text", "doc"], "card": ["creditcard"], "bank": ["building.columns"],
        "wallet": ["wallet.pass", "creditcard"], "savings": ["banknote", "dollarsign.circle"], "payments": ["banknote"],
        "money": ["dollarsign.circle"], "exchange": ["arrow.left.arrow.right"], "trending": ["chart.line.uptrend.xyaxis"],
        "chart": ["chart.xyaxis.line", "chart.line.uptrend.xyaxis"], "insurance": ["shield", "checkmark.shield"],
        "legal": ["scale.3d", "doc.text"], "policy": ["doc.text", "checkmark.shield"], "elderly": ["figure.walk", "person"],
        "family": ["person.2", "person.3"], "people": ["person.2"], "person": ["person"], "cloud": ["cloud"], "lock": ["lock"],
        "event": ["calendar"], "smoking": ["smoke", "flame"], "sell": ["tag"], "loyalty": ["heart.circle", "star"],
    ]

    private static let accounts: [String: String] = [
        "CHECKING": "building.columns", "SAVINGS": "dollarsign.circle", "CASH": "banknote", "CREDIT_CARD": "creditcard",
        "INVESTMENT": "chart.xyaxis.line", "LOAN": "doc.text", "PREPAID": "wallet.pass", "OTHER_ASSET": "shippingbox",
        "OTHER_DEBT": "receipt", "FIXED_DEPOSIT": "lock",
    ]

    /// The SF Symbol for an icon-catalog [name]; "tag" for a name that has none.
    static func symbol(for name: String) -> String {
        let options: [String] = candidates[name] ?? []
        for option in options where UIImage(systemName: option) != nil {
            return option
        }
        return "tag"
    }

    /// The SF Symbol for an account kind (an `AccountType` name).
    static func account(_ type: String) -> String {
        let wanted: String = accounts[type] ?? "banknote"
        return UIImage(systemName: wanted) != nil ? wanted : "banknote"
    }
}

/// A category's round badge: its emoji, or its catalog symbol in its colour; a letter when the icon is a picture only Android has.
struct CategoryIconView: View {
    let choice: CategoryChoiceData
    let size: CGFloat

    private var color: Color { Color(argb: choice.color) }

    var body: some View {
        ZStack {
            Circle().fill(color.opacity(0.22))
            glyph
        }
        .frame(width: size, height: size)
        .accessibilityHidden(true)
    }

    @ViewBuilder
    private var glyph: some View {
        if choice.iconKind == "emoji" {
            Text(choice.iconValue).font(.system(size: size * 0.5))
        } else if choice.iconKind == "symbol" {
            Image(systemName: IconSymbols.symbol(for: choice.iconValue))
                .font(.system(size: size * 0.46, weight: .medium))
                .foregroundColor(color)
        } else {
            Text(String(choice.name.prefix(1)).uppercased())
                .font(.system(size: size * 0.46, weight: .semibold))
                .foregroundColor(color)
        }
    }
}
