import Foundation

// The library's values in Foundation's terms, for hosts that work with `Calendar` and `Date`. The library itself never reads
// the device's calendar or locale: the host passes the one it uses.

extension Weekday {
    /// The weekday Foundation's `Calendar` numbers `calendarWeekday`, where Sunday is 1 and Saturday 7.
    public init(calendarWeekday: Int) {
        self = Weekday(rawValue: (calendarWeekday + 5) % 7 + 1) ?? .monday
    }

    /// This weekday as Foundation's `Calendar` numbers it: Sunday is 1, Saturday 7.
    public var calendarWeekday: Int { rawValue % 7 + 1 }
}

extension CivilDate {
    /// The midnight that starts this date in `timeZone`. Civil dates are Gregorian, whatever calendar the user displays.
    public func date(in timeZone: TimeZone) throws -> Date {
        var gregorian = Calendar(identifier: .gregorian)
        gregorian.timeZone = timeZone
        guard let date = gregorian.date(from: components) else { throw TimeWhisperError.backend("Invalid civil date \(year)-\(month)-\(day)") }
        return date
    }
}

extension DateOrder {
    /// Which number comes first in a date such as "11/12/2026", as `locale`'s region writes dates: the month in the United
    /// States, the day in Poland and most of the world. Year-first regions (Japan, China) put the month before the day.
    /// Nil when the region's format names no order.
    public init?(locale: Locale) {
        guard let format = DateFormatter.dateFormat(fromTemplate: "yMd", options: 0, locale: locale),
              let month = format.firstIndex(of: "M"), let day = format.firstIndex(of: "d") else { return nil }
        self = month < day ? .monthDay : .dayMonth
    }
}

extension Configuration {
    /// The settings `calendar` implies: its first weekday, and the date order of its locale's region.
    public init(calendar: Calendar, language: String) {
        self.init(dateOrder: DateOrder(locale: calendar.locale ?? .autoupdatingCurrent),
                  weekStartsOn: Weekday(calendarWeekday: calendar.firstWeekday), language: language)
    }
}
