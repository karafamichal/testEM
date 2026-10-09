# Changelog

## 2.13.1

### Fixed
- **Crash on the IDS BBSK ticket or account right after unlocking** (EM-0043, EM-0045): the saved IDS BBSK account was read back wrongly in the Play build, so opening it before the e-shop's fresh data arrived crashed the app.
- **Cut-off texts on round watches** (EM-0044): long destinations get two lines, and texts keep clear of the round screen's edge.
- **Reports from the watch** are filed as the watch's (its model, Wear OS and the watch app's version); the phone's details are in the logs.

## 2.13.0

### New
- **Plan&Ride on Wear OS** (EM-0041): a watch app for Wear OS 3 and newer (Galaxy Watch4 onward). While you follow a bus on the phone, the watch shows its countdown on the watch face (Ongoing Activity), the line, destination, delay and your ride so far as a ring, the next stops, all stops (where you get on and off marked), the journey's transfers and the trip's alerts, a tile with the departure time, and a button to stop following. Each alert comes to the watch once (from the phone), and the phone's own follow card stays off the watch while the watch app shows the bus.
- **More on the watch**: the follow notification's buttons (*Bus is here*, *Bus is leaving*, *At <stop>*, *It already left*) right under the countdown; *My trips* to start a starred journey or a regular ride from the watch; departures at your favourite stops and *Departures nearby* (located by the phone, so the watch's GPS stays off); credit; tiles for favourite stops and credit; a complication with the followed bus's countdown for any watch face; and *Get off at the next stop* with its own vibration (three long pulses), announced once by the watch. The watch app speaks the phone app's language.
- **Watch settings** (Account → Watch): show the bus on the watch or not, the countdown on the watch face, alerts on the watch too or on the phone only, credit on the watch, and how many next stops the watch lists.
- **Reports from the watch**: *Report a problem* in the watch app sends its logs to the phone, which opens the bug report with them attached.
- **IDS BBSK account and QR code** (EM-0023): add your eshop.idsbbsk.sk account next to (or instead of) sadzv.qrbus.me, on the welcome screen or in Account. The home tab shows both tickets on a slider (swipe, or the SAD Zvolen / IDS BBSK switch above it); the IDS BBSK one is the virtual card's QR code, kept for offline use. Account shows the IDS credit, valid tickets and identifiers, History the IDS top-ups and passes, one account at a time or both together.
- **The right ticket for each bus**: while following a journey the home tab switches by itself: SAD Zvolen for city buses in Banská Bystrica and Zvolen, IDS BBSK for regional lines (060xxx, also the city buses of Žiar nad Hronom, Brezno and Nová Baňa). It can be turned off, and the ticket shown first chosen, in Account → Tickets.
- **Email for reports** (EM-0037): with two accounts under different emails, the app asks once which is yours and fills it in bug reports and suggestions; change it in Account.
- **Add credit to your card** (*Account → Add credit*): pick an amount (2–50 €, 5 € suggested, the limits come from sadzv) and pay on the operator's own payment gateway (GP webpay), the same as on sadzv.qrbus.me. The payment goes straight to SAD Zvolen: Plan&Ride only opens the gateway, takes no fee and never sees your card details. Afterwards the app shows whether it was paid and refreshes your credit and History.

### Fixed
- **Logs after a crash**: a bug report sent after the app crashed now includes the log lines from before the crash (they belonged to the crashed session and were left out), also when the crash was in the background (following a bus, a regular ride). Same on the watch.
- **A late bus on a journey with a change** (EM-0040): the app moved on to the next bus by the timetable while the rider was still on the first one, 20 min late. With the phone's position (automatic mode) it now keeps following the bus as late as it really is, also while it stands in traffic, and doesn't change before the rider gets there. When the next bus leaves before the late one arrives (plus your change time), it warns *You may miss line …* in time to look for another way.
- **Getting off elsewhere in a planner result** (EM-0039): tapping another stop in a bus's stop list (*Show all stops*) only marked where following ends; now the connection changes too: the rest of the journey is planned from that stop, a short walk allowed.
- **Times on phones set to another time zone**: *Now* in the planner searched from the phone's clock time instead of Slovak time, and the stop list in *Change stop* was off by the difference. All bus times are now Slovak time.
- **Widget layout**: the route line starts and ends at your stops like on the home card (it ran past them), and the last line (*Will I catch it?*) is no longer cut off.

## 2.12.0

### New
- **Choose your own change in a planner result** (EM-0026, EM-0030): *Change stop* next to *Change here* opens a two-step editor. First pick where you get off the first bus (any of its later stops), then where you get on the next one: the same stop, a stop nearby (with the walk in minutes) or any stop you search for. A preview shows the new onward bus and the arrival; *Use this change* updates the connection without a new search, and *Follow the whole journey* follows it. Example: Tajovského školy → Lipovec Potraviny, get off at EUROPA SC, walk to Nám. SNP, line 15 from there. Needs emhub 1.15.1.
- **Unsent reports and suggestions are kept** (EM-0027, EM-0031): leave the form and come back later; the text, name, email, category, the logs switch and the photos are still there until you send it.
- **History insights you can tap** (EM-0028): a bar of the chart (or *Spent this month*) opens that month, a stop opens that stop: spent, rides, average fare, top-ups, rides by weekday and time of day, the top stops and the records.

### Changed
- **The widget looks like the live card on the home tab** (EM-0025): the same colours (your theme), line, destination, countdown, delay, stops to go and the bus moving along your part of the route.
- **Waiting at your stop for a late bus** (EM-0033): when your phone stands at your stop after the bus was due, the bus is kept there as late instead of moving on with the timetable, and the notification asks *Bus is here* (reported for other riders too) or *It already left*, also in automatic mode.
- *Alert on timetable times too* (Will I catch it?) is on by default (EM-0029).

