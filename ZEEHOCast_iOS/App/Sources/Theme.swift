import SwiftUI

/// 高级暗色主题（碳黑 + 极核橙）
enum Theme {
    static let bg = Color(red: 0.043, green: 0.059, blue: 0.078)      // #0B0F14
    static let card = Color(red: 0.078, green: 0.106, blue: 0.141)    // #141B24
    static let card2 = Color(red: 0.102, green: 0.137, blue: 0.188)   // #1A2330
    static let accent = Color(red: 1.0, green: 0.416, blue: 0.0)      // #FF6A00
    static let accentDim = Color(red: 0.8, green: 0.333, blue: 0.0)
    static let textPrimary = Color(red: 0.949, green: 0.961, blue: 0.969)
    static let textSecondary = Color(red: 0.541, green: 0.58, blue: 0.639)
    static let textTertiary = Color(red: 0.337, green: 0.376, blue: 0.439)
    static let divider = Color(red: 0.125, green: 0.165, blue: 0.212)
    static let success = Color(red: 0.169, green: 0.831, blue: 0.541)
    static let danger = Color(red: 1.0, green: 0.302, blue: 0.31)
}

struct CardStyle: ViewModifier {
    func body(content: Content) -> some View {
        content
            .background(Theme.card)
            .cornerRadius(20)
            .overlay(
                RoundedRectangle(cornerRadius: 20)
                    .stroke(Theme.divider, lineWidth: 1)
            )
    }
}

extension View {
    func cardStyle() -> some View { self.modifier(CardStyle()) }
}
