import SwiftUI
import Foundation
import UIKit

struct ContentView: View {
    @StateObject var viewModel = TestEMViewModel()
    @Environment(\.scenePhase) var scenePhase
    @Environment(\.colorScheme) var colorScheme
    @State var showSettings = false
    @State var settingsPage: SettingsPage = .root
    @State var showAccountDialog = false
    @State var showHistoryScreen = false
    @State var showTokenInfoDialog = false
    @State var showFullscreenQr = false
    @State var showNfcUidDialog = false
    @State var nfcUidToShow = ""
    @State var showLogoutConfirm = false
    @State var showChangePinSheet = false
    @State var changePinCurrent = ""
    @State var changePinNew = ""
    @State var changePinConfirm = ""
    @State var changePinError = ""
    @State var customTimeout = ""
    @State var presetName = ""
    @State var primaryHex = ""
    @State var secondaryHex = ""
    @State var tertiaryHex = ""

    var body: some View {
        Group {
            if !viewModel.isPinSet {
                ZStack {
                    appBackground
                        .ignoresSafeArea()
                    PinSetupView(viewModel: viewModel)
                        .padding(.horizontal, 16)
                        .padding(.vertical, 12)
                }
            } else if !viewModel.isAppUnlocked {
                ZStack {
                    appBackground
                        .ignoresSafeArea()
                    PinUnlockView(viewModel: viewModel)
                        .padding(.horizontal, 16)
                        .padding(.vertical, 12)
                }
            } else {
                NavigationStack {
                    ZStack {
                        appBackground
                            .ignoresSafeArea()
                        if viewModel.isLoggedIn {
                            if showSettings {
                                settingsSheet
                            } else if showHistoryScreen {
                                historyScreenSheet
                            } else {
                                daemonView
                                    .padding(.horizontal, 16)
                                    .padding(.vertical, 12)
                            }
                        } else {
                            loginView
                                .padding(.horizontal, 16)
                                .padding(.vertical, 12)
                        }
                    }
                    .toolbar {
                        if viewModel.isLoggedIn && !showSettings && !showHistoryScreen {
                            ToolbarItem(placement: .topBarLeading) {
                                Button {
                                    showAccountDialog = true
                                } label: {
                                    Text(viewModel.email.isEmpty ? "testEM" : viewModel.email)
                                        .font(.headline)
                                }
                            }

                            ToolbarItem(placement: .topBarTrailing) {
                                Button {
                                    settingsPage = .root
                                    showSettings = true
                                } label: {
                                    Image(systemName: "gearshape.fill")
                                }
                            }
                        }
                    }
                }
                .sheet(isPresented: $showChangePinSheet) {
                    changePinSheet
                }
                .sheet(isPresented: $showAccountDialog) {
                    accountDialogSheet
                }
                .sheet(isPresented: $showTokenInfoDialog) {
                    tokenInfoSheet
                }
                .fullScreenCover(isPresented: $showFullscreenQr) {
                    fullscreenQrView
                }
                .alert("NFC UID", isPresented: $showNfcUidDialog) {
                    Button("Copy") {
                        UIPasteboard.general.string = nfcUidToShow
                    }
                    Button("Close", role: .cancel) { }
                } message: {
                    Text("Copy this UID to set it on the website:\n\n\(nfcUidToShow)")
                }
                .alert("Logout?", isPresented: $showLogoutConfirm) {
                    Button("Cancel", role: .cancel) { }
                    Button("Yes, Logout", role: .destructive) {
                        viewModel.logout()
                        showSettings = false
                        showHistoryScreen = false
                    }
                } message: {
                    Text("Are you sure you want to logout?")
                }
            }
        }
        .onChange(of: scenePhase) { _, newValue in
            viewModel.handleScenePhase(newValue)
        }
        .tint(viewModel.currentAccentColor)
    }

    private var appBackground: some View {
        let top: Color
        let bottom: Color
        if viewModel.amoledEnabled {
            top = .black
            bottom = Color(red: 0.05, green: 0.05, blue: 0.05)
        } else if colorScheme == .dark {
            top = Color(red: 0.10, green: 0.13, blue: 0.12)
            bottom = Color(red: 0.06, green: 0.08, blue: 0.08)
        } else {
            top = Color(red: 0.92, green: 0.97, blue: 0.95)
            bottom = Color(red: 0.84, green: 0.92, blue: 0.89)
        }
        return LinearGradient(colors: [top, bottom], startPoint: .topLeading, endPoint: .bottomTrailing)
    }
}