### Fixed
- **"You won't make it" from an old position** (EM-0032): the location was checked only every few minutes and the estimate kept using the old one while you walked. Before *Leave now* or *You won't make it*, the app now gets a fresh position (and never alerts from one older than 45 s).
- **History after unlocking on the History tab**: it said *History not available* until you went to another tab and back. It now shows a loading animation and loads by itself.

## 2.11.0

### New
- **Time to change buses** (Settings): how much time the planner leaves between two buses, from *any* (even 0 min) to 10 min. Short changes find more connections, like line 1 → 60101 at Štadlerovo nábrežie with 1 minute (report EM-0022); a longer one is safer when buses run late. The default is 1 minute (it was a fixed 2 minutes).
- Up to **5 photos** in a bug report or suggestion (was 3).

### Changed
- **Faster planner**: searches take about half the time (Banská Bystrica – Zvolen in about 0.3 s), and the first search of a day no longer waits for the day's timetable to be prepared (emhub 1.14.0).

## 2.10.1

### Fixed
- **"You won't make it" while you're already on the bus** (reports EM-0020, EM-0021): *Will I catch it?* measured the walk to the stop even after the bus had left with you on it, or after you tapped *Bus is here*. Once you tap *Bus is here* / *Bus is leaving*, or your phone moves along the route past your stop, you count as being with the bus: no more warnings that it leaves before you get there.

## 2.10.0

### New
- **Home-screen widget** (report EM-0017): one widget that changes with what you're doing. Following a bus, it is the live card from the home tab (countdown, delay, progress between your stops, × to stop). Otherwise it lists your starred journeys (tap one to start following it) and the next buses at your first two favourite stops, scrollable. It refreshes with every follow update, when you open the app, and every 30 min.

### Fixed
- **Banská Bystrica city buses on the departure boards** (report EM-0019): sadzv sends them without a destination or stop list. The board now takes both from emhub's timetable; buses that end their trip at the stop (sadzv lists those too, e.g. at B.B. Roosevelt hospital, platform 12) say *Ends here* and show their stops up to it. Needs emhub 1.13.0.

## 2.9.0

### New
- **The followed bus on the home screen:** a live card with the countdown, the delay and the bus moving along your part of the route (your stop to where you get off), with a pulsing *Live* badge when its real position is known. Tap it for the stops, × to stop following.
- **Screenshots and photos in bug reports and suggestions** (report EM-0016): attach up to 3. The app makes them smaller (1600 px) and re-saves them as JPEG, which removes the location and other photo details; emhub checks and cleans them again and only the developer can see them.

- **New day and time pickers** in the planner and for regular rides: a calendar with Today and Tomorrow shortcuts, and a 24-hour clock you can also type into, with a Now button.
- **Show the PIN while typing** it (the eye button), like the password.
- **Haptic feedback** on taps and actions (following a bus, starring a stop, picking a time). It can be turned off in Settings; the phone's own touch-feedback setting is respected too.

### Changed
- **Many more connections:** emhub now converts the CIS timetables (portal.cp.sk) itself instead of using transiq's feeds, which dropped trips not starting at a line's first stop; about 60 % more trips have their times (report EM-0016).
- Emails about your reports now come from Plan&Ride instead of testEM.
- **Automatic bus positions** ask for location "Allow all the time" right after "While using the app", on first start and in Settings, instead of only showing a warning there.

### Fixed
- A new install (Android 13 and later) showed the app in English while Settings said Slovenčina.
- Typing a full stop name like "Štadlerovo nábrežie" now finds stops the timetable abbreviates ("Štadlerovo nábr.").

## 2.8.0

### New
- **City buses in the planner for any day:** emhub reads the whole day's timetable of S.A.D. Zvolen's city lines (Zvolen, Banská Bystrica) every morning, so connections with city buses, and changes between city and regional buses, are found for tomorrow and later too (reports EM-0011, EM-0012). They are followed live.
- **Star or repeat your own journey:** a journey built bus by bus from the boards gets the same star (home tab) and repeat (regular ride) buttons as a planner result.
- **Regular rides:** different days in odd and even weeks (or only one of them), dates to skip (holidays), and a switch between the same lines and any bus at that time. Tap a ride on the home tab to change it.

## 2.7.1

### Fixed
- **City buses in the planner** (report EM-0010): Zvolen and Banská Bystrica city buses of S.A.D. Zvolen aren't in the open timetables, so a trip like Zvolen, Nám. SNP → Lipovec found nothing. The planner now also asks S.A.D. Zvolen's live data for direct buses between the two stops (for the next several hours), and you follow them live, with their delay and position. Changes between city lines aren't found this way yet.
- The departure board no longer crashes when the operator lists the same bus twice.

## 2.7.0

### Changed
- **Timetables from our own server.** The planner, other carriers' buses on the departure boards and the stop lists of buses without live data now come from emhub, which loads the open timetables of the Slovak bus carriers every night. The app no longer uses cp.sk.
- **Stop shortcuts:** type the start of each word, e.g. *z z p k* or *zvol zla po kon* for Zvolen, Zlatý Potok konečná, or *bb as* for Banská Bystrica, AS.
- For now the planner covers regional and long-distance buses. Trains and the city buses of Banská Bystrica and Zvolen are not in it (city buses still show live on the boards).
- Journeys starred before 2.7.0 point at cp.sk: star them again.

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
