# Changelog

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
