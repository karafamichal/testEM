# Changelog

## 2.3.0

### New
- **Help with bus positions on every bus.** Under *Account → Community and location* choose:
  - **Off**: only the operator's data and the timetable.
  - **Buttons**: *Bus is here*, *Bus is leaving* and *At (stop)* on the follow notification, now on every bus without a live position, city or intercity.
  - **Automatic**: while you ride a bus you follow, your phone's position marks where the bus is, with no buttons. Only the stop the bus is between is sent, never your location. It needs precise location.
- If you had community delays on, you keep *Buttons*.

### Improved
- **More accurate bus positions.** The bus's own GPS is used when it's fresh and actually on the route, so the app no longer moves the bus ahead of reality. Positions the operator kept from long ago, or that belong to another trip, are ignored.
- **Best source first:** a rider's phone on the bus, then the bus's GPS, then riders' taps, then the operator's delay, then the timetable.

## 2.2.0

### Improved
- **Stop lists for every bus.** The operator often has no stop list for a trip (city buses in Zvolen and Banská Bystrica, like lines 7 and 8 at Zlatý Potok). testEM then takes the stops and times from cp.sk instead. The operator's live data is still used first, and its delay is still shown whenever the bus reports one. You can follow these buses too.

## 2.1.0

### New
- **Follow intercity buses.** Open a connection in the Planner, tap *Show all stops*, then *Follow this bus*. The live notification counts down to departure and shows the next stop, using timetable times from cp.sk.
- **Platforms at every stop.** When you open a connection from the Planner, each stop shows its platform.
- **Choose when to be alerted.** Under *Account → Reminders → Following a bus*, pick how early you're told the bus is coming: 1, 2, 3, 5 or 10 minutes, or your own number up to 60. It used to always be 2 minutes.
- **Community delays for intercity buses** (optional). Intercity buses don't report their position, so riders can do it. The follow notification offers *Bus is here*, *Bus is leaving*, then *At (next stop)*. The buttons move on by themselves, so if you forget one stop, just mark the next. Everyone following the same bus sees the pooled delay. Only the bus, the stop and the time are sent, with a random ID that changes for every bus. No account, no location.
- **Will I catch it?** (optional). While you wait for a bus you follow, the notification shows how long the walk to your stop is and when to leave, from your location on the phone. Your location never leaves the phone. It needs precise location permission.
- **Report a bug** under *Account*. It attaches this app's recent logs, with passwords and tokens removed. A summary, description, name and email are all optional, and you agree before anything is sent. Leave an email if you'd like a reply.
- **Support testEM** under *Account*. A link to [karafa.net/support](https://karafa.net/support/) if you'd like to support further work. Entirely voluntary.
- Both optional features are asked about once, when you first open this version. You can change them anytime under *Account → Community and location*.

### Improved
- **Nearby stops → Refresh** now gets a fresh location. Before, it could reuse a position from several minutes earlier, so the list didn't change.
- Planner results are sorted by departure time.
- The "bus arrives soon" alert shows the actual number of minutes.

### Fixed
- Selected choices, such as the report category or the alert time, are easier to see (check mark).

### Web version (iPhone)
The home-screen web app now has departures, the Planner, following a bus with push alerts, community delays, *Will I catch it?*, history insights, a PIN lock, themes, Slovak and English, bug reports and the support link. Ticket and history times now use your local time; they used to show 2 hours early.
