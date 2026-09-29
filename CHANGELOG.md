# Changelog

## 2.6.1

### Fixed
- **Automatic mode with the app closed** (report EM-0009): the phone's GPS stopped once the app left the screen, so the bus was only marked while the app was open. It now keeps working with the screen off. Rides that start by themselves (regular rides) need location *Allow all the time*; *Settings → Community and location* offers it. Opening the app once also fixes a ride that's already being followed.
- **Following survives app updates.** Updating the app used to stop the followed bus. Now it's picked up again right after the update, or when you next open the app.
- The follow notification's progress bar no longer floods the logs on trips with more than 10 stops.

## 2.6.0

### New
- **testEM is now Plan&Ride**, with a new icon. (The package name stays `com.ksjd.testem`, so it updates in place.)
- **No account needed.** Departures, the planner and following a bus work without signing in. On first start you can add your sadzv.qrbus.me account or skip; add or remove it any time in *Settings*. Removing it also removes the PIN.
- **Opens straight away with an account.** The ticket, credit, history and account details stay hidden until you unlock with your PIN or fingerprint. Tap the ticket to unlock. *Lock now* is in *Account*.
- **My journeys** (report EM-0008): star a connection in the planner and it shows on the home tab under the ticket, with a *Follow* button. Your favourite stops' next buses follow below.
- **Regular rides:** tap the repeat icon on a connection, then pick days of the week, a date range, extra dates and how early to start. Before each departure the app finds that day's bus and starts following it by itself. On Android 12+, allow alarms for it to start on time.
- **Pick a date in the planner**, not only a time.
- **Other carriers on the departure board:** intercity buses of every carrier from the cp.sk timetable (Arriva, Slovak Lines, FlixBus and more), next to the operator's live ones. Tap one to see its stops and follow it.
- **Suggest a feature** from *Settings*. Suggestions have their own list in emhub.
- **Website:** [par.karafa.net](https://par.karafa.net) with what's new, support and the privacy policy. *Full changelog* and *Settings → Privacy policy* open it.
- **Usage statistics** switch in *Settings*: anonymous usage via Firebase Analytics, which no longer uses the advertising ID.

### Improved
- **GPS only when needed.** On the way to the stop the location is checked every 1–3 min. Live GPS starts at the stop from 4 min before the bus, or earlier by as much as the bus is early or usually runs early. *Will I catch it?* counts with that earlier time too.
- **Stopped or drove past** (report EM-0007): a stop is judged only once the bus has left the stop's area. A bus that halts a little before or after the stop point now counts as stopped. Phones now also report while the bus stands.
- The walk to the stop is found for planner buses too (their stops come from cp.sk).

### Security
- The sadzv.qrbus.me password is stored encrypted with a key in the Android Keystore.
- The PIN uses a slow hash (PBKDF2). After 5 wrong PINs, entry locks for 30 s, doubling each time up to an hour.
- App data is excluded from cloud backups and device transfers.

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
